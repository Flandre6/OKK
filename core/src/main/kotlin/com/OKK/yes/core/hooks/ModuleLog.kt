package com.OKK.yes.core.hooks

import android.util.Log
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模块运行日志（诊断页「日志区」）。
 * 内存环形缓冲 + 可选落盘。
 */
object ModuleLog {
    private const val TAG = "OKK-Log"
    private const val MAX_LINES = 400
    private const val PUBLIC_DIR = "/storage/emulated/0/Android/media/com.tencent.mm/OKK"
    private const val LOG_FILE = "module_runtime.log"

    private val lines = CopyOnWriteArrayList<String>()
    private val enabled = AtomicBoolean(true)
    private val fmt = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    fun isEnabled(): Boolean = enabled.get()

    fun setEnabled(on: Boolean) {
        enabled.set(on)
        i("日志记录已${if (on) "开启" else "关闭"}")
        PublicConfigStore.putBoolean("module_log_enabled", on, async = true)
    }

    fun loadEnabledFromConfig() {
        enabled.set(PublicConfigStore.getBoolean("module_log_enabled", false))
    }

    fun clear() {
        lines.clear()
        runCatching { File(PUBLIC_DIR, LOG_FILE).writeText("") }
        i("日志已清空")
    }

    fun i(msg: String) = append("I", msg)
    fun w(msg: String) = append("W", msg)
    fun e(msg: String) = append("E", msg)

    fun d(msg: String) = append("D", msg)

    private fun append(level: String, msg: String) {
        if (!enabled.get() && !msg.contains("日志记录")) return
        val ts = fmt.get()!!.format(Date())
        val line = "${level.padEnd(1)}  $ts  $msg"
        lines.add(0, line) // 最新在上
        while (lines.size > MAX_LINES) {
            lines.removeAt(lines.size - 1)
        }
        runCatching { Log.i(TAG, msg) }
        runCatching { XposedBridge.log("[$TAG] $msg") }
        // 异步追加文件（不阻塞）
        runCatching {
            val f = File(PUBLIC_DIR, LOG_FILE)
            f.parentFile?.mkdirs()
            f.appendText(line + "\n", Charsets.UTF_8)
        }
    }

    /** 最新在前，最多 [limit] 行 */
    fun snapshot(limit: Int = 200): List<String> {
        return lines.take(limit.coerceAtLeast(1))
    }

    fun text(limit: Int = 200): String {
        val snap = snapshot(limit)
        if (snap.isEmpty()) {
            // 尝试读文件尾
            val fileTail = runCatching {
                val f = File(PUBLIC_DIR, LOG_FILE)
                if (!f.isFile) return@runCatching emptyList()
                f.readLines(Charsets.UTF_8).takeLast(limit).asReversed()
            }.getOrDefault(emptyList())
            if (fileTail.isEmpty()) return "（暂无日志，打开开关后模块运行会写入）"
            return fileTail.joinToString("\n")
        }
        return snap.joinToString("\n")
    }

    fun bootstrap() {
        loadEnabledFromConfig()
        i("OKK 日志区已就绪 · 记录=${if (enabled.get()) "开" else "关"}")
    }
}
