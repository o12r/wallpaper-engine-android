package com.weworkshop.app.download

import com.weworkshop.app.App
import com.weworkshop.app.AppSettings
import com.weworkshop.app.R
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import com.weworkshop.app.steam.SteamSession
import com.weworkshop.app.steam.WorkshopItem
import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import `in`.dragonbra.javasteam.depotdownloader.data.PubFileItem
import `in`.dragonbra.javasteam.enums.EWorkshopFileType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

sealed interface DownloadStatus {
    data object Queued : DownloadStatus
    data class Running(val progress: Float, val message: String) : DownloadStatus
    data class Done(val exportedTo: String?) : DownloadStatus
    data class Failed(val message: String) : DownloadStatus
}

data class DownloadEntry(
    val id: Long,
    val title: String,
    val previewUrl: String,
    val type: String?,
    val fileSize: Long,
    val status: DownloadStatus,
    /** Set for items found by [DownloadManager.scanPublic]: their files are read in place from here. */
    val localDir: String? = null,
)

/**
 * Parallel workshop download queue (up to [AppSettings.concurrentDownloads] items at once). Items land in app-specific storage
 * (Android/data/<pkg>/files/workshop/<id>) and are then copied to the public
 * Download/WallpaperEngine/<id> folder so other apps / file managers can see them.
 */
object DownloadManager {
    private const val TAG = "DownloadManager"
    const val PUBLIC_DIR = "WallpaperEngine"

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Ids waiting to download, in the order they will run. */
    private val _queue = MutableStateFlow<List<Long>>(emptyList())
    val queue: StateFlow<List<Long>> = _queue.asStateFlow()

    /** Entry an item had before it was (re)queued, restored if that queued run is cancelled. */
    private val previousStatus = mutableMapOf<Long, DownloadEntry?>()

    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** Downloads currently running, by item id. Only touched from the dispatcher and [cancel]. */
    private val running = java.util.concurrent.ConcurrentHashMap<Long, Job>()
    private var workerStarted = false

    private val _entries = MutableStateFlow<Map<Long, DownloadEntry>>(emptyMap())
    val entries: StateFlow<Map<Long, DownloadEntry>> = _entries.asStateFlow()

