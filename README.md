<div align="center">

<img src="docs/logo.svg" width="104" alt="PingClash logo">

<h1>PingClash</h1>

<p>A privacy-focused Android proxy client powered by Mihomo, with connection diagnostics and a built-in terminal.</p>

<p>
<a href="https://github.com/Jiangz1Ryaa259/PingClash/releases/latest">Download</a> ·
<a href="docs/user-guide.md">使用指南</a> ·
<a href="docs/user-guide.en.md">User guide</a> ·
<a href="docs/user-guide.ja.md">使い方ガイド</a>
</p>

<p>
<a href="https://github.com/Jiangz1Ryaa259/PingClash/actions/workflows/ci.yml"><img src="https://github.com/Jiangz1Ryaa259/PingClash/actions/workflows/ci.yml/badge.svg?branch=main" alt="Build and verify"></a>
<a href="https://github.com/Jiangz1Ryaa259/PingClash/releases/latest"><img src="https://img.shields.io/github/v/release/Jiangz1Ryaa259/PingClash?label=release&amp;color=ff7043" alt="Latest release"></a>
<img src="https://img.shields.io/badge/platform-Android%2010%2B%20%7C%20arm64--v8a-5c6bc0" alt="Platform: Android 10+ | arm64-v8a">
<a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="License: GPL-3.0"></a>
</p>

</div>

## Features

- **Subscriptions and profiles** — import from HTTPS URLs, clipboard, configuration text, files, provider ZIP bundles and QR images, including large subscriptions (up to 5,000 nodes, 512 policy groups and 50,000 rules). Manage profile names, User-Agent, quota and expiry in one place.
- **Native Mihomo routing** — preserve subscription policy groups and nested selections; switch between Rule, Global and Direct modes, configure application routing, and manage local rules. Profile and node changes apply while the VPN stays connected.
- **Service routing** — choose Proxy, Direct or Default for popular services by country and region (U.S., U.K., Japan, South Korea, China – Hong Kong, China – Taiwan, China – Mainland) and for AI, games, and work and social apps. Choices apply to every profile, survive subscription updates, can each use a chosen policy group, take effect while connected and resolve offline against the built-in rule data; optional ad and tracker blocking.
- **Connection diagnostics** — compare nodes using Mihomo URL tests, inspect independent TCP latency, check whether your current network reaches common websites, check any domain or IP directly over the phone's own network (also while the VPN is on), and review connection routes, domains and rule types.
- **Live information** — view upload/download speed, traffic, active connections, session duration, service state and memory information, plus your current IP: country or region, network, ASN and, while connected, the route the rules choose.
- **A fluid interface** — a draggable hanging connection card, a mechanical mode wheel with inertia, detents and haptic clicks, animated ocean/cloud backgrounds, customizable solid colors or your own picture, grouped settings, glass navigation (a collapsible glass sidebar in landscape and on tablets), and light/dark themes.
- **Automation and shortcuts** — a Quick Settings tile and a home-screen widget, optional connect on boot, scheduled subscription updates, connect-by-network rules for trusted Wi-Fi, and settings backup and restore.
- **A standard terminal** — Android `sh` with a PTY, normal keyboard input, colors, pipes, redirection, Ctrl+C, copying and sharing.
- **Local privacy** — Android Keystore and AES-GCM encrypted configuration storage, local diagnostic and crash reports, no telemetry and no advertisements.
- **Chinese, English and Japanese** — application language selection and Android system app-language integration.

## Start using PingClash

1. Install the APK from [Releases](https://github.com/Jiangz1Ryaa259/PingClash/releases/latest). The first launch opens a short setup guide: choose a language, read the user agreement (also under Settings → About), then pick a few common service choices and optional blocking; every step after the agreement can be skipped.
2. Open **Profiles**, tap **+**, and import your subscription or configuration.
3. Select the profile, then choose a policy group or node under **Proxies**.
4. On **Home**, turn the mode wheel (or tap its left or right side) to **Rule**, **Global** or **Direct**, tap the hanging card and approve Android's VPN request.

PingClash uses your own proxy service and configurations. Root access is not required. The [user guide](docs/user-guide.en.md) ([中文](docs/user-guide.md)) explains imports, routing, diagnostics, the terminal, automation, backup and background operation.

## 项目介绍

PingClash 是基于 Mihomo 的 Android 代理客户端。配置、代理与设置各自独立，首页集中提供连接开关、出站模式与网站连通性检查。订阅保留原有策略组和成员关系，支持节点切换、并行延迟检查及连接诊断；切换配置或节点时，VPN 保持连接。「服务分流」按国家和地区为常用服务选择代理、直连或默认，适用于所有配置并在连接时即时生效；首次打开时的设置向导可快速完成语言、协议、配置、服务分流与广告拦截。首页显示当前 IP 的国家/地区与网络；快捷设置磁贴、桌面小组件、开机连接、订阅定时更新、按 Wi-Fi 自动连接以及设置备份与恢复，覆盖日常使用。

配置在设备本地加密保存。应用不内置订阅或账号，不收集遥测，不展示广告；诊断报告和崩溃记录由用户主动查看与分享。终端运行标准 Android shell，可执行设备已有的命令。

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
