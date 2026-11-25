package com.weworkshop.app.scene

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Copies a user-picked Wallpaper Engine `assets` folder (via the system folder picker) into the app's private
 * assets dir. Uses DocumentsContract directly so no extra dependency is needed; files are streamed, never held in memory.
 */
object SceneAssetsImporter {
    sealed interface State {
        data object Idle : State
        data class Running(val done: Int) : State
        data class Done(val count: Int) : State
        data class Failed(val msg: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private class Node(val docId: String, val name: String, val mime: String?) {
        val isDir get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    fun import(ctx: Context, tree: Uri) {
        if (_state.value is State.Running) return
        val app = ctx.applicationContext
        _state.value = State.Running(0)
        scope.launch {
            try {
                val dest = SceneWallpaper.assetsDir(app)
                var root = DocumentsContract.getTreeDocumentId(tree)
                val rootName = DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/')
                val top = children(app, tree, root)
                // Descend into assets/ when the picked folder is its parent; a folder named assets is imported as is.
                if (top.none { it.isDir && it.name == "shaders" } && !rootName.equals("assets", true)) {
                    top.firstOrNull { it.isDir && it.name == "assets" }?.let { root = it.docId }
                }
                var count = 0
                fun walk(docId: String, out: File) {
                    out.mkdirs()
                    for (n in children(app, tree, docId)) {
                        val name = n.name.replace('/', '_')
                        if (name == ".." || name == ".") continue
                        if (n.isDir) walk(n.docId, File(out, name)) else {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, n.docId)
                            val target = File(out, name)
                            app.contentResolver.openInputStream(uri)?.use { input ->
                                target.outputStream().use { input.copyTo(it, 64 * 1024) }
                            }
                            count++
                            if (count % 50 == 0) _state.value = State.Running(count)
                        }
                    }
                }
                walk(root, dest)
                File(app.filesDir, "scene_status.txt").appendText("assets import: $count files -> $dest" + System.lineSeparator())
                _state.value = State.Done(count)
            } catch (t: Throwable) {
                _state.value = State.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun children(ctx: Context, tree: Uri, parentDocId: String): List<Node> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val out = ArrayList<Node>()
        ctx.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) out.add(Node(c.getString(0), c.getString(1) ?: continue, c.getString(2)))
        }
        return out
    }
}
