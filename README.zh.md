# AirPlay for Android

🌐 [Türkçe](README.tr.md) · [English](README.md) · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · [Português](README.pt.md) · [Italiano](README.it.md) · [Русский](README.ru.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · **简体中文** · [العربية](README.ar.md)

把 Android 设备（Android TV、手机、平板、机顶盒）变成 iPhone 和 iPad 的 **AirPlay 接收器**：屏幕镜像、视频、照片、纯音频投送（音乐、Spotify 等），以及无需安装任何东西即可从 Safari 发送视频。

应用启动后会显示二维码。用 iPhone 相机扫描即可打开网页遥控器；也可以在控制中心 > 屏幕镜像或 AirPlay 列表中选择 **TV Alici**（可改名）。

## 功能

- **屏幕镜像**（H.264 视频、AAC-ELD 音频），支持音画同步与缓冲设置、缩放以及适应/填充模式。
- **视频 AirPlay**（MP4 和 HLS）与**照片 AirPlay**。
- **纯音频 AirPlay**（音乐、Spotify、播客），带有显示封面和歌曲信息的全屏*正在播放*界面。
- **网页遥控器** `http://<设备IP>:8080`（PIN 保护）：无需安装即可从 Safari 发送视频、多个视频排队、播放/暂停/快进快退、音量、在电视上显示消息、播放视频链接、添加到主屏幕作为应用。
- **电视屏幕菜单** — 按 **MENU** 键（没有 MENU 键的遥控器，如 Mi TV Stick，按 **OK**）：开机自启、睡眠定时器、音画同步、缓冲、缩放、画面适配、时钟、统计、受信任的设备、检查更新。
- **访问控制：**新设备需要在电视上批准（允许一次 / 始终允许 / 拒绝）。
- **应用内更新**：通过包名、版本和签名证书校验。
- **12 种语言**（跟随设备/浏览器语言）：English、Türkçe、Español、Français、Deutsch、Português、Italiano、Русский、日本語、한국어、简体中文、العربية。

## 快速开始

1. 用 GitHub Actions 构建 APK（推送到 `main`，然后下载 `tv-alici-debug-apk` 构建产物），或从 [Releases](https://github.com/ozsinyol/tv-alici/releases) 下载已签名的 APK。
2. 安装到 Android 设备（`adb install app-debug.apk`，或在手机/平板上打开 APK）。两台设备必须在同一个 Wi-Fi 网络中。
3. 打开应用，用 iPhone 相机扫描二维码，或在 AirPlay / 屏幕镜像中选择“TV Alici”。

## 限制

- 仅支持经典 AirPlay（1）和屏幕镜像协议——不支持 AirPlay 2（多房间、缓冲音频、Siri/家庭）。
- 受 FairPlay 保护的视频（YouTube、Netflix、Apple TV+ 等）无法播放，DRM 内容在镜像时显示为黑屏。
- 不支持照片幻灯片；屏幕镜像仅支持 H.264。
- AirPlay 是 Apple 的专有协议，没有官方授权；本项目仅供个人/实验用途。

## 安全与更新

网页遥控器需要电视上显示的 PIN（通过请求头发送；连续输错五次会将该设备锁定 5 分钟）。发布版 APK 使用保存在 GitHub Secrets 中的私钥签名，应用内更新程序只有在包名、版本和证书 SHA-256 全部一致时才会安装 APK。

## 完整文档

完整文档（英语）见 [README.md](README.md)：构建与签名、受信任的设备、同步调整、网页 API、手动更新、调试。要为应用添加语言，请把 `app/src/main/res/values/strings.xml` 复制到 `values-<代码>/`，并在 `VideoServer.kt` 的 `DIL` 表中添加一个块。

## 许可证

GNU GPL v3 — 见 [LICENSE](LICENSE)。本项目使用了 [UxPlay](https://github.com/FDH2/UxPlay) 的 `playfair` 代码（GPL v3）和 Apple 的开源 ALAC 解码器（Apple Public Source License 2.0，见 `app/src/main/cpp/alac/APPLE_LICENSE.txt`）。
