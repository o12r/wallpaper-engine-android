package com.weworkshop.app

import android.app.Application
import android.util.Log
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import `in`.dragonbra.javasteam.util.log.LogListener
import `in`.dragonbra.javasteam.util.log.LogManager
import androidx.annotation.StringRes
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.request.crossfade
import com.weworkshop.app.download.DownloadManager
import com.weworkshop.app.steam.SteamSession

class App : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        installBouncyCastle()
        LogManager.addListener(JavaSteamLogBridge)
        AppSettings.init(this)
        SteamSession.init(this)
        DownloadManager.init(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(AnimatedImageDecoder.Factory()) }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("img"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()

    /**
     * Android ships a stripped-down provider also named "BC" (no SHA-1 MessageDigest, among
     * others). JavaSteam's CryptoHelper tries to register the full BouncyCastle as "BC", which
     * silently fails because the name is taken, so depot manifest hashing then throws
     * "no such algorithm: SHA-1 for provider BC". Replace the platform one before JavaSteam loads.
     * (Platform TLS uses Conscrypt, not BC, so this doesn't affect HTTPS.)
     */
    private fun installBouncyCastle() {
        try {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        } catch (t: Throwable) {
            Log.e("App", "failed to install BouncyCastle", t)
        }
    }

    /**
     * Forwards JavaSteam's internal log to logcat (tag "JavaSteam"). Debug lines only from the
     * download / CDN code (the rest logs every packet); errors from everywhere.
     */
    private object JavaSteamLogBridge : LogListener {
        private const val TAG = "JavaSteam"

        override fun onLog(clazz: Class<*>, message: String?, throwable: Throwable?) {
            val n = clazz.name
            if ("depotdownloader" in n || ".cdn." in n || "steamcontent" in n || "steamapps" in n) {
                Log.d(TAG, "${clazz.simpleName}: $message", throwable)
            }
        }

        override fun onError(clazz: Class<*>, message: String?, throwable: Throwable?) {
            Log.e(TAG, "${clazz.simpleName}: $message", throwable)
        }
    }

    companion object {
        lateinit var instance: App
            private set

        /** Localized string for non-UI code (errors, notifications, status text). */
        fun str(@StringRes id: Int, vararg args: Any): String = instance.getString(id, *args)
    }
}
