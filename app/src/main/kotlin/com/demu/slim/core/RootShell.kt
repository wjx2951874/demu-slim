package com.demu.slim.core

import java.util.concurrent.TimeUnit

/** 简单的 root 命令执行器：全部操作通过 `su -c` 交给系统 shell。 */
object RootShell {

    data class Result(val code: Int, val out: String) {
        val ok: Boolean get() = code == 0
    }

    fun run(command: String, timeoutSec: Long = 120): Result {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (finished) Result(process.exitValue(), text) else {
                process.destroyForcibly()
                Result(-1, text + "\n[超时]")
            }
        } catch (t: Throwable) {
            Result(-1, t.message ?: "无法执行 root 命令（设备未授权？）")
        }
    }

    /** 判断是否真的拿到了 uid=0 的 shell，而不是 su 二进制存在但没授权。 */
    fun hasRoot(): Boolean {
        val r = run("id -u", 15)
        return r.out.trim().lines().lastOrNull()?.trim() == "0"
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
