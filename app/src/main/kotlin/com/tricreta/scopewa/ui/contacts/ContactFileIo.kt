package com.tricreta.scopewa.ui.contacts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * The thin Android layer around picking and writing files. Everything that
 * decides *what* the bytes mean lives in
 * `data/repository/contacts/` so it can be unit tested; this file only moves
 * bytes through the Storage Access Framework.
 *
 * SAF means no storage permission in the manifest — the user picks the exact
 * file, which is the right trade for an app already asking for Accessibility.
 */
internal object ContactFileIo {

    fun readText(context: Context, uri: Uri): Result<String> = runCatching {
        context.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "Couldn't open that file." }
            stream.readBytes().toString(Charsets.UTF_8)
        }
    }

    fun writeText(context: Context, uri: Uri, text: String): Result<Unit> = runCatching {
        context.contentResolver.openOutputStream(uri).use { stream ->
            requireNotNull(stream) { "Couldn't write to that file." }
            stream.write(text.toByteArray(Charsets.UTF_8))
        }
    }

    /** Falls back to the URI's last path segment when the provider has no display name. */
    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
            }
        return uri.lastPathSegment.orEmpty()
    }
}
