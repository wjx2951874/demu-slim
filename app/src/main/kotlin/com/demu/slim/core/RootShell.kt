package com.demu.slim.core

import java.util.concurrent.TimeUnit

/**
 * root 命令执行器。
 *
 * 不同 root 方案的 su 位置不一样（KernelSU / Magisk / 系统 su），而且授权弹窗需要人点一下，
 * 所以这里按候选路径逐个尝试，并把失败原因留下来给日志面板。
 */
object RootShell {

    data class Result(val code: Int, val out: String) {
        val ok: Boolean get() = code == 0
    }

    var lastError: String = ""
        private set

    private val suCandidates = listOf(
        "su",
        "/system/bin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ksud/bin/su",
        "/data/adb/magisk/su",
    )

    private var resolved: String? = null

    private fun exec(bin: String, command: String, timeoutSec: Long): Result {
        return try {
            val process = ProcessBuilder(bin, "-c", command)
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (finished) {
                Result(process.exitValue(), text)
            } else {
                process.destroyForcibly()
                Result(-1, text + "\n[超时：可能是授权弹窗没人点]")
            }
        } catch (t: Throwable) {
            Result(-1, t.message ?: "无法启动 su")
        }
    }

    private fun detect(): String? {
        resolved?.let { return it }
        val tried = StringBuilder()
        for (bin in suCandidates) {
            val r = exec(bin, "id -u", 25)
            val isRoot = r.out.lines().any { it.trim() == "0" }
            if (isRoot) {
                resolved = bin
                return bin
            }
            tried.append("$bin → ${r.out.trim().take(80)}；")
        }
        lastError = "su 全部不可用（$tried）"
        return null
    }

    fun run(command: String, timeoutSec: Long = 120): Result {
        val bin = detect() ?: return Result(-1, lastError)
        return exec(bin, command, timeoutSec)
    }

    /** 是否真的拿到了 uid=0，而不是 su 存在但没授权。 */
    fun hasRoot(): Boolean {
        if (detect() == null) return false
        val r = run("id", 20)
        if (r.out.contains("uid=0")) {
            lastError = ""
            return true
        }
        lastError = "已找到 su，但执行 id 未返回 uid=0：${r.out.trim().take(120)}"
        return false
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
