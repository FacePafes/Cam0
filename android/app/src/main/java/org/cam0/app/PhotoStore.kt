// SPDX-License-Identifier: GPL-3.0-only
package org.cam0.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** One saved photo, as seen through the chosen SAF folder. */
data class PhotoEntry(val uri: Uri, val displayName: String, val lastModified: Long)

/**
 * Owns the user's chosen save folder a Storage Access Framework tree
 * Uri, granted once via ACTION_OPEN_DOCUMENT_TREE and persisted across
 * app restarts and device reboots with a persistable permission grant :0
 * as an app shouldnt request stuff it doesnt need!
 * Cam0 only ever gets access to the one folder the user
 * picked, not broad storage access, and no runtime storage permission
 * is requested at all.
 *
 * Every save after the initial folder grant writes directly into that
 * folder with no further picker dialogs.
 *
 * Deliberately built on the plain framework `DocumentsContract` API
 * (available since API 21) rather than the androidx `DocumentFile`
 * wrapper same capability, no extra dependency gained.
 */
object PhotoStore {

    private const val PREFS_NAME = "cam0_photo_store"
    private const val KEY_TREE_URI = "tree_uri"
    private const val MIME_JPEG = "image/jpeg"

    fun savedTreeUri(context: Context): Uri? {
        val raw = prefs(context).getString(KEY_TREE_URI, null) ?: return null
        val uri = Uri.parse(raw)
        // The grant can be revoked out from under cam (user changes it in
        // Settings), so verify it's still valid rather than trusting the
        // saved string forever.
        val stillGranted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        return if (stillGranted) uri else null
    }

    /** Call with the Uri returned from an ACTION_OPEN_DOCUMENT_TREE picker result. */
    fun persist(context: Context, treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs(context).edit().putString(KEY_TREE_URI, treeUri.toString()).apply()
    }

    /** Creates a new empty JPEG file in the chosen folder and returns its Uri, or null on failure. */
    fun createJpegFile(context: Context, fileName: String): Uri? {
        val treeUri = savedTreeUri(context) ?: return null
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        return try {
            DocumentsContract.createDocument(context.contentResolver, parentDocUri, MIME_JPEG, fileName)
        } catch (e: Exception) {
            null
        }
    }

    /** Photos previously saved into the chosen folder, newest first. */
    fun listPhotos(context: Context): List<PhotoEntry> {
        val treeUri = savedTreeUri(context) ?: return emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val results = mutableListOf<PhotoEntry>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val mime = cursor.getString(mimeIdx) ?: continue
                if (!mime.startsWith("image/")) continue
                val docId = cursor.getString(idIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: docId
                val lastModified = if (!cursor.isNull(modIdx)) cursor.getLong(modIdx) else 0L
                results.add(PhotoEntry(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId), name, lastModified))
            }
        }
        return results.sortedByDescending { it.lastModified }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
