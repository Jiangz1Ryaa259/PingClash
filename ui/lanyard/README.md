# Offline Lanyard renderer

`reference/Lanyard.jsx` is the user-supplied React Bits component. Its LF-normalized
SHA-256 and preserved geometry/material/physics sections are recorded in
`reference.json`. It retains its MIT + Commons Clause license, reproduced in
`LICENSE-ReactBits.md`; do not relabel this component as GPL.

Node 24.14.0:

```sh
npm ci --ignore-scripts --no-fund --no-audit
npm run build
npm test
```

`adapt.mjs` preserves the mesh construction, shaders, normal textures, environment
and physics. Its narrow integration changes bind native long-press displacement,
VPN face state and lifecycle/reduced-motion state, and bound the image cache.
The host drag is not clamped to the former small slot; the adapter extends and
restores the strap rest length, then resumes frame scheduling after backgrounding.
`src/entry.jsx` supplies local card-face artwork and viewport framing. The bundle
and its license notices are committed under `app/src/main/assets/`; Gradle can
build those committed assets without Node. After changing renderer sources,
rebuild and commit the emitted assets and `bundle-integrity.json` together.
CI reproduces the build, checks the diff and tests source fidelity/integrity.

The WebView loads three fixed app assets, blocks network and file/content access,
and exposes no JavaScript-to-native interface. It receives fixed UI text, phase
and viewport/drag geometry only. Native centerX keeps horizontal/column layouts aligned
with their clickable control, including system side insets. Native Compose owns VPN actions, input and accessibility.
Rendering pauses off-home, in background and on protected pages. WebGL-unavailable
devices use the native card fallback; emulator evidence is not OEM validation.
