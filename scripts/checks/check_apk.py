#!/usr/bin/env python3
"""拆开 release APK 做产物级校验。

编译通过 ≠ 装上去能用。下面这些事编译器一律不管，只有拆开 APK 才看得见：

1. **R8 有没有把 JNI 入口改掉**。`dev.jdtech.mpv.MPVLib` 的 native 方法是由
   native 侧按「类名_方法名」反查的，一旦被混淆就是 `UnsatisfiedLinkError`。
   AAR 自带 keep 规则，但规则生效与否只能看产物。
2. **原生库是不是 STORED 且 16KB 对齐**。`extractNativeLibs=false` 时系统直接从
   APK 映射 .so，压缩过或没对齐就会在 Android 15+（16KB 页）上装不上/起不来。
3. **清单里的关键开关**：PiP、前台服务类型、configChanges、权限。

用法：check_apk.py <apk 路径>
"""

from __future__ import annotations

import struct
import sys
import zipfile

# ---------------------------------------------------------------- AXML 解析 ----

RES_STRING_POOL = 0x0001
RES_XML_START_ELEMENT = 0x0102
RES_XML_END_ELEMENT = 0x0103
RES_XML_START_NAMESPACE = 0x0100
RES_XML_RESOURCE_MAP = 0x0180

TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_INT_BOOLEAN = 0x12


def _decode_length8(data: bytes, offset: int) -> tuple[int, int]:
    value = data[offset]
    if value & 0x80:
        value = ((value & 0x7F) << 8) | data[offset + 1]
        return value, offset + 2
    return value, offset + 1




def parse_string_pool(data: bytes, offset: int) -> tuple[list[str], int]:
    header_size, chunk_size = struct.unpack_from("<II", data, offset + 2)
    string_count, _, flags, strings_start, _ = struct.unpack_from("<IIIII", data, offset + 8)
    is_utf8 = (flags & (1 << 8)) != 0
    offsets = struct.unpack_from(f"<{string_count}I", data, offset + 28)
    base = offset + strings_start
    strings: list[str] = []
    for item in offsets:
        pos = base + item
        if is_utf8:
            _, pos = _decode_length8(data, pos)  # 字符数（不用）
            byte_len, pos = _decode_length8(data, pos)
            raw = data[pos:pos + byte_len]
            strings.append(raw.decode("utf-8", "replace"))
        else:
            # UTF-16 池里长度就是一个小端 u16，**不是** UTF-8 那套「1~2 字节大端变长」编码。
            # 早先按后者解，会把 `05 00` 读成 1280，整池字符串全乱。
            length = struct.unpack_from("<H", data, pos)[0]
            pos += 2
            raw = data[pos:pos + length * 2]
            strings.append(raw.decode("utf-16-le", "replace"))
    return strings, offset + chunk_size


