# Offline geographic rules

PingClash bundles unchanged public MetaCubeX/meta-rules-dat and DB-IP files for Mihomo native
GEOSITE/GEOIP/ASN matching. These are public classification lists, not collected user
traffic. No rule-data HTTP request, telemetry or updater is started on the phone.

Source/build recipes and upstream attribution: https://github.com/MetaCubeX/meta-rules-dat
Release source: https://github.com/MetaCubeX/meta-rules-dat/releases/tag/latest
Snapshot downloaded 2026-10-27; the moving release URL is not used as an integrity identity.

| APK asset | Bytes | SHA-256 |
|---|---:|---|
| GeoIP.dat | 16570425 | a6271d3c96361d96156e6d3f7cf1550ef21b00d2f951deb772f464db91e89ac5 |
| GeoSite.dat | 4352810 | c57333596b44aa061e84c3c224e97a4475c759a5e3baab1fcda002e48b4dc313 |
| ASN.mmdb | 9566363 | 4384bfcfb594391bc4e0ab37751f44661eaf7cadbeee28a90a6b82dc935fec4c |

ASN provenance (added in preview.21, local download date 2026-10-28):
[DB-IP IP to ASN Lite, October 2026](https://db-ip.com/db/download/ip-to-asn-lite).
The unmodified database is stored under Mihomo's fixed `ASN.mmdb` name.
Download: `https://download.db-ip.com/free/dbip-asn-lite-2026-10.mmdb.gz`.
Compressed bytes: 5186202; compressed SHA-256:
`6cdde02baa8c1bd619ab84c2e505ecd91d45288b5a87faadf1b7c41525a17a94`.
The decoded SHA-1 matches the publisher's
`bc76615cd8f99b7b3a878803b1c84cbeda65769a`; decoded SHA-256 is pinned above.
MMDB type: `DBIP-ASN-Lite (compat=GeoLite2-ASN)`, supported by the pinned Mihomo
reader. The free monthly dataset has reduced coverage/accuracy and may lag network
changes. It is not a real-time reputation or ownership guarantee.

Copyright DB-IP.com, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
The license text and attribution are in APK `assets/licenses/` and NOTICE.md.
No changes were made to database records. The initial investigation downloaded
MetaCubeX's GeoLite2-ASN for comparison; that file is **not** bundled in this APK.

The upstream repository distributes under GPL-3.0 and links its source datasets
and build inputs; preserve those attributions. GPL text and dependency notices
are in the APK; the unchanged files and this provenance are in the source tree.

The app atomically copies only these three fixed asset names into its private ruledata
folder. Since preview.24 a shared background task prepares these files; Go
performs the sole full SHA-256 pass, avoiding duplicate main-thread reads. A
corrupt private copy is repaired from fixed assets and verified again. Go accepts one verified absolute data
folder per process; relative paths, symlinks to data files, changed files and
subscription-controlled file names are rejected. Native loaders are called only
when the verified files remain present. Geo-data download URLs stay empty.

Known category names and attributes are handled by Mihomo's own matcher. An
unknown category fails explicitly with the rule line number; it is not ignored
or converted to direct traffic. Rule targets are bounded at 64, rules at 2000,
and graph work remains bounded. Regex prefers RE2; native-compatible lookaround
uses timed regexp2. Process/UID and bounded logical rules arrived in preview.20;
preview.21 adds native IP-ASN/SRC-IP-ASN, including IPv6 and source parameters.
Database filenames/URLs cannot be changed by a subscription. The native boundary checks
the pinned bytes before the native reader is initialized, and ASN/MMDB download
URLs remain empty. Missing/corrupt database regressions run in fresh child
processes, preventing already-initialized native state from masking the failure.

After upgrading preview.20, update the subscription or choose Reparse saved
configuration to recompute cached skip counts without sharing the subscription.
DSCP metadata and user-authorized local file providers remain outside the
implemented scope. HTTP MRS arrived in preview.22; preview.24 adds named-proxy
downloads and HTTPS-backed file declarations, without opening arbitrary paths.
