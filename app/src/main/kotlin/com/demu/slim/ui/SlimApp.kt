package com.demu.slim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.demu.slim.core.GalleryOps
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.util.Locale

@Composable
fun SlimApp(vm: SlimViewModel) {
    MiuixTheme(
        colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        LaunchedEffect(Unit) { vm.refreshRoot(scope) }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(title = "云相册瘦身", scrollBehavior = scrollBehavior)
            },
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 640.dp)
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .scrollEndHaptic()
                        .imePadding(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = paddingValues.calculateTopPadding() + 8.dp,
                        bottom = paddingValues.calculateBottomPadding() + 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "status") {
                        StatusCard(
                            rootReady = vm.rootReady,
                            busy = vm.busy,
                            onClick = { vm.refreshRoot(scope) },
                        )
                    }
                    item(key = "device") {
                        InfoCard(
                            deviceModel = vm.deviceModel,
                            androidVersion = vm.androidVersion,
                            galleryVersion = vm.galleryVersion,
                        )
                    }
                    item(key = "stats") {
                        StatsCard(
                            videoCount = vm.stats.videoLocalCount,
                            videoBytes = vm.stats.videoLocalBytes,
                            videoSynced = vm.stats.videoSyncedBytes,
                            photoCount = vm.stats.photoLocalCount,
                            photoBytes = vm.stats.photoLocalBytes,
                            pendingCount = vm.stats.pendingFiles,
                            pendingBytes = vm.stats.pendingBytes,
                            dbOk = vm.stats.dbOk,
                            busy = vm.busy,
                            onScan = { vm.scan(scope) },
                            onViewVideos = { vm.loadDetails(scope, true) },
                            onViewPhotos = { vm.loadDetails(scope, false) },
                        )
                    }
                    if (vm.details.isNotEmpty()) {
                        item(key = "details") {
                            DetailCard(
                                title = vm.detailsTitle,
                                rows = vm.details,
                                onOpen = { row -> openMedia(context, row) { msg -> vm.log("✗ $msg", SlimViewModel.RED) } },
                            )
                        }
                    }
                    item(key = "options") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            SwitchPreference(
                                checked = vm.includeVideos,
                                onCheckedChange = { vm.includeVideos = it },
                                title = "包含视频",
                                summary = "从云端回读时按需下载，占网络流量",
                            )
                            SwitchPreference(
                                checked = vm.includePhotos,
                                onCheckedChange = { vm.includePhotos = it },
                                title = "包含照片",
                                summary = "照片被官方“优化”后本来就只留缩略图",
                            )
                        }
                    }
                    item(key = "actions") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(
                                text = if (vm.busy) "处理中…" else "一键瘦身",
                                enabled = !vm.busy && vm.rootReady,
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                onClick = { vm.slim(scope) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextButton(
                                text = "还原到动手前",
                                enabled = !vm.busy && vm.rootReady,
                                onClick = { vm.restore(scope) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextButton(
                                text = "清空暂存区（确认云端正常后）",
                                enabled = !vm.busy && vm.rootReady && vm.stats.pendingFiles > 0,
                                onClick = { vm.purge(scope) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    item(key = "log") {
                        Column {
                            Text(text = "运行日志", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(6.dp))
                            LogPanel(lines = vm.logs, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}


/** 直接指定一个能处理该类型的应用，避免弹“打开方式”选择框。 */
private fun openMedia(context: android.content.Context, row: GalleryOps.MediaRow, onError: (String) -> Unit) {
    val lower = row.path.lowercase()
    val isVideo = lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".mov") ||
        lower.endsWith(".webm") || lower.endsWith(".3gp") || lower.endsWith(".avi")
    val mime = if (isVideo) "video/*" else "image/*"
    try {
        val file = java.io.File(row.path)
        if (!file.exists()) {
            onError("文件已不存在：${row.path.substringAfterLast('/')}")
            return
        }
        val uri = FileProvider.getUriForFile(context, "com.demu.slim.fileprovider", file)
        val base = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)

        // 依次锁定具体应用：小米相册 → 小米视频 → 系统图库 → 任意一个（这时才会弹选择框）
        val targets = listOf("com.miui.gallery", "com.miui.videoplayer", "com.miui.player", "com.android.gallery3d")
        for (pkg in targets) {
            val intent = android.content.Intent(base).setPackage(pkg)
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                context.startActivity(intent)
                return
            }
        }
        context.startActivity(base)
    } catch (t: Throwable) {
        onError(t.message ?: "打开失败")
    }
}

@Composable
private fun StatusCard(
    rootReady: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val visual = if (rootReady) RootReadyCard() else RootMissingCard()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = visual.background),
        onClick = onClick,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp)) {
            Icon(
                imageVector = visual.icon,
                contentDescription = null,
                tint = visual.tint,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 27.dp, y = 31.dp)
                    .size(110.dp),
            )
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 120.dp, bottom = 14.dp),
            ) {
                Text(text = visual.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(1.dp))
                Text(text = visual.summary, fontSize = 15.sp)
            }
        }
    }
}

