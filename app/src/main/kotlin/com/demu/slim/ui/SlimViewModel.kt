package com.demu.slim.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.demu.slim.core.GalleryOps
import com.demu.slim.core.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogLine(val text: String, val color: Int)

class SlimViewModel(private val context: Context) {

    var rootReady by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var stats by mutableStateOf(GalleryOps.Stats())
        private set
    var logs by mutableStateOf<List<LogLine>>(emptyList())
        private set
    var includeVideos by mutableStateOf(true)
    var includePhotos by mutableStateOf(false)

    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}"
    val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    var galleryVersion by mutableStateOf("读取中…")
        private set

    var details by mutableStateOf<List<GalleryOps.MediaRow>>(emptyList())
        private set
    var detailsTitle by mutableStateOf("")
        private set

    /** 包可见性可能被限制，读不到就退回 dumpsys 查 versionName。 */
    private fun resolveGalleryVersion(): String {
        runCatching {
            val info = context.packageManager.getPackageInfo("com.miui.gallery", 0)
            val name = info.versionName ?: "?"
            return "$name (${info.longVersionCode})"
        }
        val out = RootShell.run(
            "dumpsys package com.miui.gallery | grep -m1 versionName",
            20,
        ).out.trim()
        return if (out.isBlank()) "未安装 / 无法读取" else out.removePrefix("versionName=")
    }

    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    fun log(text: String, color: Int = BLUE) {
        val line = LogLine("[${stamp.format(Date())}] $text", color)
        logs = logs + line
    }

    fun clearLog() {
        logs = emptyList()
    }

    fun refreshRoot(scope: CoroutineScope) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { RootShell.hasRoot() }
            rootReady = ok
            galleryVersion = withContext(Dispatchers.IO) { resolveGalleryVersion() }
            if (ok) {
                log("root 已就绪 (uid=0)", GREEN)
            } else {
                log("root 获取失败：${RootShell.lastError}", RED)
            }
        }
    }

    fun scan(scope: CoroutineScope) {
        if (busy) return
        busy = true
        scope.launch {
            log("开始体检…")
            val result = withContext(Dispatchers.IO) {
                if (!rootReady) {
                    rootReady = RootShell.hasRoot()
                }
                if (rootReady) GalleryOps.scan(context) else GalleryOps.Stats(dbOk = false)
            }
            stats = result
            busy = false
            if (result.dbOk) {
                log(
                    "体检完成：本地视频 ${result.videoLocalCount} 个 " +
                        "${mb(result.videoLocalBytes)}，本地照片 ${result.photoLocalCount} 个 ${mb(result.photoLocalBytes)}",
                    GREEN,
                )
            } else {
                log("✗ 读不到 gallery.db：${GalleryOps.lastError}", RED)
            }
        }
    }

    fun loadDetails(scope: CoroutineScope, video: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            log("读取${if (video) "视频" else "照片"}明细…")
            val rows = withContext(Dispatchers.IO) { GalleryOps.mediaRows(context, video) }
            details = rows
            detailsTitle = "本地${if (video) "视频" else "照片"}明细（${rows.size} 项）"
            busy = false
            log(
                if (rows.isEmpty()) "· 没有占本地的条目" else "· ${rows.count { it.synced }} 项已同步、可瘦身",
                if (rows.isEmpty()) AMBER else GREEN,
            )
        }
    }

    fun slim(scope: CoroutineScope) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                GalleryOps.slim(context, includeVideos, includePhotos) { line ->
                    log(line, pickColor(line))
                }
            }
            withContext(Dispatchers.IO) { stats = GalleryOps.scan(context) }
            busy = false
            log(if (ok) "● 瘦身流程结束（可点“还原”回滚）" else "✗ 瘦身失败", if (ok) GREEN else RED)
        }
    }

    fun restore(scope: CoroutineScope) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                GalleryOps.restore(context) { line -> log(line, pickColor(line)) }
            }
            withContext(Dispatchers.IO) { stats = GalleryOps.scan(context) }
            busy = false
            log(if (ok) "● 已还原" else "✗ 还原失败", if (ok) GREEN else RED)
        }
    }

    fun purge(scope: CoroutineScope) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                GalleryOps.purge { line -> log(line, pickColor(line)) }
            }
            withContext(Dispatchers.IO) { stats = GalleryOps.scan(context) }
            busy = false
            log(if (ok) "● 暂存区已清空" else "✗ 清理失败", if (ok) GREEN else RED)
        }
    }

    private fun pickColor(line: String): Int = when {
        line.startsWith("✗") -> RED
        line.startsWith("●") -> GREEN
        line.startsWith("·") -> BLUE
        else -> AMBER
    }

    private fun mb(bytes: Long): String {
        return when {
            bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f GB", bytes.toDouble() / (1L shl 30))
            bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes.toDouble() / (1L shl 20))
            else -> "${bytes / 1024} KB"
        }
    }

    companion object {
        const val GREEN = 0xFF5FD68A.toInt()
        const val RED = 0xFFFF6B6B.toInt()
        const val AMBER = 0xFFFFC94D.toInt()
        const val BLUE = 0xFF60A5FA.toInt()
    }
}
