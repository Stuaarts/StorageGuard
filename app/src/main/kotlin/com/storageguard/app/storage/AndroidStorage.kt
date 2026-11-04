package com.storageguard.app.storage

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.storageguard.core.Asset
import com.storageguard.core.Source
import com.storageguard.core.StorageAccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class Coverage(val images: Boolean, val videos: Boolean, val partial: Boolean) {
    val label: String get() = when {
        images && videos -> "Photos & videos granted"
        partial -> "Selected media only"
        images -> "Photos granted · videos excluded"
        videos -> "Videos granted · photos excluded"
        else -> "Media access not granted"
    }
}

class AndroidStorage(private val context: Context) : StorageAccess {
    private val resolver = context.contentResolver
    fun coverage(): Coverage {
        fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= 33) Coverage(
            granted(Manifest.permission.READ_MEDIA_IMAGES), granted(Manifest.permission.READ_MEDIA_VIDEO),
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
        ) else granted(Manifest.permission.READ_EXTERNAL_STORAGE).let { Coverage(it, it, false) }
    }

    suspend fun discover(sources: Set<String>, issue: (String) -> Unit, emit: suspend (Asset) -> Unit) {
        val access = coverage()
        for (volume in MediaStore.getExternalVolumeNames(context)) {
            val collections = buildList {
                if (access.images || access.partial) add(MediaStore.Images.Media.getContentUri(volume))
                if (access.videos || access.partial) add(MediaStore.Video.Media.getContentUri(volume))
            }
            for (collection in collections) {
                try {
                    resolver.query(collection, MEDIA_COLUMNS, null, null, "${MediaStore.MediaColumns._ID} ASC")?.use { cursor ->
                        while (cursor.moveToNext()) {
                            currentCoroutineContext().ensureActive()
                            val uri = ContentUris.withAppendedId(collection, cursor.getLong(cursor.getColumnIndexOrThrow("_id")))
                            emit(mediaAsset(uri, cursor))
                        }
                    } ?: issue("A media collection could not be queried.")
                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (_: Exception) { issue("A media collection became unavailable or access was revoked.") }
            }
        }
        for (source in sources) {
            currentCoroutineContext().ensureActive()
            val uri = Uri.parse(source.substringAfter('|'))
            try {
                if (source.startsWith("T|")) discoverTree(uri, issue, emit)
                else documentAsset(uri)?.let { emit(it) } ?: issue("A selected file is no longer available.")
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { issue("A selected location is unavailable. Re-select it in Settings.") }
        }
    }

    private suspend fun discoverTree(tree: Uri, issue: (String) -> Unit, emit: suspend (Asset) -> Unit) {
        val pending = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        pending.add(DocumentsContract.getTreeDocumentId(tree))
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val parent = pending.removeFirst()
            if (!visited.add(parent)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            try {
                resolver.query(children, DOCUMENT_COLUMNS, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val id = cursor.string(DocumentsContract.Document.COLUMN_DOCUMENT_ID) ?: continue
                        if (cursor.string(DocumentsContract.Document.COLUMN_MIME_TYPE) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending.add(id)
                        } else {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            emit(documentFromCursor(uri, cursor))
                        }
                    }
                } ?: issue("A folder could not be read.")
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { issue("Part of a selected folder could not be read.") }
        }
    }

    override fun open(asset: Asset) = resolver.openInputStream(Uri.parse(asset.uri)) ?: error("File is unavailable.")

    override fun refresh(asset: Asset): Asset? = try {
        val uri = Uri.parse(asset.uri)
        if (asset.source == Source.MEDIA) {
            resolver.query(uri, MEDIA_COLUMNS + MediaStore.MediaColumns.IS_TRASHED, trashQuery(), null)?.use {
                if (!it.moveToFirst() || it.number(MediaStore.MediaColumns.IS_TRASHED) == 1L) null else mediaAsset(uri, it)
            }
        } else documentAsset(uri)
    } catch (_: Exception) { null }

    fun isTrashed(uriString: String): Boolean? = try {
        resolver.query(Uri.parse(uriString), arrayOf(MediaStore.MediaColumns.IS_TRASHED), trashQuery(), null)?.use {
            if (!it.moveToFirst()) null else it.number(MediaStore.MediaColumns.IS_TRASHED) == 1L
        }
    } catch (_: Exception) { null }

    fun documentExists(uriString: String): Boolean? {
        val uri = Uri.parse(uriString)
        val direct = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() }
        } catch (_: java.io.FileNotFoundException) { false }
        catch (_: Exception) { null }
        if (direct != null) return direct
        // ExternalStorageProvider can reject a deleted document URI rather than returning an
        // empty cursor. Verify absence through its still-readable, fully granted local parent.
        // An inaccessible parent or an opaque provider is never interpreted as deletion.
        if (uri.authority != "com.android.externalstorage.documents" || !DocumentsContract.isTreeUri(uri)) return null
        return try {
            val id = DocumentsContract.getDocumentId(uri)
            val parentId = id.substringBeforeLast('/', id.substringBefore(':') + ":")
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, parentId)
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
                var found = false
                while (cursor.moveToNext()) if (cursor.string(DocumentsContract.Document.COLUMN_DOCUMENT_ID) == id) found = true
                found
            }
        } catch (_: Exception) { null }
    }

    fun deleteDocument(asset: Asset): Boolean = DocumentsContract.deleteDocument(resolver, Uri.parse(asset.uri))

    private fun mediaAsset(uri: Uri, cursor: Cursor): Asset {
        val name = cursor.string(MediaStore.MediaColumns.DISPLAY_NAME) ?: "Unnamed media"
        val relative = cursor.string(MediaStore.MediaColumns.RELATIVE_PATH)
        val volume = cursor.string(MediaStore.MediaColumns.VOLUME_NAME)
        val id = cursor.number(MediaStore.MediaColumns._ID)
        val identity = if (relative != null && volume != null) "local:$volume:$relative$name"
            else if (volume != null && id != null) "media:$volume:$id" else null
        return Asset(uri.toString(), identity, name, cursor.string(MediaStore.MediaColumns.MIME_TYPE) ?: "application/octet-stream",
            cursor.number(MediaStore.MediaColumns.SIZE), cursor.number(MediaStore.MediaColumns.DATE_MODIFIED),
            relative ?: "MediaStore · $volume", Source.MEDIA, identity != null)
    }

    private fun documentAsset(uri: Uri): Asset? {
        val isDocument = DocumentsContract.isDocumentUri(context, uri)
        val columns = if (isDocument) DOCUMENT_COLUMNS else arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return resolver.query(uri, columns, null, null, null)?.use {
            if (!it.moveToFirst()) null
            else if (isDocument) documentFromCursor(uri, it)
            else Asset(uri.toString(), null, it.string(OpenableColumns.DISPLAY_NAME) ?: "Selected media",
                resolver.getType(uri) ?: "application/octet-stream", it.number(OpenableColumns.SIZE), null,
                "Selected media · ${uri.authority}", Source.DOCUMENT, false)
        }
    }

    private fun documentFromCursor(uri: Uri, cursor: Cursor): Asset {
        val id = cursor.string(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        // Only local ExternalStorageProvider document IDs establish independent filesystem assets.
        // Generic/cloud provider IDs may alias the same content and are deliberately review-only.
        val local = uri.authority == "com.android.externalstorage.documents" && id?.contains(':') == true
        val volume = id?.substringBefore(':')?.let { if (it == "primary") "external_primary" else it.lowercase() }
        val path = id?.substringAfter(':')
        val identity = if (local && !path.isNullOrBlank() && path.split('/').none { it == ".." || it == "." })
            "local:$volume:$path" else null
        val flags = cursor.number(DocumentsContract.Document.COLUMN_FLAGS) ?: 0
        val canWrite = context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
        val canDelete = flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE.toLong() != 0L
        return Asset(uri.toString(), identity,
            cursor.string(DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: "Unnamed document",
            cursor.string(DocumentsContract.Document.COLUMN_MIME_TYPE) ?: "application/octet-stream",
            cursor.number(DocumentsContract.Document.COLUMN_SIZE), cursor.number(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            if (local) path?.substringBeforeLast('/', "Selected documents") ?: "Selected documents" else "Provider · ${uri.authority}",
            Source.DOCUMENT, identity != null && canWrite && canDelete)
    }

    companion object {
        private val MEDIA_COLUMNS = arrayOf("_id", "_display_name", "mime_type", "_size", "date_modified", "relative_path", "volume_name")
        private val DOCUMENT_COLUMNS = arrayOf("document_id", "_display_name", "mime_type", "_size", "last_modified", "flags")
        private fun trashQuery() = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
    }
}

private fun Cursor.string(column: String): String? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }
private fun Cursor.number(column: String): Long? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) }
