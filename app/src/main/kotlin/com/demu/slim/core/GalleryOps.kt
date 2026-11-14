package com.demu.slim.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import java.io.File
import java.util.Locale

/**
 * 相册瘦身的全部底层动作。
 *
 * 关键点：应用运行在自己的 mount namespace 里，看不到 /data/user/0（和 Termux 里遇到的一样），
 * 所以必须优先走 /proc/1/root/data/user/0/com.miui.gallery 这条 pid 1 根命名空间路径。
 *
 * 思路：官方“优化储存空间”的终态就是 cloud 表里 localFile 为空、serverId 仍在。
 * 这里做同样的终态，但把文件“移到暂存区”而不是真删，且只碰 serverId 非空的条目。
 */
object GalleryOps {

    private const val PKG = "com.miui.gallery"

    val DIR_CANDIDATES = listOf(
        "/proc/1/root/data/user/0/$PKG",
        "/data/user/0/$PKG",
        "/data/data/$PKG",
    )

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

    /** 最近一次失败原因，给日志面板用。 */
    var lastError: String = ""
        private set

    private var cachedDir: String? = null
    private var cachedDb: String? = null

    fun q(value: String): String = RootShell.shellQuote(value)

    /** 在几个候选路径里找出真正存在 databases 的那个。 */
    fun resolveGalleryDir(): String? {
        cachedDir?.let { return it }
        for (dir in DIR_CANDIDATES) {
            val r = RootShell.run("[ -d ${q("$dir/databases")} ] && echo HIT", 20)
            if (r.out.contains("HIT")) {
                cachedDir = dir
                return dir
            }
        }
        lastError = "找不到相册数据目录（已试过 /proc/1/root、/data/user/0、/data/data），root 没真正生效"
        return null
    }

    fun dbPath(): String? {
        cachedDb?.let { return it }
        val dir = resolveGalleryDir() ?: return null
        return "$dir/databases/gallery.db".also { cachedDb = it }
    }

    fun prepare(): Boolean {
        val r = RootShell.run("mkdir -p ${q(MOVED)} ${q(BACKUP)}; echo OK", 30)
        return r.out.contains("OK")
    }

