# 云相册瘦身（DemuSlim）

小米云相册的「优化储存空间」只会处理**很旧的照片**，且**视频完全不在范围内**（官方文案也是“照片将自动优化为较小分辨率”）。
本工具直接做出官方“已优化”的**终态**：`gallery.db` 的 `cloud` 表里 `localFile = NULL`，文件本身被移到暂存区，云端 `serverId` 原封不动。

实测：小米 15 / HyperOS 4 / Android 17，本地 113 个视频 24GB → 移出 95 个 24,069MB，可用空间 10GB → 34GB，无云端回传。

## 它做了什么

1. 备份 `gallery.db`（`/sdcard/DemuSlim/backup/gallery-first.db`，救命的，别删）
2. 读取 `cloud` 表里“本地有文件 + 云端有 serverId”的视频/照片
3. 逐个校验实际文件大小（允许 8KB 尾部差异），符合的 `mv` 到 `/sdcard/DemuSlim/moved/`，并记录索引
4. 只对**确实移走**的条目把 `localFile` 置空
5. 写回数据库（先 force-stop 相册，删掉旧 `-wal/-shm`）

相册里这些视频会立刻变回“仅云端”状态，点播放才走网络下载 —— 和官方优化后的效果一致。

## 安全边界

- 只处理 `serverId` 非空的条目：**不会**把只有本地一份的东西弄丢
- 文件是移走不是删除，`还原到动手前` 可以一键搬回来 + 数据库回滚
- 确认云端一切正常后，再点 `清空暂存区` 真正腾出空间

## 需要 Root

读写 `com.miui.gallery` 私有目录（`/data/user/0/com.miui.gallery/databases/gallery.db`）只有 uid 0 能做到，Shizuku 的 shell 权限不够。
没有 root 时只能看统计（只读体检），不能改任何东西。

## 自己编译

```bash
gradle :app:assembleDebug
```

CI 在 `.github/workflows/build.yml`，push 到 main 自动出 APK。
