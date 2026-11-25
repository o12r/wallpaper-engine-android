package com.weworkshop.app.wallpaper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.weworkshop.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Captures the phone's media playback (AudioPlaybackCapture via MediaProjection, the user approves a one-off
 * "record screen" prompt) and feeds a 64-band spectrum to [MusicBridge]. Needed because [android.media.audiofx.Visualizer]
 * on the global output is refused on recent Android versions.
 */
class AudioCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null || code == 0) { stopSelf(); return START_NOT_STICKY }
        startForegroundCompat()
        try {
            begin(code, data)
        } catch (t: Throwable) {
            Log.w(TAG, "capture failed", t)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, AudioCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(getString(R.string.capture_title))
            .setContentText(getString(R.string.capture_text))
            .setOngoing(true)
            .addAction(0, getString(R.string.capture_stop), stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(ID, n)
    }

    private fun begin(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("no projection")
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Handler(Looper.getMainLooper()).post { stopSelf() } }
        }, Handler(Looper.getMainLooper()))
        projection = mp
        val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val rec = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setAudioPlaybackCaptureConfig(cfg)
            .setBufferSizeInBytes(N * 2 * 4)
            .build()
        rec.startRecording()
        record = rec
        running = true
        _active.value = true
        thread = Thread({ loop(rec) }, "we-audio-capture").also { it.start() }
    }

    private fun loop(rec: AudioRecord) {
        val pcm = ShortArray(N)
        val re = FloatArray(N)
        val im = FloatArray(N)
        var wsum = 0f
        val win = FloatArray(N) { i -> (0.5 - 0.5 * cos(2 * PI * i / (N - 1))).toFloat().also { wsum += it } }
        while (running) {
            var got = 0
            while (got < N && running) {
                val r = rec.read(pcm, got, N - got)
                if (r <= 0) { running = false; break }
                got += r
            }
            if (!running) break
            for (i in 0 until N) { re[i] = pcm[i] / 32768f * win[i]; im[i] = 0f }
            fft(re, im)
            val mags = FloatArray(N / 2) { k -> 2f * sqrt(re[k] * re[k] + im[k] * im[k]) / wsum }
            MusicBridge.feedSpectrum(mags)
        }
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val a = re[i]; re[i] = re[j]; re[j] = a; val b = im[i]; im[i] = im[j]; im[j] = b }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    override fun onDestroy() {
        running = false
        _active.value = false
        runCatching { thread?.join(500) }
        runCatching { record?.stop(); record?.release() }
        record = null
        runCatching { projection?.stop() }
        projection = null
        MusicBridge.clearSpectrum()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AudioCapture"
        private const val CHANNEL = "audio_capture"
        private const val ID = 4711
        private const val ACTION_STOP = "stop"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val RATE = 44100
        private const val N = 1024

        private val _active = MutableStateFlow(false)
        /** True while system audio is being captured. */
        val active: StateFlow<Boolean> = _active.asStateFlow()

        /** Set once the "record screen" prompt has been shown in this process. */
        @Volatile var prompted = false

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            ctx.startForegroundService(
                Intent(ctx, AudioCaptureService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data),
            )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AudioCaptureService::class.java))
        }

        /** The "record screen" prompt; pass its result to [start]. */
        fun requestIntent(ctx: Context): Intent =
            ctx.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
    }
}
