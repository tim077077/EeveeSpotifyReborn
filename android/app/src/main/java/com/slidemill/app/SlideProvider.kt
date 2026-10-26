package com.slidemill.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Read-only access to rendered slides for the TikTok app (granted per share; nothing else is exposed). */
class SlideProvider : ContentProvider() {
    companion object {
        fun authority(context: Context) = "${context.packageName}.slides"

        private fun root(context: Context) = File(context.filesDir, "slidemill/media/slideshows")

        fun uriFor(context: Context, file: File): Uri {
            val rel = file.canonicalFile.relativeTo(root(context).canonicalFile).path
            return Uri.Builder().scheme("content").authority(authority(context)).path(rel).build()
        }
    }

    private fun fileFor(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val base = root(ctx).canonicalFile
        val f = File(base, uri.path.orEmpty().trimStart('/')).canonicalFile
        if (!f.path.startsWith(base.path + File.separator) || !f.isFile) throw FileNotFoundException(uri.toString())
        return f
    }

    override fun onCreate() = true

    override fun getType(uri: Uri): String = if (uri.path.orEmpty().endsWith(".mp4")) "video/mp4" else "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileFor(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            addRow(cols.map { if (it == OpenableColumns.DISPLAY_NAME) "${f.parentFile?.name}_${f.name}" else if (it == OpenableColumns.SIZE) f.length() else null }.toTypedArray())
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
