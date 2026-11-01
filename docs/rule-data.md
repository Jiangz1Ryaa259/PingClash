# Bundled geographic rule data

PingClash includes public geographic and ASN classification data for Mihomo rules.
These files are public datasets; they do not contain collected user traffic.

## Sources and integrity

MetaCubeX/meta-rules-dat: https://github.com/MetaCubeX/meta-rules-dat

The repository provides the build inputs and source attribution for GeoIP.dat and
GeoSite.dat under its GPL-3.0 distribution. The bundled snapshot was obtained on
2026-10-27. Integrity is pinned to the following file hashes rather than a moving URL.

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| GeoIP.dat | 16570425 | a6271d3c96361d96156e6d3f7cf1550ef21b00d2f951deb772f464db91e89ac5 |
| GeoSite.dat | 4352810 | c57333596b44aa061e84c3c224e97a4475c759a5e3baab1fcda002e48b4dc313 |
| ASN.mmdb | 9566363 | 4384bfcfb594391bc4e0ab37751f44661eaf7cadbeee28a90a6b82dc935fec4c |

ASN.mmdb is the unchanged DB-IP IP to ASN Lite database, October 2026.
Copyright DB-IP.com, licensed under CC BY 4.0:
https://db-ip.com/db/download/ip-to-asn-lite
https://creativecommons.org/licenses/by/4.0/

Source archive: https://download.db-ip.com/free/dbip-asn-lite-2026-10.mmdb.gz
Compressed SHA-256: 6cdde02baa8c1bd619ab84c2e505ecd91d45288b5a87faadf1b7c41525a17a94.
The database is stored under Mihomo's ASN.mmdb filename without changing records.
This Lite dataset is a periodic classification snapshot, rather than a real-time
ownership or reputation service. The source attribution and license are also
included in the application notices.

## Runtime use

The application copies the three fixed files into private storage and verifies
them before native loading. Subscription-controlled database paths and download
URLs are not used. No telemetry or dataset updater is started by this component.
