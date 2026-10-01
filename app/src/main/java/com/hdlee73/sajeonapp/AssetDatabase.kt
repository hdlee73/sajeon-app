package com.hdlee73.sajeonapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * Copies a bundled read-only SQLite asset to app storage and opens it.
 *
 * The copy goes to a temporary file and is renamed only after it completes, so an
 * interrupted first launch can no longer leave a truncated database that silently made
 * every local lookup fail. A file that cannot be opened is deleted and copied again once.
 * Older cache versions of the same asset are removed to free storage.
 */
internal object AssetDatabase {
    fun open(context: Context, asset: String, prefix: String, version: Int): SQLiteDatabase? {
        val file = context.getDatabasePath("${prefix}_v$version.sqlite")
        file.parentFile?.listFiles()?.forEach { old ->
            if (old.name.startsWith(prefix) && old.name != file.name) old.delete()
        }
        repeat(2) {
            try {
                if (!file.exists()) {
                    file.parentFile?.mkdirs()
                    val temporary = java.io.File(file.path + ".part")
                    context.assets.open(asset).use { input -> temporary.outputStream().use { input.copyTo(it) } }
                    if (!temporary.renameTo(file)) { temporary.delete(); return null }
                }
                val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
                return db
            } catch (_: Exception) {
                file.delete()
            }
        }
        return null
    }
}
