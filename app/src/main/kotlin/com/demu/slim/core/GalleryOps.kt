package com.demu.slim.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 相册瘦身的全部底层动作。
 *
 * 思路：官方“优化储存空间”的终态就是 cloud 表里 localFile 为空、serverId 仍在。
 * 这里做同样的终态，但把文件“移到暂存区”而不是真删，且只碰 serverId 非空的条目。
 */
object GalleryOps {

    const val DB = "/data/user/0/com.miui.gallery/databases/gallery.db"
    const val WORK = "/sdcard/DemuSlim"
    const val MOVED = "$WORK/moved"
    const val BACKUP = "$WORK/backup"
    const val INDEX = "$WORK/index.txt"
    private const val TMP_INDEX = "$WORK/index_tmp.txt"

    /** 库里记录的大小和实际文件大小允许的偏差（实测只有几百字节的尾部差异）。 */
    private const val SIZE_TOLERANCE = 8192L

    data class Candidate(val id: Long, val path: String, val size: Long)

    data class Stats(
        val dbOk: Boolean = false,
        val videoTotal: Int = 0,
        val videoLocalCount: Int = 0,
        val videoLocalBytes: Long = 0,
        val videoSyncedBytes: Long = 0,
        val photoLocalCount: Int = 0,
        val photoLocalBytes: Long = 0,
        val pendingFiles: Int = 0,
        val pendingBytes: Long = 0,
    )

    fun prepare(): Boolean {
        val q = RootShell.shellQuote
        val r = RootShell.run("mkdir -p ${q(MOVED)} ${q(BACKUP)}; echo OK")
        return r.out.contains("OK")
    }

    private fun now(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())

