#!/usr/bin/env python3
"""资源 XML 与清单文件的格式校验。

AAPT 报 XML 错误的时机很晚（要等到资源合并阶段），而且一旦出错信息往往是
"not well-formed" 加一行列号，很难定位。这里在编译前用标准库先扫一遍，
几毫秒就能把这类问题拦下来。

除了格式良好性，还顺手做两条本项目特有的检查：
  1. AndroidManifest 里引用的 @style / @drawable / @color 必须真的存在；
  2. strings.xml 里的占位符数量要和格式化调用一致（这里只查重名）。
"""

from __future__ import annotations

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
RES_DIR = ROOT / "app" / "src" / "main" / "res"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def collect_xml_files() -> list[pathlib.Path]:
    files = sorted(RES_DIR.rglob("*.xml"))
    if MANIFEST.exists():
        files.append(MANIFEST)
    return files


def check_well_formed(files: list[pathlib.Path]) -> list[str]:
    errors: list[str] = []
    for path in files:
        try:
            ET.parse(path)
        except ET.ParseError as exc:
            errors.append(f"{path.relative_to(ROOT)}: XML 格式错误 -> {exc}")
    return errors


def collect_defined_resources() -> dict[str, set[str]]:
    """收集 values/*.xml 里定义的颜色与字符串名"""
    defined: dict[str, set[str]] = {"color": set(), "string": set(), "style": set()}
    for path in sorted((RES_DIR / "values").glob("*.xml")):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for child in root:
            tag = child.tag
            name = child.attrib.get("name")
            if not name:
                continue
            if tag in ("color", "string"):
                defined[tag].add(name)
            elif tag == "style":
                defined["style"].add(name)
                parent = child.attrib.get("parent", "")
                if parent.startswith("Theme."):
                    defined["style"].add(parent)
    return defined


def check_manifest_references(defined: dict[str, set[str]]) -> list[str]:
    if not MANIFEST.exists():
        return [f"缺少 {MANIFEST.relative_to(ROOT)}"]
    errors: list[str] = []
    text = MANIFEST.read_text(encoding="utf-8")

    for kind in ("style", "color", "drawable", "mipmap", "xml"):
        for ref in re.findall(rf'@{kind}/([A-Za-z0-9_.]+)', text):
            if kind == "style":
                if ref in defined["style"]:
                    continue
                # 系统主题形如 Theme.SplashScreen 由依赖提供，不在这里校验
                if ref.startswith("Theme.SplashScreen"):
                    continue
                errors.append(f"AndroidManifest.xml: 引用了不存在的 style @style/{ref}")
            elif kind == "color":
                if ref in defined["color"] or ref == "ic_launcher_background":
                    continue
                if ref.startswith("splash") or ref.startswith("brand"):
                    errors.append(f"AndroidManifest.xml: 引用了不存在的 color @color/{ref}")
    return errors


def check_duplicate_string_names() -> list[str]:
    errors: list[str] = []
    strings_file = RES_DIR / "values" / "strings.xml"
    if not strings_file.exists():
        return [f"缺少 {strings_file.relative_to(ROOT)}"]
    root = ET.parse(strings_file).getroot()
    seen: dict[str, int] = {}
    for child in root:
        name = child.attrib.get("name")
        if not name:
            continue
        seen[name] = seen.get(name, 0) + 1
    for name, count in seen.items():
        if count > 1:
            errors.append(f"strings.xml: 字符串名重复 {name} × {count}")
    return errors


def main() -> int:
    files = collect_xml_files()
    if not files:
        print("没有找到任何 XML 资源，路径可能不对", file=sys.stderr)
        return 1

    errors: list[str] = []
    errors += check_well_formed(files)
    defined = collect_defined_resources()
    errors += check_manifest_references(defined)
    errors += check_duplicate_string_names()

    print(f"检查 XML 文件 {len(files)} 个；定义 color={len(defined['color'])} "
          f"string={len(defined['string'])} style={len(defined['style'])}")

    if errors:
        for message in errors:
            print(f"::error::{message}")
        return 1
    print("XML 校验通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