    val rootDir: File by lazy {
        File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "workshop").apply { mkdirs() }
    }
    private val indexFile: File by lazy { File(appContext.filesDir, "downloads.json") }

    @Synchronized
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        loadIndex()
    }

    fun itemDir(id: Long) = File(rootDir, id.toString())

    /** Where an item's files are: the scanned public folder for imported items, else app storage. */
    fun contentDir(id: Long): File = _entries.value[id]?.localDir?.let(::File) ?: itemDir(id)

    /** Downloaded content files (excluding DepotDownloader bookkeeping). */
    fun contentFiles(id: Long): List<File> =
        contentDir(id).walkTopDown()
            .onEnter { it.name != DepotDownloader.CONFIG_DIR }
            .filter { it.isFile }
            .toList()

    fun enqueue(item: WorkshopItem) {
        val cur = _entries.value[item.id]
        if (cur != null && (cur.status is DownloadStatus.Queued || cur.status is DownloadStatus.Running)) return
        synchronized(previousStatus) { previousStatus[item.id] = cur }
        val entry = DownloadEntry(item.id, item.title, item.previewUrl, item.type, item.fileSize, DownloadStatus.Queued)
        _entries.update { it + (item.id to entry) }
        _queue.update { it + item.id }
        startWorker()
        ContextCompat.startForegroundService(appContext, Intent(appContext, DownloadService::class.java))
        wake.trySend(Unit)
    }

    /**
     * Dispatcher: keeps up to [AppSettings.concurrentDownloads] downloads running, always taking
     * from the front of [queue] so reordering takes effect for the next free slot.
     */
    @Synchronized
    private fun startWorker() {
        if (workerStarted) return
        workerStarted = true
        // Raising the limit in settings should start more queued items right away.
        scope.launch { AppSettings.concurrentDownloads.collect { wake.trySend(Unit) } }
        scope.launch {
            while (true) {
                while (running.size < AppSettings.concurrentDownloads.value) {
                    val next = _queue.value.firstOrNull() ?: break
                    _queue.update { it - next }
                    val job = launch { runDownload(next) }
                    running[next] = job
                    job.invokeOnCompletion {
                        running.remove(next, job)
                        wake.trySend(Unit)
                    }
                }
                wake.receive()
            }
        }
    }

    /**
     * Cancels a queued or running download. A cancelled re-download of a finished item goes back
     * to its previous state; otherwise the entry and any partial files are removed.
     */
    fun cancel(id: Long) {
        if (id in _queue.value) {
            _queue.update { it - id }
            restoreOrRemove(id)
        } else {
            running[id]?.cancel() // runDownload cleans up in its CancellationException handler
        }
    }

    private fun restoreOrRemove(id: Long) {
        val prev = synchronized(previousStatus) { previousStatus.remove(id) }
        if (prev?.status is DownloadStatus.Done) {
            _entries.update { it + (id to prev) }
        } else {
            itemDir(id).deleteRecursively()
            _entries.update { it - id }
        }
        saveIndex()
    }

    /** Moves a queued item by [delta] places (negative = earlier). */
    fun move(id: Long, delta: Int) {
        _queue.update { q ->
            val i = q.indexOf(id)
            if (i < 0) return@update q
            val j = (i + delta).coerceIn(0, q.lastIndex)
            q.toMutableList().apply { removeAt(i); add(j, id) }
        }
    }

    /** Moves a queued item to the front so it downloads next. */
    fun moveToFront(id: Long) {
        _queue.update { q -> if (id in q) listOf(id) + (q - id) else q }
    }

    fun delete(id: Long) {
        val e = _entries.value[id] ?: return
        if (e.status is DownloadStatus.Running) return
        itemDir(id).deleteRecursively()
        _entries.update { it - id }
        saveIndex()
    }

    fun hasActiveWork(): Boolean =
        _entries.value.values.any { it.status is DownloadStatus.Queued || it.status is DownloadStatus.Running }

    private fun setStatus(id: Long, status: DownloadStatus) {
        _entries.update { map ->
            val e = map[id] ?: return@update map
            map + (id to e.copy(status = status))
        }
    }

    /** The Steam connection this attempt was using went away; the attempt can be retried. */
    private class RetryableException(message: String) : IOException(message)

    private suspend fun runDownload(id: Long) {
        val dir = itemDir(id)
        try {
            var attempt = 0
            while (true) {
                attempt++
                setStatus(
                    id, DownloadStatus.Running(
                        0f, App.str(if (attempt == 1) R.string.dl_waiting_login else R.string.dl_reconnecting),
                    ),
                )
                SteamSession.awaitLoggedIn(120_000)
                dir.mkdirs()
                setStatus(id, DownloadStatus.Running(0f, App.str(R.string.dl_manifest)))
                try {
                    downloadOnce(id, dir)
                    break
                } catch (e: RetryableException) {
                    Log.w(TAG, "download $id attempt $attempt: ${e.message}")
                    if (attempt >= MAX_ATTEMPTS) throw IOException(e.message)
                }
            }

            if (contentFiles(id).isEmpty()) {
                throw IOException(App.str(R.string.dl_no_files))
            }

            setStatus(id, DownloadStatus.Running(1f, App.str(R.string.dl_exporting, "Download/$PUBLIC_DIR")))
            val exported = try {
                exportToPublic(id)
            } catch (t: Throwable) {
                Log.w(TAG, "export failed", t)
                null
            }
            setStatus(id, DownloadStatus.Done(exported))
            synchronized(previousStatus) { previousStatus.remove(id) }
        } catch (e: CancellationException) {
            Log.i(TAG, "download $id cancelled")
            // Partially written files are not usable, even for a re-download of a finished item.
            synchronized(previousStatus) { previousStatus.remove(id) }
            itemDir(id).deleteRecursively()
            _entries.update { it - id }
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "download $id failed", t)
            setStatus(id, DownloadStatus.Failed(t.message ?: t.javaClass.simpleName))
        } finally {
            saveIndex()
        }
    }

    private const val MAX_ATTEMPTS = 3
    private const val STALL_DUMP_MS = 45_000L
    private const val STALL_ABORT_MS = 120_000L

    /**
     * One DepotDownloader run. Its pending Steam requests never complete if the CM connection
     * drops, so we watch the session and abort (retryably) on disconnect or a long stall.
     */
    private suspend fun downloadOnce(id: Long, dir: File) {
        val session = SteamSession.sessionId

        check(fileTypePatched) { "Could not patch JavaSteam's workshop file types" }

        val lastProgress = AtomicLong(SystemClock.elapsedRealtime())
        var failure: Throwable? = null

        DepotDownloader(
            SteamSession.client,
            SteamSession.licenses,
            true, // debug: route DepotDownloader's own log through LogManager → logcat
            false, // useLanCache
            AppSettings.chunkThreads.value, // maxDownloads: parallel chunk connections
            2, // maxFileWrites
        ).use { dd ->
            dd.addListener(object : IDownloadListener {
                override fun onDownloadFailed(item: DownloadItem, error: Throwable) {
                    failure = error
                }

                override fun onStatusUpdate(message: String) {
                    lastProgress.set(SystemClock.elapsedRealtime())
                    val p = (_entries.value[id]?.status as? DownloadStatus.Running)?.progress ?: 0f
                    setStatus(id, DownloadStatus.Running(p, message.substringAfterLast('/').take(60)))
                }

                override fun onFileCompleted(depotId: Int, fileName: String, depotPercentComplete: Float) {
                    lastProgress.set(SystemClock.elapsedRealtime())
                    setStatus(id, DownloadStatus.Running(depotPercentComplete, fileName.substringAfterLast('/')))
                }

                override fun onChunkCompleted(
                    depotId: Int,
                    depotPercentComplete: Float,
                    compressedBytes: Long,
                    uncompressedBytes: Long,
                ) {
                    lastProgress.set(SystemClock.elapsedRealtime())
                    setStatus(id, DownloadStatus.Running(depotPercentComplete, App.str(R.string.dl_downloading)))
                }
            })
            dd.add(PubFileItem(appId = SteamSession.WE_APP_ID, pubFile = id, installDirectory = dir.absolutePath))
            dd.finishAdding()

            coroutineScope {
                val done = async { dd.getCompletion().await() }
                var dumped = false
                while (!done.isCompleted) {
                    delay(1000)
                    if (SteamSession.sessionId != session || SteamSession.state.value !is SteamSession.State.LoggedIn) {
                        done.cancel()
                        throw RetryableException(App.str(R.string.dl_disconnected))
                    }
                    val idle = SystemClock.elapsedRealtime() - lastProgress.get()
                    if (idle > STALL_DUMP_MS && !dumped) {
                        dumped = true
                        dumpThreads(id)
                    }
                    if (idle > STALL_ABORT_MS) {
                        done.cancel()
                        throw RetryableException(App.str(R.string.dl_stalled))
                    }
                }
                done.await()
            }
        }
        failure?.let { throw it }
    }

    /**
     * JavaSteam bug workaround: workshop file type 0 is looked up as EWorkshopFileType.First
     * (an alias of Community), which is missing from DepotDownloader's supported set, so every
     * Wallpaper Engine item is skipped as "unsupported" and the download never completes.
     * The set is a mutable LinkedHashSet at runtime, so add First to it.
     */
    private val fileTypePatched: Boolean by lazy {
        try {
            val field = DepotDownloader::class.java.getDeclaredField("SupportedWorkshopFileTypes")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(null) as MutableSet<EWorkshopFileType>).add(EWorkshopFileType.First)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "failed to patch SupportedWorkshopFileTypes", t)
            false
        }
    }

    /** Logs where the download threads are blocked, to diagnose stalls. */
    private fun dumpThreads(id: Long) {
        Log.w(TAG, "download $id: no progress for ${STALL_DUMP_MS / 1000}s, thread dump follows")
        for ((t, stack) in Thread.getAllStackTraces()) {
            val interesting = stack.any { f ->
                f.className.startsWith("in.dragonbra") || f.className.startsWith("io.ktor") ||
                    f.className.startsWith("okhttp3") || f.className.startsWith("com.weworkshop")
            }
            if (!interesting) continue
            Log.w(TAG, "--- ${t.name} (${t.state})\n" + stack.take(40).joinToString("\n") { "    at $it" })
        }
    }

    /** Copies the downloaded files into Download/WallpaperEngine/<id>/ via MediaStore. */
    private suspend fun exportToPublic(id: Long): String = withContext(Dispatchers.IO) {
        val base = itemDir(id)
        val resolver = appContext.contentResolver
        val relBase = "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_DIR/$id"

        // Remove a previous export of this item so re-downloads don't create "file (1)" copies.
        resolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$relBase/%"),
        )

        for (f in contentFiles(id)) {
            val rel = f.relativeTo(base).parent?.replace(File.separatorChar, '/')
            val ext = f.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (rel.isNullOrEmpty()) "$relBase/" else "$relBase/$rel/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException(App.str(R.string.dl_cannot_create, f.name))
            resolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        "Download/$PUBLIC_DIR/$id"
    }

    // region scanning Download/WallpaperEngine

    val publicDir: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PUBLIC_DIR)

    /** Whether we may list other apps' / a previous install's files in [publicDir]. */
    fun canScanPublic(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /**
     * Adds every Download/WallpaperEngine/<workshop id>/ folder that isn't already a usable
     * download (exported earlier, copied from a PC's workshop/content/431960, or left over from
     * a reinstall) as a finished entry whose files are read in place. Returns how many were added.
     */
    suspend fun scanPublic(): Int = withContext(Dispatchers.IO) {
        val dirs = publicDir.listFiles()?.filter { it.isDirectory && it.name.toLongOrNull() != null }
            ?: return@withContext 0
        var added = 0
        for (dir in dirs) {
            val id = dir.name.toLong()
            val cur = _entries.value[id]
            when (cur?.status) {
                null, is DownloadStatus.Failed -> {}
                is DownloadStatus.Done -> if (contentFiles(id).isNotEmpty()) continue
                else -> continue // queued or downloading
            }
            val files = dir.walkTopDown().onEnter { it.name != DepotDownloader.CONFIG_DIR }.filter { it.isFile }.toList()
            if (files.isEmpty()) continue
            val meta = runCatching { JSONObject(File(dir, "project.json").readText()) }.getOrNull()
            val type = meta?.optString("type")?.trim()?.takeIf { it.isNotEmpty() }
                ?.lowercase()?.replaceFirstChar { it.uppercase() }
                ?: when {
                    files.any { it.extension.lowercase() in VIDEO_EXTENSIONS } -> "Video"
                    files.any { it.name.equals("scene.pkg", true) } -> "Scene"
                    else -> cur?.type
                }
            val preview = meta?.optString("preview")?.takeIf { it.isNotBlank() }?.let { File(dir, it) }?.takeIf { it.isFile }
                ?: files.firstOrNull { it.nameWithoutExtension.equals("preview", true) }
            val entry = DownloadEntry(
                id = id,
                title = meta?.optString("title")?.trim()?.takeIf { it.isNotEmpty() } ?: cur?.title ?: id.toString(),
                previewUrl = preview?.let { Uri.fromFile(it).toString() } ?: cur?.previewUrl.orEmpty(),
                type = type,
                fileSize = files.sumOf { it.length() },
                status = DownloadStatus.Done("Download/$PUBLIC_DIR/$id"),
                localDir = dir.absolutePath,
            )
            _entries.update { it + (id to entry) }
            added++
        }
        if (added > 0) saveIndex()
        added
    }

    private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "m4v")

    // endregion

    // region persistence

    private fun loadIndex() {
        try {
            if (!indexFile.exists()) return
            val arr = JSONArray(indexFile.readText())
            val map = mutableMapOf<Long, DownloadEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getLong("id")
                val status = when (o.optString("status")) {
                    "done" -> DownloadStatus.Done(o.optString("exported").ifEmpty { null })
                    else -> DownloadStatus.Failed(o.optString("error").ifEmpty { App.str(R.string.dl_interrupted) })
                }
                map[id] = DownloadEntry(
                    id, o.getString("title"), o.optString("preview"),
                    o.optString("type").ifEmpty { null }, o.optLong("size"), status,
                    o.optString("dir").ifEmpty { null },
                )
            }
            _entries.value = map
        } catch (t: Throwable) {
            Log.w(TAG, "failed to load index", t)
        }
    }

    @Synchronized
    private fun saveIndex() {
        try {
            val arr = JSONArray()
            for (e in _entries.value.values) {
                val o = JSONObject()
                    .put("id", e.id).put("title", e.title).put("preview", e.previewUrl)
                    .put("type", e.type ?: "").put("size", e.fileSize)
                if (e.localDir != null) o.put("dir", e.localDir)
                when (val s = e.status) {
                    is DownloadStatus.Done -> o.put("status", "done").put("exported", s.exportedTo ?: "")
                    is DownloadStatus.Failed -> o.put("status", "failed").put("error", s.message)
                    else -> o.put("status", "failed").put("error", App.str(R.string.dl_interrupted))
                }
                arr.put(o)
            }
            indexFile.writeText(arr.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "failed to save index", t)
        }
    }

    // endregion
}