    /** 把相册数据库（含 WAL）复制到本应用缓存，属于只读快照。 */
    private fun copyDb(ctx: Context): File? {
        val dir = File(ctx.cacheDir, "db").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "gallery.db")
        val q = RootShell.shellQuote
        val uid = Process.myUid()
        val t = q(target.absolutePath)
        val cmd = buildString {
            append("cp -f ${q(DB)} $t || exit 1;")
            append("cp -f ${q("$DB-wal")} ${q(target.absolutePath + "-wal")} 2>/dev/null;")
            append("cp -f ${q("$DB-shm")} ${q(target.absolutePath + "-shm")} 2>/dev/null;")
            append("chown $uid:$uid $t* 2>/dev/null;")
            append("chmod 600 $t* 2>/dev/null;")
            append("echo COPIED")
        }
        val r = RootShell.run(cmd)
        return if (r.out.contains("COPIED") && target.length() > 0) target else null
    }

    private fun long(d: SQLiteDatabase, sql: String): Long =
        d.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    fun scan(ctx: Context): Stats {
        val db = copyDb(ctx) ?: return Stats(dbOk = false)
        return try {
            SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                val videoTotal = long(d, "select count(*) from cloud where mimeType like 'video/%'").toInt()
                val videoLocalCount =
                    long(d, "select count(*) from cloud where length(localFile)>0 and mimeType like 'video/%'").toInt()
                val videoLocalBytes =
                    long(d, "select sum(size) from cloud where length(localFile)>0 and mimeType like 'video/%'")
                val videoSyncedBytes =
                    long(
                        d,
                        "select sum(size) from cloud where length(localFile)>0 and length(serverId)>0 " +
                            "and mimeType like 'video/%'",
                    )
                val photoLocalCount =
                    long(d, "select count(*) from cloud where length(localFile)>0 and mimeType like 'image/%'").toInt()
                val photoLocalBytes =
                    long(d, "select sum(size) from cloud where length(localFile)>0 and mimeType like 'image/%'")
                Stats(
                    dbOk = true,
                    videoTotal = videoTotal,
                    videoLocalCount = videoLocalCount,
                    videoLocalBytes = videoLocalBytes,
                    videoSyncedBytes = videoSyncedBytes,
                    photoLocalCount = photoLocalCount,
                    photoLocalBytes = photoLocalBytes,
                    pendingFiles = pendingCount(),
                    pendingBytes = pendingBytes(),
                )
            }
        } catch (t: Throwable) {
            Stats(dbOk = false)
        }
    }

    fun pendingCount(): Int {
        val r = RootShell.run("[ -f ${RootShell.shellQuote(INDEX)} ] && wc -l < ${RootShell.shellQuote(INDEX)} || echo 0")
        return r.out.trim().lines().lastOrNull()?.trim()?.toIntOrNull() ?: 0
    }

    fun pendingBytes(): Long {
        val r = RootShell.run("du -sk ${RootShell.shellQuote(MOVED)} 2>/dev/null | cut -f1")
        return (r.out.trim().toLongOrNull() ?: 0L) * 1024L
    }

    /**
     * 一键瘦身：
     * 1. 备份数据库
     * 2. 校验文件大小后移入暂存区，并记录索引
     * 3. 只对“确实移走”的条目把 localFile 置空
     * 4. 写回数据库
     */
    fun slim(
        ctx: Context,
        includeVideos: Boolean,
        includePhotos: Boolean,
        log: (String) -> Unit,
    ): Boolean {
        if (!RootShell.hasRoot()) {
            log("✗ 没有拿到 root，无法修改相册数据库")
            return false
        }
        if (!includeVideos && !includePhotos) {
            log("✗ 至少选择一种媒体类型")
            return false
        }
        if (!prepare()) {
            log("✗ 无法创建暂存目录 $WORK")
            return false
        }

        val dbFile = copyDb(ctx) ?: run {
            log("✗ 复制 gallery.db 失败（可能未授权或路径不存在）")
            return false
        }
        val backupPath = "$BACKUP/gallery-first.db"
        log("· 备份数据库 → $backupPath")
        RootShell.run(
            "[ -f ${RootShell.shellQuote(backupPath)} ] || " +
                "cp -f ${RootShell.shellQuote(DB)} ${RootShell.shellQuote(backupPath)}",
        )
        File(ctx.filesDir, "last_backup.txt").writeText(backupPath)

        val candidates = ArrayList<Candidate>()
        try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                val types = ArrayList<String>()
                if (includeVideos) types += "mimeType like 'video/%'"
                if (includePhotos) types += "mimeType like 'image/%'"
                val sql = "select _id, localFile, size from cloud where length(localFile)>0 " +
                    "and length(serverId)>0 and (" + types.joinToString(" or ") + ")"
                d.rawQuery(sql, null).use { c ->
                    while (c.moveToNext()) {
                        val path = c.getString(1) ?: continue
                        if (path.isNotEmpty()) candidates += Candidate(c.getLong(0), path, c.getLong(2))
                    }
                }
            }
        } catch (t: Throwable) {
            log("✗ 读取候选列表失败：${t.message}")
            return false
        }
        log("· 候选 ${candidates.size} 项")
        if (candidates.isEmpty()) {
            log("· 没有可处理的条目（都已仅云端）")
            return true
        }

        log("· 停止相册进程")
        RootShell.run("am force-stop com.miui.gallery; sleep 2")

        val q = RootShell.shellQuote
        val script = StringBuilder()
        script.append("mkdir -p ${q(MOVED)}\n")
        script.append(": > ${q(TMP_INDEX)}\n")
        for (c in candidates) {
            val name = "${c.id}-${c.path.substringAfterLast('/')}"
            val dst = "$MOVED/$name"
            script.append("f=${q(c.path)}; ")
            script.append("if [ -f \"\$f\" ]; then ")
            script.append("s=\$(stat -c %s \"\$f\"); ")
            script.append("d=\$((s-${c.size})); ")
            script.append("if [ \$d -ge -$SIZE_TOLERANCE ] && [ \$d -le $SIZE_TOLERANCE ]; then ")
            script.append("mv -f \"\$f\" ${q(dst)} && ")
            script.append("printf '%s\\t%s\\t%s\\n' ${q(c.id.toString())} \"\$f\" ${q(name)} >> ${q(TMP_INDEX)}; ")
            script.append("fi; fi\n")
        }
        script.append("echo MOVE_DONE\n")

        val scriptFile = File(ctx.filesDir, "move.sh")
        scriptFile.writeText(script.toString())
        log("· 开始移动文件…")
        val moveResult = RootShell.run("sh ${RootShell.shellQuote(scriptFile.absolutePath)}", 300)
        if (!moveResult.out.contains("MOVE_DONE")) {
            log("✗ 移动脚本异常：${moveResult.out.takeLast(200)}")
        }

        val indexLines = RootShell.run("cat ${q(TMP_INDEX)} 2>/dev/null").out
            .lines().filter { it.contains('\t') }
        val movedIds = indexLines.mapNotNull { line -> line.substringBefore('\t').toLongOrNull() }
        log("· 实际移出 ${movedIds.size} 项，${movedIds.size} 条记录将置为仅云端")
        if (movedIds.isEmpty()) {
            log("· 没有符合条件（大小不符或文件缺失）的条目")
            return true
        }

        val reopen = try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
                try {
                    d.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
                } catch (_: Throwable) {
                }
                d.beginTransaction()
                var done = 0
                for (id in movedIds) {
                    d.execSQL("update cloud set localFile=NULL where _id=$id")
                    done++
                }
                d.setTransactionSuccessful()
                d.endTransaction()
                done > 0
            }
        } catch (t: Throwable) {
            log("✗ 改写数据库失败：${t.message}")
            false
        }
        if (!reopen) {
            log("✗ 数据库未改写，文件已移动，可用“还原”恢复")
            return false
        }

        val push = RootShell.run(
            "rm -f ${q("$DB-wal")} ${q("$DB-shm")}; " +
                "cp -f ${q(dbFile.path)} ${q(DB)} && " +
                "cp -f ${q(dbFile.path + "-wal")} ${q("$DB-wal")} 2>/dev/null; " +
                "cp -f ${q(dbFile.path + "-shm")} ${q("$DB-shm")} 2>/dev/null; echo PUSHED",
        )
        if (!push.out.contains("PUSHED")) {
            log("✗ 写回数据库失败：${push.out.takeLast(200)}")
            return false
        }
        RootShell.run("cat ${q(TMP_INDEX)} >> ${q(INDEX)}; rm -f ${q(TMP_INDEX)}")
        log("· 完成：${movedIds.size} 个文件已移出，库记录已置为仅云端")
        return true
    }

    /** 还原：文件搬回原位 + 数据库回到最近一次备份。 */
    fun restore(ctx: Context, log: (String) -> Unit): Boolean {
        if (!RootShell.hasRoot()) {
            log("✗ 没有 root")
            return false
        }
        val q = RootShell.shellQuote
        val backup = File(ctx.filesDir, "last_backup.txt").takeIf { it.exists() }?.readText()?.trim()
        if (backup.isNullOrBlank()) {
            log("✗ 找不到备份记录")
            return false
        }
        RootShell.run("am force-stop com.miui.gallery; sleep 2")

        val script = File(ctx.filesDir, "restore.sh")
        script.writeText(
            """
            IDX=${q(INDEX)}
            MOV=${q(MOVED)}
            IFS="${'$'}(printf '\t')"
            if [ -f "${'$'}IDX" ]; then
              while read -r id path name; do
                [ -n "${'$'}name" ] || continue
                if [ -f "${'$'}MOV/${'$'}name" ]; then
                  mkdir -p "${'$'}(dirname "${'$'}path")"
                  mv -f "${'$'}MOV/${'$'}name" "${'$'}path" && echo "BACK:${'$'}name"
                fi
              done < "${'$'}IDX"
            fi
            echo RESTORE_DONE
            """.trimIndent(),
        )
        val moved = RootShell.run("sh ${q(script.absolutePath)}; echo X", 300)
        val backCount = moved.out.lines().count { it.startsWith("BACK:") }
        log("· 文件还原 $backCount 个")

        val dbRestore = RootShell.run(
            "rm -f ${q("$DB-wal")} ${q("$DB-shm")}; " +
                "cp -f ${q(backup)} ${q(DB)} && echo DBRESTORED",
        )
        if (!dbRestore.out.contains("DBRESTORED")) {
            log("✗ 数据库还原失败")
            return false
        }
        RootShell.run(": > ${q(INDEX)}")
        log("· 数据库已还原到 $backup")
        return true
    }

    /** 清空暂存区（确认云端正常后再点）。 */
    fun purge(log: (String) -> Unit): Boolean {
        if (!RootShell.hasRoot()) {
            log("✗ 没有 root")
            return false
        }
        val q = RootShell.shellQuote
        val r = RootShell.run("rm -rf ${q(MOVED)}; mkdir -p ${q(MOVED)}; : > ${q(INDEX)}; echo PURGED")
        log(if (r.out.contains("PURGED")) "· 暂存区已清空" else "✗ 清理失败")
        return r.out.contains("PURGED")
    }
}
