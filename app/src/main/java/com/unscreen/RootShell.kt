package com.unscreen

import java.io.OutputStream

/** يدير صدفة su دائمة لتنفيذ الأوامر بسرعة دون إعادة فتح su في كل مرة. */
object RootShell {
    private var proc: Process? = null
    private var out: OutputStream? = null

    /** يتحقق من توفر صلاحية الروت (قد يظهر طلب منح الصلاحية أول مرة). */
    fun checkRoot(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val txt = p.inputStream.bufferedReader().readText()
        p.waitFor()
        txt.contains("uid=0")
    } catch (e: Exception) {
        false
    }

    @Synchronized
    fun exec(cmd: String): Boolean = try {
        if (!alive()) open()
        out!!.write((cmd + "\n").toByteArray())
        out!!.flush()
        true
    } catch (e: Exception) {
        close()
        false
    }

    @Synchronized
    fun close() {
        try {
            out?.write("exit\n".toByteArray())
            out?.flush()
        } catch (_: Exception) {
        }
        try {
            proc?.destroy()
        } catch (_: Exception) {
        }
        proc = null
        out = null
    }

    private fun alive(): Boolean {
        val p = proc ?: return false
        return try {
            p.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }

    private fun open() {
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        proc = p
        out = p.outputStream
        // تفريغ المخرجات حتى لا تمتلئ الذاكرة المؤقتة
        Thread {
            try {
                val buf = ByteArray(1024)
                while (p.inputStream.read(buf) >= 0) { /* تجاهل */ }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()
    }
}
