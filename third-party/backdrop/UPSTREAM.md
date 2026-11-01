# Backdrop source dependency

Source: Kyant0/AndroidLiquidGlass, tag 1.0.4, commit
`cf0f35d8c597c06ea8bd42ff7a48821678d74669`.
https://github.com/Kyant0/AndroidLiquidGlass/tree/1.0.4/backdrop
Copyright 2025 Kyant. Apache-2.0; see LICENSE.

This is the actual source dependency, built against the app's pinned Compose
platform. No upstream catalog APK, images, fonts or user examples are bundled.
Original source hashes are recorded in upstream-hashes.json.

Local compatibility changes: LayerRecorder takes its DelegatableNode explicitly
instead of using Kotlin context parameters. Two callers pass their existing node.
The rendering algorithms, optics shaders, layer recording, blur, highlights and
shadows are preserved. The module uses the existing Android/Compose toolchain.
App code controls source lifetime and privacy/legacy fallback above this module.

InverseLayerScope's blendMode/colorFilter are local fields rather than overrides,
because they joined GraphicsLayerScope after the pinned Compose 1.8 API. The
values and inverse-transform behavior are preserved. Dependencies are aligned to
already-verified app versions; no runtime permission or network capability is added.
