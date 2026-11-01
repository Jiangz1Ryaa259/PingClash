# Core bridge notices

The Go bridge in `mobile/` is new PingClash code licensed under GPL-3.0-only.
It links to `github.com/metacubex/mihomo` at **v1.19.32**, which is licensed under GPL-3.0.
The corresponding GPL text is retained in `core/LICENSE`.

Upstream: https://github.com/MetaCubeX/mihomo/tree/v1.19.32

Pinned module checksums:

```text
github.com/metacubex/mihomo v1.19.32 h1:uD7ZC3P77isWD554NNvtee65L+99+/C5hyc+Lk8rVEk=
github.com/metacubex/mihomo v1.19.32/go.mod h1:d7xWi5iEktkC9PqXkD/Vo4AEcb0Q92XikfLoySUDWls=
```

No FlClash, CMFA or Hiddify implementation files were copied into this bridge.
The upstream module contains additional dependencies under their respective licenses;
an Android distribution containing the native library must retain their applicable
notices and provide the corresponding source/build information required by its licenses.
This Go package is bundled in the Android APK from 0.2.0-dev onward. The project
root LICENSE and NOTICE.md describe the application and source distribution.
The source archive includes vendored Go dependency sources and their notices;
tools/collect-notices.ps1 collects Android module license texts into APK assets.
