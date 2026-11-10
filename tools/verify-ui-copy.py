"""Keep retired tutorials/command stubs out of application resources and APKs."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
RETIRED = {
    "message_ec5dbb4b87", "message_dd950864ba", "ui_site_latency_note",
    "ui_session_duration_note", "ui_lanyard_hint", "ui_dynamic_color_detail",
    "ui_reduce_effects_detail", "ui_transparency_detail", "latency_tcp_note",
    "message_58acd3db15", "message_a03eefe8ff", "message_a1ebf712be",
    "message_79c06d301b", "message_e0693379bd", "message_2b379681df",
    "message_3731779ddd", "message_518eae8255", "message_2d69df5d6b",
    "message_8967970252", "message_4fadf7a9a5", "message_4dd958cc24",
    "message_b34cdfde80", "message_6c8b8a3572", "message_7ae041d629",
    "terminal_raw_output", "message_5103a81309", "message_a12140eb61",
    "ui_site_route_proxy", "ui_site_route_direct", "delay_queued", "message_3538e7b1a7",
}
MARKERS = (
    "让连接从这里开始", "让连接问题，", "0.2 · 辅助探测",
    "Connect from here", "Make connection issues",
    "diag node", "diag dns ", "logs --recent",
    "控制台只执行以上诊断命令", "使用所选代理的 Mihomo URLTest（HEAD），最多同时测试",
    "从本次 VPN 首次建立开始计时，切网与等待重连不归零",
)


def main():
    count = 0
    for folder in ("values", "values-zh-rCN", "values-ja"):
        for path in (ROOT / "app/src/main/res" / folder).glob("*.xml"):
            for entry in ET.parse(path).getroot():
                if entry.attrib.get("name") in RETIRED:
                    raise RuntimeError("Retired tutorial resource is packaged")
                text = "".join(entry.itertext())
                if any(marker in text for marker in MARKERS):
                    raise RuntimeError("Retired command/tutorial text is packaged")
                count += entry.tag in ("string", "plurals")
    guide = ROOT / "docs/user-guide.md"
    if not guide.is_file() or guide.stat().st_size < 10000:
        raise RuntimeError("Detailed external user guide is missing")
    for name in sys.argv[1:]:
        with zipfile.ZipFile(name) as apk:
            for entry in apk.infolist():
                data = apk.read(entry)
                if any(marker.encode(encoding) in data for marker in MARKERS for encoding in ("utf-8", "utf-16le")):
                    raise RuntimeError("APK contains retired command/tutorial copy")
    print(f"UI copy: {count} resource entries checked; retired tutorials absent; external guide present")


if __name__ == "__main__":
    main()
