# PingClash source and dependency notices

PingClash application and diagnostic code is provided under GPL-3.0-only; see `LICENSE`.
The separately bundled Lanyard renderer contains React Bits source under its
MIT + Commons Clause license (not GPL); see `ui/lanyard/LICENSE-ReactBits.md`.
Its original geometry, PBR materials and physics are preserved in
`ui/lanyard/reference/Lanyard.jsx`; Android host adaptations are in `adapt.mjs`.
React, React DOM and Three.js retain their MIT licenses. All four notice texts
are also packaged in `assets/licenses/lanyard/`. No remote renderer assets load.
The embedded core uses MetaCubeX/mihomo v1.19.32, together with the exact module graph
recorded in `core/go.mod` and `core/go.sum`. Upstream source and notices are preserved
in the source archive's `core/vendor` tree. Dependency files retain their own licenses.

Upstream core: https://github.com/MetaCubeX/mihomo/tree/v1.19.32

The application name is PingClash. It is not an official MetaCubeX application.
The upstream README requires unaffiliated downstream project names not to include
the word `mihomo`; references to the embedded engine identify the dependency.

The current beta proxy page layout and selection interactions are adapted from the user-provided
FlClash reference and the upstream project's GPL-3.0 code structure, implemented here
in Kotlin/Compose. Upstream: chen08209/FlClash, commit
8d4f9744948751f1273fe871e1d05276fac09b0e, lib/views/proxies/{proxies,tab,group_tabs,card,common}.dart.
https://github.com/chen08209/FlClash/tree/8d4f9744948751f1273fe871e1d05276fac09b0e/lib/views/proxies
No upstream fonts, screenshots or image assets are included in the application.
AndroidX, Kotlin and kotlinx.coroutines are used under their respective Apache-2.0
notices. Go and golang.org/x/mobile retain their BSD notices. Gradle wrapper files
retain the Apache-2.0 license in `gradle/wrapper/LICENSE-Gradle.txt`.
VPN state morphing uses AndroidX graphics-shapes 1.0.1, Apache-2.0.
Navigation blur uses Haze and haze-materials 1.6.8, copyright Christopher Banes
and the Haze project contributors, Apache-2.0: https://github.com/chrisbanes/haze/tree/1.6.8.

The APK includes license texts and module notices in its assets. Its accompanying
source archive includes the project, build scripts, pinned module files, tests,
and vendored Go dependency sources. Android build dependencies are resolved from
the pinned Gradle declarations through Google's repository and Maven Central.

Standard terminal: Termux terminal-emulator / terminal-view v0.118.3. Vendored sources and local modifications are included; see terminal-emulator/LICENSE.md, terminal-view/LICENSE.md and their Apache-2.0 exceptions.

The beta.7 glass backdrop direction references FlClash v0.9.2 (GPL-3.0),
`lib/ui/liquid_glass.dart` and `shaders/liquid_glass.frag`:
https://github.com/chen08209/FlClash/blob/v0.9.2/lib/ui/liquid_glass.dart
https://github.com/chen08209/FlClash/blob/v0.9.2/shaders/liquid_glass.frag
PingClash implements a Compose/Haze backdrop with AGSL capsule refraction;
no Flutter runtime or upstream visual assets are copied.

The beta.8 compact website list and expanded panel direction reference FlClash
v0.9.2 (GPL-3.0), lib/common/service_probe.dart, lib/providers/service_status.dart,
and lib/views/dashboard/widgets/service_status.dart:
https://github.com/chen08209/FlClash/tree/v0.9.2/lib/views/dashboard/widgets
PingClash uses its existing Mihomo URLTest bridge for latency only; it does not
copy the service-unlock checkers or collect response bodies/IP/region data.

## Liquid Glass navigation

Backdrop (AndroidLiquidGlass) 1.0.4, copyright 2025 Kyant, Apache-2.0.
Pinned source: https://github.com/Kyant0/AndroidLiquidGlass/tree/cf0f35d8c597c06ea8bd42ff7a48821678d74669/backdrop
The actual source module is included under `third-party/backdrop`, with LICENSE,
upstream hashes and documented Kotlin/Compose compatibility shims. Its optical
shaders, layer capture, highlights and shadows are preserved. `LiquidBottomTabs`
is the reference for the app's independently adapted native selection interactions.
No upstream demo APK, artwork or fonts are bundled. Haze 1.6.8 remains the API 31/32
blur fallback. Android 13+ uses Backdrop; reduced transparency and protected pages
use a solid material with no background capture.

## ZXing Core

QR image decoding uses ZXing Core 3.5.3 (Apache-2.0), Copyright ZXing authors.
Source: https://github.com/zxing/zxing/tree/zxing-3.5.3/core
License: https://www.apache.org/licenses/LICENSE-2.0
No Google Play services are required.

## MetaCubeX rule data

GeoIP.dat and GeoSite.dat are unchanged, SHA-256-pinned MetaCubeX/meta-rules-dat release assets.
Repository and build/source attribution: https://github.com/MetaCubeX/meta-rules-dat
GPL-3.0 (repository); see GPL-3.0.txt and docs/rule-data.md for exact asset provenance.

ASN.mmdb is the unchanged October 2026 DB-IP IP to ASN Lite database,
copyright DB-IP.com, Creative Commons Attribution 4.0 International (CC BY 4.0).
Source: https://db-ip.com/db/download/ip-to-asn-lite
License: https://creativecommons.org/licenses/by/4.0/
This free classification database has reduced coverage and accuracy. It is not
user traffic data. Exact download provenance and hashes: docs/rule-data.md.