def parse_axml(data: bytes) -> list[dict]:
    """返回按顺序排列的元素：{"name": ..., "attrs": {名: 值}, "depth": n}"""
    _, _, _ = struct.unpack_from("<HHI", data, 0)
    offset = 8
    strings: list[str] = []
    events: list[dict] = []
    depth = 0

    while offset < len(data):
        chunk_type, header_size, chunk_size = struct.unpack_from("<HHI", data, offset)
        if chunk_size == 0:
            break
        if chunk_type == RES_STRING_POOL:
            strings, _ = parse_string_pool(data, offset)
        elif chunk_type == RES_XML_START_NAMESPACE:
            pass
        elif chunk_type == RES_XML_START_ELEMENT:
            _, _, _ = struct.unpack_from("<HHI", data, offset)
            # ResXMLTree_node = header(8) + lineNumber(4) + comment(4) = 16
            # ResXMLTree_attrExt 紧接其后：ns(+16) name(+20) attributeStart(+24)
            #   attributeSize(+26) attributeCount(+28) idIndex(+30) classIndex(+32) styleIndex(+34)
            name_idx = struct.unpack_from("<I", data, offset + 20)[0]
            attr_start = struct.unpack_from("<H", data, offset + 24)[0]
            attr_size = struct.unpack_from("<H", data, offset + 26)[0]
            attr_count = struct.unpack_from("<H", data, offset + 28)[0]
            attrs: dict[str, object] = {}
            for i in range(attr_count):
                # 属性数组起点相对 attrExt（+16）而言
                a = offset + 16 + attr_start + i * attr_size
                a_name = struct.unpack_from("<I", data, a + 4)[0]
                a_raw = struct.unpack_from("<I", data, a + 8)[0]
                data_type = data[a + 15]
                a_data = struct.unpack_from("<I", data, a + 16)[0]
                key = strings[a_name] if a_name < len(strings) else f"attr{a_name}"
                if a_raw != 0xFFFFFFFF and a_raw < len(strings):
                    attrs[key] = strings[a_raw]
                elif data_type == TYPE_STRING and a_data < len(strings):
                    attrs[key] = strings[a_data]
                elif data_type == TYPE_INT_BOOLEAN:
                    attrs[key] = bool(a_data)
                elif data_type in (TYPE_INT_DEC, TYPE_INT_HEX):
                    attrs[key] = a_data
                else:
                    attrs[key] = a_data
            name = strings[name_idx] if name_idx < len(strings) else "?"
            events.append({"name": name, "attrs": attrs, "depth": depth})
            depth += 1
        elif chunk_type == RES_XML_END_ELEMENT:
            depth = max(0, depth - 1)
        offset += chunk_size
    return events


# ------------------------------------------------------------------ 校验项 ----

def check_manifest(zf: zipfile.ZipFile) -> list[str]:
    problems: list[str] = []
    events = parse_axml(zf.read("AndroidManifest.xml"))
    by_name = {e["name"]: e["attrs"] for e in events}

    print("--- 清单 ---")
    manifest = by_name.get("manifest", {})
    print(f"  package            : {manifest.get('package')}")
    print(f"  versionName/Code   : {manifest.get('versionName')} / {manifest.get('versionCode')}")
    sdk = by_name.get("uses-sdk", {})
    print(f"  minSdk / targetSdk : {sdk.get('minSdkVersion')} / {sdk.get('targetSdkVersion')}")
    if sdk.get("minSdkVersion") != 26:
        problems.append(f"minSdk 期望 26，实际 {sdk.get('minSdkVersion')}")

    app = by_name.get("application", {})
    print(f"  application name   : {app.get('name')}")
    print(f"  extractNativeLibs  : {app.get('extractNativeLibs')}")
    if app.get("name") != "com.zhiwei.xplayer.XPlayerApp":
        problems.append(f"application 类名不对：{app.get('name')}")

    permissions = sorted(
        e["attrs"]["name"].split(".")[-1]
        for e in events
        if e["name"] == "uses-permission" and "name" in e["attrs"]
    )
    print(f"  权限({len(permissions)})        : {', '.join(permissions)}")
    for required in ("INTERNET", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO",
                     "POST_NOTIFICATIONS", "FOREGROUND_SERVICE",
                     "FOREGROUND_SERVICE_MEDIA_PLAYBACK"):
        if required not in permissions:
            problems.append(f"缺少权限 {required}")

    for e in events:
        if e["name"] != "activity" and e["name"] != "service":
            continue
        attrs = e["attrs"]
        label = attrs.get("name", "?").split(".")[-1]
        print(f"  {e['name']:8s} {label:22s} exported={attrs.get('exported')} "
              f"pip={attrs.get('supportsPictureInPicture')} "
              f"fgsType={attrs.get('foregroundServiceType')}")
        if e["name"] == "activity" and label == "MainActivity":
            if attrs.get("supportsPictureInPicture") is not True:
                problems.append("MainActivity 没开 supportsPictureInPicture")
            config = str(attrs.get("configChanges", ""))
            # 0x4A0 = orientation|screenSize|screenLayout|smallestScreenSize 之类，
            # 这里只确认位掩码里确实带了 orientation 与 screenSize
            if not config:
                problems.append("MainActivity 没声明 configChanges")
        if e["name"] == "service" and label == "PlaybackService":
            if str(attrs.get("foregroundServiceType", "")).strip() in ("", "0"):
                problems.append("PlaybackService 没声明 foregroundServiceType")
    return problems


