#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Check every translation against the English strings.

Machines cannot judge wording, but they can catch the mistakes that break a screen: a missing or
leftover key, a placeholder that differs, a plural without the forms its language needs, an
unescaped apostrophe, or text that was never translated.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"

# Plural forms Android asks for in each language (CLDR); zero/two etc. are not used by these.
PLURALS = {
    "zh-rCN": {"other"},
    "ja": {"other"},
    "ko": {"other"},
    "ru": {"one", "few", "many", "other"},
}
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")
# Text that is the same in every language: names, protocols, units and bare symbols.
WORD = re.compile(r"[A-Za-z]{4,}")
SAME_EVERYWHERE = {
    "PingClash", "Android", "Mihomo", "AnyTLS", "Wi-Fi", "HTTPS", "HTTP", "QUIC", "User-Agent",
    "GeoIP", "GeoSite", "Clash", "Meta", "GitHub", "Cloudflare", "Google", "Telegram", "YouTube",
    "Netflix", "OpenAI", "Hysteria", "Shadowsocks", "Trojan", "VLESS", "VMess", "SOCKS", "WireGuard",
    "English", "TUIC", "Mieru", "Snell", "Reality", "Sniffer", "Fake", "fake", "IPv4", "IPv6",
}


# Wording kept out of each language so one thing has one name, and text from another language does
# not slip in. These only catch variants of the glossary; wording still needs a native reader.
# A plain ASCII space between Japanese and Latin text also counts: "VPNを切断", not "VPN を切断".
AVOID = {
    "ja": re.compile(
        r"お客様|クリーンアップ|根拠|断定|入口|補助|"
        r"[\u3040-\u30ff\u4e00-\u9fff] (?=[A-Za-z0-9%])|(?<=[A-Za-z0-9]) (?=[\u3040-\u30ff\u4e00-\u9fff])|"
        r"购读|購読|代理|節点|节点|连接|规则|设置|订阅|配置|延迟|レイテンシ|"
        r"サーバ(?!ー)|ユーザ(?!ー)|プロキシー|パラメータ(?!ー)|フィルタ(?!ー)|バッテリ(?!ー)|スマホ"
    ),
}


# Each language's guide must keep the same chapters and sections as the English one.
GUIDES = {"ja": "user-guide.ja.md", "zh-rCN": "user-guide.md"}


def guide_outline(path):
    lines = path.read_text(encoding="utf-8").splitlines()
    return [len(line) - len(line.lstrip("#")) for line in lines if re.match(r"#{1,3} ", line)]


def line_breaks(text):
    return text.count("\\n")


def agreement_sections(text):
    return re.findall(r"(?:^|\\n\\n)(\d+)\. ", text)


def load(folder):
    """Every translatable string and plural in a values folder, by name."""
    strings, plurals = {}, {}
    for path in sorted(folder.glob("*.xml")):
        root = ET.parse(path).getroot()
        for node in root:
            if node.get("translatable") == "false":
                continue
            name = node.get("name")
            if node.tag == "string":
                strings[name] = "".join(node.itertext())
            elif node.tag == "plurals":
                plurals[name] = {i.get("quantity"): "".join(i.itertext()) for i in node}
    return strings, plurals


def placeholders(text):
    return sorted(m.group(0) for m in PLACEHOLDER.finditer(text) if m.group(0) != "%%")


def looks_untranslated(text, english):
    if text != english:
        return False
    words = [w for w in WORD.findall(text) if w not in SAME_EVERYWHERE and not w.isupper()]
    return bool(words)


def check(folder, code, base, base_plurals):
    strings, plurals = load(folder)
    errors = []
    errors += [f"missing: {n}" for n in sorted(base.keys() - strings.keys())]
    errors += [f"not in English: {n}" for n in sorted(strings.keys() - base.keys())]
    errors += [f"missing plural: {n}" for n in sorted(base_plurals.keys() - plurals.keys())]
    errors += [f"plural not in English: {n}" for n in sorted(plurals.keys() - base_plurals.keys())]
    for name in base.keys() & strings.keys():
        text = strings[name]
        if placeholders(text) != placeholders(base[name]):
            errors.append(f"placeholders differ: {name}: {text!r}")
        if not text.strip():
            errors.append(f"empty: {name}")
        if looks_untranslated(text, base[name]):
            errors.append(f"not translated: {name}: {text!r}")
        if line_breaks(text) != line_breaks(base[name]):
            errors.append(f"line breaks differ: {name}")
        if code in AVOID and (found := AVOID[code].search(text)):
            errors.append(f"avoid {found.group(0)!r}: {name}")
    if "agreement_text" in strings:
        if agreement_sections(strings["agreement_text"]) != agreement_sections(base["agreement_text"]):
            errors.append("agreement sections differ from English")
    forms = PLURALS.get(code)
    for name in base_plurals.keys() & plurals.keys():
        want = placeholders(base_plurals[name]["other"])
        if forms is not None and set(plurals[name]) != forms:
            errors.append(f"plural forms {sorted(plurals[name])}, need {sorted(forms)}: {name}")
        for quantity, text in plurals[name].items():
            if placeholders(text) != want:
                errors.append(f"placeholders differ: {name}[{quantity}]: {text!r}")
    if code in GUIDES:
        docs = RES.parents[2].parent / "docs"
        if guide_outline(docs / GUIDES[code]) != guide_outline(docs / "user-guide.en.md"):
            errors.append(f"guide outline differs from English: {GUIDES[code]}")
    for path in sorted(folder.glob("*.xml")):
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if "<string" in line and re.search(r"(?<!\\)'", line.split(">", 1)[-1]):
                if '>"' not in line:
                    errors.append(f"{path.name}:{number}: unescaped apostrophe")
    return errors


def main():
    base, base_plurals = load(RES / "values")
    failed = False
    folders = sorted(p for p in RES.glob("values-*") if any(p.glob("*.xml")))
    for folder in folders:
        code = folder.name.removeprefix("values-")
        if code not in PLURALS:
            continue
        errors = check(folder, code, base, base_plurals)
        print(f"{code}: {len(errors)} problem(s)")
        for error in errors:
            print("  " + error)
        failed = failed or bool(errors)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
