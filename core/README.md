# Mihomo Android bridge

PingClash embeds Mihomo v1.19.32 with the module versions locked in go.mod and go.sum.
The Android bridge provides rootless TUN forwarding, policy selection, DNS, native
routing, node URL tests, connection information and local diagnostics.

Android owns the VPN interface and socket protection. Configuration and Provider
paths are resolved within application-controlled storage. Rule data is bundled
with the application and verified before use.

To rebuild the Android bridge, use tools/bootstrap.ps1 and tools/build-native.ps1
from the project root. The generated AAR supports arm64-v8a and x86_64. See NOTICE.md,
LICENSE, go.mod and go.sum for upstream dependencies and license information.
