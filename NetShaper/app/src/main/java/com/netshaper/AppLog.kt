package com.netshaper

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Timestamped log: in-memory ring for the UI plus a file on the device (Android/data/com.netshaper/files/shaper.log). */
object AppLog {
    private var file: File? = null
    private val ring = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun init(ctx: Context) {
        if (file == null) {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            file = File(dir, "shaper.log")
        }
    }

    fun path(): String = file?.absolutePath ?: "(not initialised)"

    @Synchronized
    fun log(msg: String) {
        val line = "${fmt.format(Date())} $msg"
        ring.addLast(line)
        while (ring.size > 400) ring.removeFirst()
        try {
            val f = file
            if (f != null) {
                if (f.length() > 2_000_000L) f.renameTo(File(f.parentFile, "shaper.log.1"))
                f.appendText(line + "\n")
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun tail(n: Int = 150): String = ring.toList().takeLast(n).joinToString("\n")
}
