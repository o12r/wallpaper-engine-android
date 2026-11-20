# WE Workshop for Android

Browse, search and download **Wallpaper Engine** Steam Workshop wallpapers directly on your Android phone — no more copying files over from your PC.

[简体中文](#简体中文)

> **Unofficial.** This project is not affiliated with or endorsed by Wallpaper Engine (Kristjan Skutta) or Valve. You need a Steam account that **owns Wallpaper Engine**; the app downloads content the same way the Steam client / SteamCMD does, using your own account.

## Features

- Sign in with your Steam account (password + Steam Guard: mobile app approval, authenticator code or e‑mail code). Only Steam's refresh token is stored, never your password.
- Browse the Workshop: sort (trending, top rated, most subscribed, newest, recently updated), full‑text search (sortable too), and filters for type, tag, **resolution** (portrait phone resolutions first) and content rating.
- **My subscriptions**: lists the wallpapers your Steam account subscribes to (nothing is downloaded automatically).
- Wallpaper details with preview images; tap any tag to browse it.
- Download queue in the background with progress notification; reorder, move to front, or cancel.
- Files are exported to `Download/WallpaperEngine/<workshop id>/`.
- **Scan `Download/WallpaperEngine`** (Downloads screen) to add wallpapers that are already there: copied from a PC's `steamapps/workshop/content/431960/<id>`, or downloaded before a reinstall. Needs “All files access”, asked for only when you scan.
- **Video wallpapers** can be set as a live wallpaper with the built‑in player (“WE Video Wallpaper” also appears in the system live‑wallpaper picker, with its own settings).
  - Effects: a black or white status bar shade, blur, saturation, and sharp upscaling for low‑resolution videos.
  - A visual crop editor: drag, pinch to zoom, rotate, mirror and flip, with the frame showing exactly what your screen shows.
- Full‑screen preview with a mock lock‑screen clock.
- English and Simplified Chinese, light / dark / true black (AMOLED) themes.
- In‑app updates from GitHub Releases (checked on launch at most every 12 h, or manually in Settings).

### Limitations

- **Scene** wallpapers (`scene.pkg`) are downloaded but not rendered — they need the official Wallpaper Engine Android app or another compatible player.
- **Web** and **Application** wallpapers depend on the desktop runtime and usually can't be used on a phone.

## Install

Download the APK from [Releases](../../releases) and install it (Android 10 or later).

If you previously installed a build signed with a different key, uninstall it first.

### Steam “unusual sign in” check

Steam may hold the first sign‑in from a new client and ask a few questions in the Steam mobile app. Answer:

- *Where are you trying to sign in?* → **Steam Client**
- *What are you trying to do?* → **Other**

Don't choose “Vote on a workshop item”, “Collect a free skin” and similar — those are scam patterns and Steam will block the sign‑in.

## How it works

- [JavaSteam](https://github.com/Longi94/JavaSteam) connects to Steam and signs in with Steam's modern auth flow.
- Browsing uses Steam's `PublishedFile.QueryFiles` / `GetDetails` services over the authenticated connection (no Web API key needed).
- Downloads use `javasteam-depotdownloader`: the item's content manifest (`hcontent_file`) is fetched from the Wallpaper Engine workshop depot on Steam's CDN, like DepotDownloader / SteamCMD.

Workarounds for running JavaSteam on Android, in case they help others:

- Android's built‑in `BC` security provider is stripped down (no SHA‑1 `MessageDigest`), and JavaSteam can't register the full BouncyCastle under the same name — the app replaces the platform provider at startup.
- `EWorkshopFileType.from(0)` returns `First` instead of `Community`, so DepotDownloader skips every Wallpaper Engine item as unsupported (and never completes) — the app adds `First` to the supported set.
- `zstd-jni` (AAR) and `xz` must be added explicitly for depot chunk decompression.
- Unified‑message RPCs write to the socket on the calling thread, so they must not be issued from the main thread.

## Build

Requires JDK 21 and the Android SDK (compileSdk 37.2).

```
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Release builds are signed with the key described in a git‑ignored `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); without it they fall back to the debug key.

---

## 简体中文

在手机上直接浏览、搜索、下载 **Wallpaper Engine** 创意工坊壁纸，不用再从电脑传。

> **非官方项目**，与 Wallpaper Engine 及 Valve 无关。需要一个**已购买 Wallpaper Engine** 的 Steam 账号；App 用你自己的账号，以与 Steam 客户端 / SteamCMD 相同的方式下载内容。

### 功能

- 登录 Steam 账号（密码 + Steam 令牌：手机 App 批准、令牌验证码或邮箱验证码）。只保存 Steam 的 refresh token，不保存密码。
- 浏览创意工坊：排序（热门、好评、订阅最多、最新、最近更新）、搜索（搜索结果也能排序），按类型、标签、**分辨率**（竖屏手机分辨率排在前面）和内容分级筛选。
- **我的订阅**：列出 Steam 账号订阅的壁纸（不会自动下载）。
- 壁纸详情和预览图；点击任意标签即可浏览该标签。
- 后台下载队列，通知栏显示进度；可调整顺序、设为下一个下载、取消下载。
- 文件导出到 `Download/WallpaperEngine/<壁纸ID>/`。
- 可在下载页面**扫描 `Download/WallpaperEngine`**，把已有的壁纸加入列表（例如从电脑 `steamapps/workshop/content/431960/<ID>` 复制过来的，或重装前下载的）。需要“所有文件访问权限”，只在扫描时请求。
- **视频**类壁纸可以用内置播放器设为动态壁纸（系统动态壁纸列表里也有“WE 视频壁纸”，带独立设置页）。
  - 效果：黑色或白色的状态栏遮罩、模糊、饱和度，以及给低分辨率视频用的超分辨率（锐化放大）。
  - 可视化裁剪：拖动、双指缩放、旋转、镜像和翻转，方框里就是屏幕上显示的样子。
- 全屏预览，叠加模拟锁屏时钟。
- 中文 / 英文界面，浅色 / 深色 / 纯黑（AMOLED）主题。
- 从 GitHub Releases 检查并安装更新（启动时最多每 12 小时检查一次，也可在设置里手动检查）。

### 限制

- **场景**类壁纸（scene.pkg）只能下载，不能渲染，需要 Wallpaper Engine 官方安卓版或其它兼容播放器。
- **网页 / 应用**类壁纸依赖电脑端运行环境，手机上一般不能用。

### 安装

在 [Releases](../../releases) 下载 APK 安装（需要 Android 10 及以上）。如果之前装过不同签名的版本，需要先卸载。

### Steam“异常登录”确认

Steam 可能会在手机 App 里拦下新客户端的首次登录并提问，请这样选：

- *Where are you trying to sign in?* → **Steam Client**
- *What are you trying to do?* → **Other**

不要选 “Vote on a workshop item”“Collect a free skin” 等选项，那些是常见盗号骗局话术，选了会被拦截。
