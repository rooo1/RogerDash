package app.roger.dash

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands a file we made (ride picture, GPX) to another app through the share sheet.
 * A minimal stand-in for androidx FileProvider: serves only files in cache/share, read-only,
 * to apps we explicitly grant a URI to.
 */
class ShareProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileFor(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val dir = Share.dir(ctx)
        val f = File(dir, uri.lastPathSegment ?: throw FileNotFoundException())
        if (f.parentFile?.canonicalPath != dir.canonicalPath || !f.exists()) throw FileNotFoundException(uri.toString())
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, sel: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = fileFor(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else if (it == OpenableColumns.DISPLAY_NAME) f.name else null })
        }
    }

    override fun getType(uri: Uri) = Share.mime(uri.lastPathSegment ?: "")
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

object Share {
    private const val AUTHORITY = "app.roger.dash.share"

    fun dir(ctx: Context) = File(ctx.cacheDir, "share").apply { mkdirs() }

    fun mime(name: String) = when {
        name.endsWith(".png") -> "image/png"
        name.endsWith(".gpx") -> "application/gpx+xml"
        else -> "application/octet-stream"
    }

    /** A fresh file in the share folder (old shares are cleared first; they're only temporary copies). */
    fun newFile(ctx: Context, name: String): File {
        dir(ctx).listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() }
        return File(dir(ctx), name)
    }

    /** Open the share sheet for [f]. */
    fun send(a: Activity, f: File, title: String, text: String? = null) {
        val uri = Uri.parse("content://$AUTHORITY/${Uri.encode(f.name)}")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime(f.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(f.name, uri)
        }
        a.startActivity(Intent.createChooser(send, title))
    }

    /** Safe file name from a ride name: "Saturday morning ride" → "saturday-morning-ride". */
    fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "ride" }.take(40)
}