    /** 把相册数据库（含 WAL）复制到本应用缓存，属于只读快照。 */
    private fun copyDb(ctx: Context): File? {
        val db = dbPath() ?: return null
        val dir = File(ctx.cacheDir, "db").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "gallery.db")
        val uid = Process.myUid()
        val t = q(target.absolutePath)
        val cmd = buildString {
            append("cp -f ${q(db)} $t || exit 1;")
            append("cp -f ${q("$db-wal")} ${q(target.absolutePath + "-wal")} 2>/dev/null;")
            append("cp -f ${q("$db-shm")} ${q(target.absolutePath + "-shm")} 2>/dev/null;")
            append("chown $uid:$uid $t* 2>/dev/null;")
            append("chmod 600 $t* 2>/dev/null;")
            append("echo COPIED")
        }
        val r = RootShell.run(cmd, 60)
        if (r.out.contains("COPIED") && target.length() > 0) return target
        lastError = "复制数据库失败：${r.out.trim().takeLast(300)}"
        return null
    }

    private fun long(d: SQLiteDatabase, sql: String): Long =
        d.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    private fun ageClause(minAgeDays: Int): String {
        if (minAgeDays <= 0) return ""
        val cutoff = System.currentTimeMillis() - minAgeDays.toLong() * 86_400_000L
        return " and dateTaken>0 and dateTaken<=$cutoff"
    }

    fun scan(ctx: Context, minAgeDays: Int = 0): Stats {
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
                            "and mimeType like 'video/%'" + ageClause(minAgeDays),
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
            lastError = "打开数据库失败：${t.message}"
            Stats(dbOk = false)
        }
    }

    data class MediaRow(
        val id: Long,
        val path: String,
        val size: Long,
        val dateTaken: Long,
        val synced: Boolean,
        val eligible: Boolean,
    )

    /** 列出仍占本地的视频 / 照片，用于“一键查看明细”。 */
    fun mediaRows(ctx: Context, video: Boolean, minAgeDays: Int = 0): List<MediaRow> {
        val cutoff = if (minAgeDays <= 0) Long.MAX_VALUE
        else System.currentTimeMillis() - minAgeDays.toLong() * 86_400_000L
        val db = copyDb(ctx) ?: return emptyList()
        val like = if (video) "video/%" else "image/%"
        return try {
            SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                val rows = ArrayList<MediaRow>()
                d.rawQuery(
                    "select _id, localFile, size, dateTaken, length(serverId)>0 from cloud " +
                        "where length(localFile)>0 and mimeType like '$like' order by size desc",
                    null,
                ).use { c ->
                    while (c.moveToNext()) {
                        val path = c.getString(1) ?: continue
                        if (path.isEmpty()) continue
                        val dateTaken = c.getLong(3)
                        val synced = c.getInt(4) != 0
                        val oldEnough = if (cutoff == Long.MAX_VALUE) true
                        else dateTaken > 0 && dateTaken <= cutoff
                        rows += MediaRow(
                            id = c.getLong(0),
                            path = path,
                            size = c.getLong(2),
                            dateTaken = dateTaken,
                            synced = synced,
                            eligible = synced && oldEnough,
                        )
                    }
                }
                rows
            }
        } catch (t: Throwable) {
            lastError = "读取明细失败：${t.message}"
            emptyList()
        }
    }

    fun pendingCount(): Int {
        val r = RootShell.run("[ -f ${q(INDEX)} ] && wc -l < ${q(INDEX)} || echo 0", 30)
        return r.out.trim().lines().lastOrNull()?.trim()?.toIntOrNull() ?: 0
    }

    fun pendingBytes(): Long {
        val r = RootShell.run("du -sk ${q(MOVED)} 2>/dev/null | cut -f1", 30)
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
        minAgeDays: Int,
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
        val db = dbPath() ?: run {
            log("✗ $lastError")
            return false
        }
        log("· 数据目录：$db")
        if (!prepare()) {
            log("✗ 无法创建暂存目录 $WORK")
            return false
        }

        val dbFile = copyDb(ctx) ?: run {
            log("✗ $lastError")
            return false
        }
        val backupPath = "$BACKUP/gallery-first.db"
        log("· 备份数据库 → $backupPath")
        RootShell.run(
            "[ -f ${q(backupPath)} ] || cp -f ${q(db)} ${q(backupPath)}",
            60,
        )
        File(ctx.filesDir, "last_backup.txt").writeText(backupPath)

        val candidates = ArrayList<Candidate>()
        try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                val types = ArrayList<String>()
                if (includeVideos) types += "mimeType like 'video/%'"
                if (includePhotos) types += "mimeType like 'image/%'"
                val sql = "select _id, localFile, size from cloud where length(localFile)>0 " +
                    "and length(serverId)>0 and (" + types.joinToString(" or ") + ")" + ageClause(minAgeDays)
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
        RootShell.run("am force-stop com.miui.gallery; sleep 2", 30)

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
        val moveResult = RootShell.run("sh ${q(scriptFile.absolutePath)}", 300)
        if (!moveResult.out.contains("MOVE_DONE")) {
            log("✗ 移动脚本异常：${moveResult.out.trim().takeLast(200)}")
        }

        val indexLines = RootShell.run("cat ${q(TMP_INDEX)} 2>/dev/null", 30).out
            .lines().filter { it.contains('\t') }
        val movedIds = indexLines.mapNotNull { line -> line.substringBefore('\t').toLongOrNull() }
        log("· 实际移出 ${movedIds.size} 项")
        if (movedIds.isEmpty()) {
            log("· 没有符合条件（大小不符或文件缺失）的条目")
            return true
        }

        val rewritten = try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
                try {
                    d.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
                } catch (_: Throwable) {
                }
                d.beginTransaction()
                for (id in movedIds) {
                    d.execSQL("update cloud set localFile=NULL where _id=$id")
                }
                d.setTransactionSuccessful()
                d.endTransaction()
                true
            }
        } catch (t: Throwable) {
            log("✗ 改写数据库失败：${t.message}")
            false
        }
        if (!rewritten) {
            log("✗ 数据库未改写，文件已移动，可用“还原”恢复")
            return false
        }

        val push = RootShell.run(
            "rm -f ${q("$db-wal")} ${q("$db-shm")}; " +
                "cp -f ${q(dbFile.path)} ${q(db)} && " +
                "cp -f ${q(dbFile.path + "-wal")} ${q("$db-wal")} 2>/dev/null; " +
                "cp -f ${q(dbFile.path + "-shm")} ${q("$db-shm")} 2>/dev/null; echo PUSHED",
            60,
        )
        if (!push.out.contains("PUSHED")) {
            log("✗ 写回数据库失败：${push.out.trim().takeLast(200)}")
            return false
        }
        RootShell.run("cat ${q(TMP_INDEX)} >> ${q(INDEX)}; rm -f ${q(TMP_INDEX)}", 30)
        log("· 完成：${movedIds.size} 个文件已移出，库记录已置为仅云端")
        return true
    }

    /** 还原：文件搬回原位 + 数据库回到动手前的备份。 */
    fun restore(ctx: Context, log: (String) -> Unit): Boolean {
        if (!RootShell.hasRoot()) {
            log("✗ 没有 root")
            return false
        }
        val db = dbPath() ?: run {
            log("✗ $lastError")
            return false
        }
        val backup = File(ctx.filesDir, "last_backup.txt").takeIf { it.exists() }?.readText()?.trim()
        if (backup.isNullOrBlank()) {
            log("✗ 找不到备份记录")
            return false
        }
        RootShell.run("am force-stop com.miui.gallery; sleep 2", 30)

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
        val moved = RootShell.run("sh ${q(script.absolutePath)}", 300)
        val backCount = moved.out.lines().count { it.startsWith("BACK:") }
        log("· 文件还原 $backCount 个")

        val dbRestore = RootShell.run(
            "rm -f ${q("$db-wal")} ${q("$db-shm")}; " +
                "cp -f ${q(backup)} ${q(db)} && echo DBRESTORED",
            60,
        )
        if (!dbRestore.out.contains("DBRESTORED")) {
            log("✗ 数据库还原失败：${dbRestore.out.trim().takeLast(200)}")
            return false
        }
        RootShell.run(": > ${q(INDEX)}", 30)
        log("· 数据库已还原到 $backup")
        return true
    }

    /** 清空暂存区（确认云端正常后再点）。 */
    fun purge(log: (String) -> Unit): Boolean {
        if (!RootShell.hasRoot()) {
            log("✗ 没有 root")
            return false
        }
        val r = RootShell.run("rm -rf ${q(MOVED)}; mkdir -p ${q(MOVED)}; : > ${q(INDEX)}; echo PURGED", 60)
        log(if (r.out.contains("PURGED")) "· 暂存区已清空" else "✗ 清理失败")
        return r.out.contains("PURGED")
    }
}