def check_dex(zf: zipfile.ZipFile) -> list[str]:
    """确认 R8 没有动 JNI 入口。dex 里以字符串形式出现的类名/方法名直接搜。"""
    problems: list[str] = []
    dex_names = sorted(n for n in zf.namelist() if n.endswith(".dex"))
    blob = b"".join(zf.read(n) for n in dex_names)
    print(f"--- dex（{len(dex_names)} 个，合计 {len(blob) / 1048576:.1f} MB）---")

    # 只有两类名字必须原样活下来：
    #   1. JNI 入口 —— native 侧按「类名_方法名」反查，改名即 UnsatisfiedLinkError；
    #   2. 清单里按字符串引用的类 —— 改名即 ClassNotFoundException。
    # 其余 Kotlin 类被 R8 改名是正常的，不要在这里断言。
    required = [
        b"dev/jdtech/mpv/MPVLib",
        b"attachSurface",
        b"detachSurface",
        b"setOptionString",
        b"observeProperty",
        b"getPropertyString",
        b"com/zhiwei/xplayer/MainActivity",
        b"com/zhiwei/xplayer/core/playback/PlaybackService",
        b"com/zhiwei/xplayer/XPlayerApp",
    ]
    for token in required:
        ok = token in blob
        print(f"  {'OK ' if ok else '!! '} {token.decode()}")
        if not ok:
            problems.append(f"dex 里找不到 {token.decode()}（R8 可能把它删了或改了名）")
    return problems


def check_native_libs(zf: zipfile.ZipFile) -> list[str]:
    problems: list[str] = []
    print("--- 原生库 ---")
    libs = [i for i in zf.infolist() if i.filename.startswith("lib/")]
    if not libs:
        return ["APK 里没有 lib/ 下的原生库"]

    abis = sorted({i.filename.split("/")[1] for i in libs})
    print(f"  含 ABI: {abis}")
    if len(abis) != 1:
        problems.append(f"ABI 拆包失效，一个 APK 里出现了多套 ABI：{abis}")

    raw = zf.fp
    for info in sorted(libs, key=lambda i: i.filename):
        if info.compress_type != zipfile.ZIP_STORED:
            problems.append(f"{info.filename} 被压缩了（extractNativeLibs=false 时必须 STORED）")
        # 必须读「本地文件头」：zipalign 就是靠拉长本地 extra 来对齐数据起始偏移的，
        # 中央目录里的 extra 长度和本地头并不一样。
        raw.seek(info.header_offset)
        header = raw.read(30)
        name_len = struct.unpack_from("<H", header, 26)[0]
        extra_len = struct.unpack_from("<H", header, 28)[0]
        data_offset = info.header_offset + 30 + name_len + extra_len
        aligned_4k = data_offset % 4096 == 0
        aligned_16k = data_offset % 16384 == 0
        flag = "16KB" if aligned_16k else ("4KB" if aligned_4k else "未对齐")
        print(f"  {info.filename:44s} {info.file_size / 1048576:6.2f} MB  "
              f"{'STORED' if info.compress_type == 0 else 'DEFLATED':8s} 偏移对齐={flag}")
        if not aligned_4k:
            problems.append(f"{info.filename} 数据偏移 {data_offset} 连 4KB 都没对齐")
    return problems


def main() -> int:
    if len(sys.argv) < 2:
        print("用法: check_apk.py <apk>", file=sys.stderr)
        return 2
    path = sys.argv[1]
    with zipfile.ZipFile(path) as zf:
        names = zf.namelist()
        print(f"=== {path} ===")
        print(f"条目 {len(names)} 个；签名文件 "
              f"{[n for n in names if n.startswith('META-INF/') and n.endswith(('.RSA', '.DSA', '.EC'))]}")
        problems = check_manifest(zf) + check_dex(zf) + check_native_libs(zf)

    print("\n=== 结论 ===")
    if problems:
        for p in problems:
            print(f"  [问题] {p}")
        return 1
    print("  全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
