package com.wuxianggujun.tinaide.core.lsp

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * 远程 LSP URI 映射器
 *
 * 目的：让远程 clangd 看到的是“远端工作区”的 file:// URI，而不是 Android 本地路径。
 */
class RemoteLspUriMapper {

    companion object {
        private const val TAG = "RemoteLspUriMapper"
    }

    @Volatile
    private var clientRootUri: String? = null

    @Volatile
    private var serverRootUri: String? = null

    fun setClientRootUri(uri: String?) {
        clientRootUri = uri?.let(::normalizeRootUri)
    }

    /**
     * @param uriOrPath 支持 `file:///...`/`file:/...` 或本地绝对路径（如 Windows `C:\\...`）
     */
    fun setServerRootUri(uriOrPath: String?) {
        serverRootUri = uriOrPath
            ?.takeIf { it.isNotBlank() }
            ?.let {
                if (it.startsWith("file:")) it else File(it).toURI().toString()
            }
            ?.let(::normalizeRootUri)
    }

    fun hasMapping(): Boolean = !clientRootUri.isNullOrBlank() && !serverRootUri.isNullOrBlank()

    fun rewriteClientToServer(jsonText: String): String = rewrite(jsonText, Direction.CLIENT_TO_SERVER)

    fun rewriteServerToClient(jsonText: String): String = rewrite(jsonText, Direction.SERVER_TO_CLIENT)

    private enum class Direction { CLIENT_TO_SERVER, SERVER_TO_CLIENT }

    private fun rewrite(jsonText: String, direction: Direction): String {
        val clientRoot = clientRootUri ?: return jsonText
        val serverRoot = serverRootUri ?: return jsonText
        if (!jsonText.contains("file:")) return jsonText

        return try {
            val obj = JSONObject(jsonText)
            rewriteJsonAny(obj, clientRoot, serverRoot, direction)
            obj.toString()
        } catch (t: Throwable) {
            Timber.tag(TAG).d(t, "Failed to rewrite URIs, keep original")
            jsonText
        }
    }

    private fun rewriteJsonAny(value: Any, clientRoot: String, serverRoot: String, direction: Direction) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    when (child) {
                        is String -> {
                            if (shouldRewriteKey(key) && child.startsWith("file:")) {
                                value.put(key, rewriteUri(child, clientRoot, serverRoot, direction))
                            }
                        }
                        is JSONObject, is JSONArray -> rewriteJsonAny(child, clientRoot, serverRoot, direction)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    val child = value.opt(i)
                    when (child) {
                        is String -> {
                            // 数组里常见的是 workspaceFolders，但我们没有 key；保守起见只处理 file://
                            if (child.startsWith("file:")) {
                                value.put(i, rewriteUri(child, clientRoot, serverRoot, direction))
                            }
                        }
                        is JSONObject, is JSONArray -> rewriteJsonAny(child, clientRoot, serverRoot, direction)
                    }
                }
            }
        }
    }

    private fun shouldRewriteKey(key: String): Boolean = key == "uri" || key.endsWith("Uri")

    private fun rewriteUri(uri: String, clientRoot: String, serverRoot: String, direction: Direction): String {
        val normalizedUri = normalizeFileUri(uri)
        val clientRootNormalized = normalizeFileUri(clientRoot)
        val serverRootNormalized = normalizeFileUri(serverRoot)

        val clientBase = clientRootNormalized.removeSuffix("/")
        val serverBase = serverRootNormalized.removeSuffix("/")

        return when (direction) {
            Direction.CLIENT_TO_SERVER -> {
                when {
                    normalizedUri == clientBase -> serverBase
                    normalizedUri.startsWith(clientRootNormalized) ->
                        serverBase + "/" +
                            normalizedUri.removePrefix(clientRootNormalized).trimStart('/')
                    else -> uri
                }
            }
            Direction.SERVER_TO_CLIENT -> {
                when {
                    normalizedUri == serverBase -> clientBase
                    normalizedUri.startsWith(serverRootNormalized) ->
                        clientBase + "/" +
                            normalizedUri.removePrefix(serverRootNormalized).trimStart('/')
                    else -> uri
                }
            }
        }
    }

    private fun normalizeRootUri(uri: String): String = normalizeFileUri(uri).trimEnd('/') + "/"

    private fun normalizeFileUri(uri: String): String = canonicalizeLspDocumentUri(uri)
}