private data class StatusVisual(
    val background: Color,
    val title: String,
    val summary: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tint: Color,
)

@Composable
private fun RootReadyCard(): StatusVisual = StatusVisual(
    background = if (isSystemInDarkTheme()) Color(0xFF1A3825) else Color(0xFFDFFAE4),
    title = "Root 已就绪",
    summary = "可以读取并改写相册数据库",
    icon = Icons.Rounded.CheckCircleOutline,
    tint = Color(0xFF36D167),
)

@Composable
private fun RootMissingCard(): StatusVisual = StatusVisual(
    background = if (isSystemInDarkTheme()) Color(0xFF3E2F1B) else Color(0xFFFFF0DB),
    title = "需要 Root 权限",
    summary = "点此重新申请；未授权时只能查看统计",
    icon = Icons.Rounded.RemoveCircleOutline,
    tint = Color(0xFFF5A623),
)

@Composable
private fun InfoCard(
    deviceModel: String,
    androidVersion: String,
    galleryVersion: String,
) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        SelectionContainer {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                InfoItem(title = "设备", value = deviceModel)
                InfoItem(title = "系统", value = androidVersion)
                InfoItem(title = "相册版本", value = galleryVersion)
            }
        }
    }
}

@Composable
private fun InfoItem(title: String, value: String) {
    Column {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            modifier = Modifier.padding(top = 2.dp),
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        )
    }
}

@Composable
private fun StatsCard(
    videoCount: Int,
    videoBytes: Long,
    videoSynced: Long,
    photoCount: Int,
    photoBytes: Long,
    pendingCount: Int,
    pendingBytes: Long,
    dbOk: Boolean,
    busy: Boolean,
    onScan: () -> Unit,
    onViewVideos: () -> Unit,
    onViewPhotos: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            InfoItem(
                title = "本地视频",
                value = if (dbOk) "$videoCount 个 · ${format(videoBytes)}" else "—",
            )
            InfoItem(
                title = "其中已同步",
                value = if (dbOk) format(videoSynced) + " 可以瘦身" else "—",
            )
            InfoItem(
                title = "本地照片",
                value = if (dbOk) "$photoCount 个 · ${format(photoBytes)}" else "—",
            )
            InfoItem(
                title = "暂存区",
                value = if (pendingCount > 0) "$pendingCount 个 · ${format(pendingBytes)}" else "空",
            )
            TextButton(
                text = if (busy) "处理中…" else "重新体检",
                enabled = !busy,
                onClick = onScan,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = "查看视频明细",
                    enabled = !busy,
                    onClick = onViewVideos,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "查看照片明细",
                    enabled = !busy,
                    onClick = onViewPhotos,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DetailCard(
    title: String,
    rows: List<GalleryOps.MediaRow>,
    onOpen: (GalleryOps.MediaRow) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(text = "点任意一行直接打开该文件", fontSize = 13.sp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0B1220))
                    .padding(10.dp),
            ) {
                LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(rows) { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onOpen(row) }
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = row.path.substringAfterLast('/'),
                                    fontSize = 13.sp,
                                    color = Color(0xFFFFFFFF),
                                    maxLines = 1,
                                )
                                Text(
                                    text = "${format(row.size)} · ${dateOf(row.dateTaken)} · ${if (row.synced) "可瘦身" else "无云端，不动"}",
                                    fontSize = 11.sp,
                                    color = if (row.synced) Color(0xFF5FD68A) else Color(0xFFFFC94D),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun dateOf(ms: Long): String = if (ms <= 0) "未知时间" else
    java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(java.util.Date(ms))

@Composable
private fun LogPanel(lines: List<LogLine>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Column(
        modifier = modifier
            .height(240.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0B1220))
            .padding(12.dp),
    ) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp),
            ) {
                items(lines) { line ->
                    Text(
                        text = line.text,
                        color = Color(line.color),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

private fun format(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f GB", bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes.toDouble() / (1L shl 20))
    else -> "${bytes / 1024} KB"
}
