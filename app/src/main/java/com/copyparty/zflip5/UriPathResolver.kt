package com.copyparty.zflip5

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Resolve a SAF tree URI to a real filesystem path when possible.
 * Required because upstream copyparty opens OS paths (not content://).
 */
object UriPathResolver {

    fun resolve(context: Context, uri: Uri): String? {
        // Already a file:// URI
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }
        if (!DocumentsContract.isTreeUri(uri) && !DocumentsContract.isDocumentUri(context, uri)) {
            return uri.path
        }

        val docId = try {
            if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.getTreeDocumentId(uri)
            } else {
                DocumentsContract.getDocumentId(uri)
            }
        } catch (_: Exception) {
            return null
        }

        // primary:Download  /  primary:
        if (docId.startsWith("primary:")) {
            val rel = docId.removePrefix("primary:")
            val base = Environment.getExternalStorageDirectory()
            val f = if (rel.isEmpty()) base else File(base, rel)
            return if (f.exists()) f.absolutePath else f.absolutePath
        }

        // <uuid>:path  → /storage/<uuid>/path  (SD card)
        val colon = docId.indexOf(':')
        if (colon > 0) {
            val volume = docId.substring(0, colon)
            val rel = docId.substring(colon + 1)
            if (volume != "primary") {
                val f = if (rel.isEmpty()) {
                    File("/storage/$volume")
                } else {
                    File("/storage/$volume", rel)
                }
                if (f.exists()) return f.absolutePath
            }
        }

        // Last resort: DocumentFile name won't give path — fail closed
        return null
    }
}
