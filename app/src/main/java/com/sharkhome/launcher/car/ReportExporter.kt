package com.sharkhome.launcher.car

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportExporter {

    /**
     * Saves the report twice: to Downloads (for a USB stick / file manager) and to the app's
     * own folder, so `adb pull /sdcard/Android/data/com.sharkhome.launcher/files/probe-report.txt` works.
     * Returns a short description of where it went.
     */
    fun save(ctx: Context, text: String): String {
        val places = ArrayList<String>()
        ctx.getExternalFilesDir(null)?.let { dir ->
            runCatching { File(dir, "probe-report.txt").writeText(text) }.onSuccess { places.add("app folder") }
        }
        runCatching {
            val name = "shark-launcher-probe-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Downloads unavailable")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            places.add("Downloads/$name")
        }
        return if (places.isEmpty()) "Couldn't save the report" else "Saved to ${places.joinToString(" and ")}"
    }

    fun share(ctx: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(send, "Share probe report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
