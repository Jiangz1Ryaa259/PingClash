# PingClash

[![Build and verify](https://github.com/Jiangz1Ryaa259/PingClash/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Jiangz1Ryaa259/PingClash/actions/workflows/ci.yml)

A privacy-focused Android proxy client powered by Mihomo, with connection diagnostics and a built-in terminal.

[Download](https://github.com/Jiangz1Ryaa259/PingClash/releases/latest) · [使用指南 / User guide](docs/user-guide.md)

**0.1.0-beta · Android 10+ · Rootless VPN · Jetpack Compose**

## Features

- **Subscriptions and profiles** — import from HTTPS URLs, clipboard, configuration text, files, provider ZIP bundles and QR images. Manage profile names, User-Agent, quota and expiry in one place.
- **Native Mihomo routing** — preserve subscription policy groups and nested selections; switch between Rule, Global and Direct modes, configure application routing, and manage local rules.
- **Connection diagnostics** — compare nodes using Mihomo URL tests, inspect independent TCP latency, check website connectivity, and review connection routes and rule types.
- **Live information** — view upload/download speed, traffic, active connections, session duration, service state and memory information.
- **A fluid interface** — a draggable hanging connection card, animated ocean/cloud backgrounds, customizable solid colors, glass navigation, and light/dark themes.
- **A standard terminal** — Android `sh` with a PTY, normal keyboard input, colors, pipes, redirection, Ctrl+C, copying and sharing.
- **Local privacy** — Android Keystore and AES-GCM encrypted configuration storage, local diagnostic reports, no telemetry and no advertisements.
- **Chinese and English** — application language selection and Android system app-language integration.

## Start using PingClash

1. Install the APK from [Releases](https://github.com/Jiangz1Ryaa259/PingClash/releases/latest).
2. Open **Profiles**, tap **+**, and import your subscription or configuration.
3. Select the profile, then choose a policy group or node under **Proxies**.
4. On **Home**, choose **Rule**, **Global** or **Direct**, tap the hanging card and approve Android's VPN request.

PingClash uses your own proxy service and configurations. Root access is not required. The [user guide](docs/user-guide.md) explains imports, routing, diagnostics, the terminal and background operation.

## 项目介绍

PingClash 是基于 Mihomo 的 Android 代理客户端。配置、代理与设置各自独立，首页集中提供连接开关、出站模式与网站连通性检查。订阅保留原有策略组和成员关系，支持节点切换、并行延迟检查及连接诊断。

配置在设备本地加密保存。应用不内置订阅或账号，不收集遥测，不展示广告；诊断报告由用户主动预览和分享。终端运行标准 Android shell，可执行设备已有的命令。

## Build

Use JDK 21, PowerShell 7, Node.js and the pinned Android/Go toolchains:

```powershell
./tools/bootstrap.ps1 -AcceptSdkLicense
./tools/build-android.ps1
```

The scripts install local build tools under `.tools/`, compile the native core and build the Android application. For optimized APKs, run:

```powershell
./gradlew.bat -PpingclashSplitApks=true :app:assembleRelease
```

Sign release APKs with your own private signing key. Local toolchains, signing material, device configurations and build output are excluded from Git.

## License

Application code is licensed under GPL-3.0-only. Third-party components retain their own licenses; see [LICENSE](LICENSE) and [NOTICE](NOTICE.md). The offline card renderer includes React Bits material under MIT + Commons Clause. PingClash is an independent project.
