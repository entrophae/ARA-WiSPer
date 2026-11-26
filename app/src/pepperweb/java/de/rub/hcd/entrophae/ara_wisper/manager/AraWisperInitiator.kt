package de.rub.hcd.entrophae.ara_wisper.manager

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Zero-touch startup initializer registered in the `pepperweb` flavor manifest.
 *
 * Automatically bootstraps [AraWisperManager] when the Android process launches so
 * activity lifecycle hooks, security providers, and speech services are ready before
 * `ChatActivity` is created—without modifying upstream `src/main` classes.
 */
class AraWisperInitiator : ContentProvider() {

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return false
        AraWisperManager.init(app)
        return true
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}