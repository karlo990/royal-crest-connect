package zw.royalcrest.connect.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import zw.royalcrest.connect.data.Connection
import zw.royalcrest.connect.data.friendly
import java.io.File

/** Downloads a server PDF (with the access key) into the app's cache. */
private suspend fun fetchPdf(context: Context, conn: Connection, path: String): File = withContext(Dispatchers.IO) {
    val url = conn.absolute(path) ?: error("No PDF yet.")
    val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "document.pdf" }
    val dir = File(context.cacheDir, "pdfs").apply { mkdirs() }
    val file = File(dir, name)
    conn.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) error("Couldn't download the PDF (${r.code}).")
        file.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
    }
    file
}

private fun uriFor(context: Context, file: File) =
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)

suspend fun openPdf(context: Context, conn: Connection, path: String) {
    try {
        val file = fetchPdf(context, conn, path)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(context, file), "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(intent) }
        catch (_: ActivityNotFoundException) { sharePdfFile(context, file) }
    } catch (e: Exception) {
        Toast.makeText(context, e.friendly(), Toast.LENGTH_LONG).show()
    }
}

suspend fun sharePdf(context: Context, conn: Connection, path: String) {
    try { sharePdfFile(context, fetchPdf(context, conn, path)) }
    catch (e: Exception) { Toast.makeText(context, e.friendly(), Toast.LENGTH_LONG).show() }
}

private fun sharePdfFile(context: Context, file: File) {
    val send = Intent(Intent.ACTION_SEND).setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
