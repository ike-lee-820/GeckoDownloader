# Gecko 下载器（GeckoDownloader）

Android `arm64-v8a` 单架构、**界面默认中文**的下载器，内置基于**系统 WebView** 的浏览器（已移除 GeckoView 引擎，安装包体积大幅减小）。

## 功能

- HTTP / HTTPS 分块多线程下载：**单任务最多 128 线程**、**最多 32 个并发任务**，断点续传、失败重试、实时进度与速度
- 进度每 50ms 刷新一次，速度每 250ms 采样一次
- HLS（`.m3u8`）分片下载
- 内置系统 WebView 浏览器：地址栏、多标签、前进/后退/刷新、下载链接捕获、媒体资源嗅探
  （标签切换只改可见性，不在重组中反复插拔 WebView；嗅探回调只读请求头，不跨线程调用 WebView 方法）
- 通过分享、浏览器「打开方式」或应用内「添加下载」接收链接
- 前台服务通知、开机恢复、SAF 自定义下载目录
- 启动自动检查更新 / 设置页手动检查，应用内最多 128 线程下载升级

技术栈：Kotlin、Jetpack Compose、Material 3、Hilt、Room、DataStore、OkHttp、WorkManager；
minSdk 26、targetSdk 34、compileSdk 35、Java 17。

## 构建

仓库不带 Gradle Wrapper。AGP 8.5.2 要求 Gradle 8.7+：

```bash
gradle wrapper --gradle-version 8.7 --distribution-type bin
./gradlew :app:assembleDebug
```

浏览器改用系统 WebView 后不再依赖 `maven.mozilla.org`，只从 Google / Maven Central 拉依赖。
`abiFilters` 仅保留 `arm64-v8a`；`versionName` / `versionCode` 默认 `26.11.0` / `2611000`，
可用 `-PappVersionName=... -PappVersionCode=...` 覆盖。

## 自动发版（GitHub Actions）

`.github/workflows/release.yml`：push 到 `main` 即自动构建 release APK，固定签名后发布 GitHub Release。
也支持 push `release*` 分支 / tag 和 Actions 手动运行。

- **版本号**：`YY.MM.N`（年.月.当月序号，北京时间），本月第二个版本就是 `26.11.1`；已有 tag 不会覆盖。
- **产物**：`GeckoDownloader-<版本>-arm64-v8a.apk` + `SHA256SUMS`，Release 说明里附加速直链和签名证书指纹。
- **签名**：`signing/ikelee-release.jks`（别名 `ikelee`，口令 `liyikui917`）随仓库提交，零配置；
  各版本签名一致才能覆盖升级。密钥公开仅用于保证可升级，不承担身份可信。
- **Token**：用 Actions 内置 `GITHUB_TOKEN`（`contents: write`），不需要 PAT。

## 应用内更新

- 元数据走公开 `https://api.github.com/repos/ike-lee-820/GeckoDownloader/releases/latest`，匿名无需 token，优先挑 `arm64-v8a` 的 APK。
- **只代理文件直链**：gh-proxy 一类服务只加速
  `https://github.com/{owner}/{repo}/releases/download/{tag}/{file}`，
  **不代理 GitHub 网页**。把 `/releases/tag/{tag}` 套上代理前缀，浏览器只会停在
  "There was an error while loading. Please reload this page."，到不了下载按钮。
  因此应用内所有打开/下载动作一律指向 APK 直链：
  `https://v6.gh-proxy.com/github.com/ike-lee-820/GeckoDownloader/releases/download/26.11.3/GeckoDownloader-26.11.3-arm64-v8a.apk`
- **节点候选**：主节点 `v6.gh-proxy.com`，备用节点 `v6.gh-proxy.org`（官方国内 / IPv6 线路），
  最后回退 `github.com` 直连。下载器逐个探测，挑真正返回文件而非 HTML 错误页的节点；
  代理不支持 `Range` 时退化为单线程，并校验文件大小 + ZIP 内含 `AndroidManifest.xml` 才允许安装。
- **三选一**：应用内更新（最多 128 并发分段，实时显示字节 / 总量 / 百分比 / 线程数，下完调系统安装器）／
  应用外更新（先探测可用节点，再用 `ACTION_VIEW` 把 **APK 直链**交给系统默认浏览器，打开即下载）／取消。
- 版本按 `YY.MM.N` 数字比较，与自动发版生成的 tag 一致。

## 状态

`26.11.0` 起由 CI 自动构建发布。界面默认中文；内置浏览器改为系统 WebView；
单任务线程上限 128、并发任务上限 32、进度 50ms 一帧；
更新链接统一改为 APK 文件直链（不再跳代理后的 Release 网页），并加入 `.org` 备用节点自动择优。
