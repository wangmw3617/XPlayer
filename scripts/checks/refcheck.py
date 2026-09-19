#!/usr/bin/env python3
"""检测「导入了但没用」「用了但没导入」这两类问题。

CI 里的一次编译往返要 20 多分钟，而这两类错误占了本工程编译失败的大多数。
这里用纯文本启发式扫一遍 —— 比不上编译器，但足以在推送前把大部分低级错误挡下来。

用法：
    python3 scripts/checks/refcheck.py            # 扫默认目录
    python3 scripts/checks/refcheck.py <目录>
"""

import os
import re
import sys

SRC_ROOT = os.path.join("app", "src", "main", "java")

# 这些包里的名字可能是隐式可用的（Compose 编译器插件注入、作用域成员等），
# 见到就跳过，别误报。
SKIP_IMPORT_HINTS = (
    "androidx.compose.runtime.getValue",
    "androidx.compose.runtime.setValue",
    "androidx.compose.runtime.mutableStateOf",
    "kotlinx.coroutines.flow.",
)

# Kotlin 语言内建类型，永远不需要导入
BUILTINS = {
    "Any", "Array", "ArrayList", "Boolean", "Byte", "Char", "CharSequence",
    "ClosedRange", "Comparable", "Double", "Enum", "Exception", "Float",
    "HashMap", "HashSet", "IllegalArgumentException", "IllegalStateException",
    "Int", "Iterable", "LinkedHashMap", "List", "Long", "Map", "MutableList",
    "MutableMap", "MutableSet", "Nothing", "Number", "Pair", "Runnable",
    "RuntimeException", "Sequence", "Set", "Short", "String", "StringBuilder",
    "Throwable", "Triple", "Unit", "UnsupportedOperationException", "Runtime",
    "System", "Void", "Result", "Regex",
}

# Compose 里由作用域提供的成员，看起来「没导入」但其实是类成员
SCOPE_PROVIDED = {
    "weight", "align", "matchParentSize", "padding", "size", "height", "width",
}

# 同包内互相引用的类型不需要 import，这里收集所有声明过的顶层类型名
ALL_DECLARED_TYPES = set()

# --------------------------------------------------------------------------
# Java getter 误当函数调用
#
# Kotlin 见到 Java 的 `String getFoo()` 会暴露成**属性** `foo`，
# 所以 `x.getFoo()` 与 `x.foo()` 都不对，正确写法是 `x.foo`。
# 这个坑真实踩过两次：
#   1. `parser.localName()` —— 名字根本不存在（XmlPullParser 叫 getName()）；
#   2. 改成 `parser.localName` 依然错，因为没有这个方法。
#      正确写法是 `parser.name`。
#
# 规则：`.<getterName>()` 形状（点号 + 已知 getter 名 + 空括号）一律报错。
# 表里的值是「Kotlin 里正确的属性名」，而不是把 get/set 简单去前缀 ——
# 例如 getName() 对应 name，但 localName() 根本不存在，正确名是 name。
# --------------------------------------------------------------------------
JAVA_GETTERS_AS_PROPERTIES = {
    # org.xmlpull.v1.XmlPullParser —— 注意：没有 localName()
    "getName": "name",
    "getPrefix": "prefix",
    "getNamespace": "namespace",
    "getText": "text",
    "getEventType": "eventType",
    "getAttributeCount": "attributeCount",
    "getDepth": "depth",
    "getPositionDescription": "positionDescription",
    "getLineNumber": "lineNumber",
    "getColumnNumber": "columnNumber",
    "getInputEncoding": "inputEncoding",
    "isEmptyElementTag": "isEmptyElementTag",
    # 常见 JDK / Android
    "getMessage": "message",
    "getCause": "cause",
    "getStatusCode": "statusCode",
    "getResponseCode": "responseCode",
    "getContentLength": "contentLength",
    "getContentType": "contentType",
    "getLastModified": "lastModified",
    "getSize": "size",
    "getTime": "time",
    "getPath": "path",
    "getParent": "parent",
    "getScheme": "scheme",
    "getHost": "host",
    "getPort": "port",
    "getQuery": "query",
    "getFragment": "fragment",
    "getAuthority": "authority",
    "getUserInfo": "userInfo",
    "getEncodedPath": "encodedPath",
}

# 这些名字在 Kotlin/Compose 里**确实**是函数，别误报
NOT_JAVA_GETTERS: set = set()


def collect_declared(root):
    """先把整个工程声明的类型收起来，供「同包引用」判断用。"""
    for dirpath, _, filenames in os.walk(root):
        for name in filenames:
            if not name.endswith(".kt"):
                continue
            text = strip_comments(open(os.path.join(dirpath, name), encoding="utf-8").read())
            ALL_DECLARED_TYPES.update(
                re.findall(r"\b(?:class|object|interface|enum class)\s+(\w+)", text)
            )


def kotlin_files(root):
    for dirpath, _, filenames in os.walk(root):
        for name in filenames:
            if name.endswith(".kt"):
                yield os.path.join(dirpath, name)


def strip_comments(text):
    """去掉行注释。

    刻意**不**处理块注释：本工程的 KDoc 里会引用代码示例（例如 XML 片段），
    其中可能含有 `*/` 这样的字符序列，用非贪婪的块注释正则会把从 `/∗∗`
    到那个假 `*/` 之间的代码整段误删 —— 于是真正用到某 import 的那几行
    会被判成「不存在」，产生大量假阳性。宁可多留点注释文字，
    也不要误删代码。
    """
    # 逐行处理，跳过三引号字符串内部的行（那里面可能有 // 但不是注释）
    out = []
    in_raw_string = False
    for line in text.splitlines():
        if line.count('"""') % 2 == 1:
            in_raw_string = not in_raw_string
            out.append(line)
            continue
        if in_raw_string:
            out.append(line)
            continue
        # 去掉 // 之后的内容（不处理字符串里的 //，这对本检查足够）
        out.append(re.sub(r"//[^\n]*", "", line))
    return "\n".join(out)


def strip_comments_for_getter_rule(text):
    """给「Java getter 误当函数调用」这条规则用的、更激进的去注释。

    为什么不直接复用 strip_comments：那个函数**故意**不处理块注释
    （KDoc 里会贴 XML/代码片段，带 `*/` 的假块注释会把真实代码整段吃掉）。
    但 getter 规则恰恰最怕 KDoc 里的代码示例 —— 本工程的 KDoc 里就写了
    `parser.getName()` 这种**正确**的说明性文字，会被误报。

    这里用状态机逐行处理，只在「确实进入块注释」时丢弃：
    以 `/**` 或 `/*` 开头、到本行或后续某行的 `*/` 结束。
    三引号字符串内部不改动。宁可漏报，不可误报。
    """
    out = []
    in_block = False
    in_raw = False
    for line in text.splitlines():
        if in_raw:
            if line.count('"""') % 2 == 1:
                in_raw = False
            out.append(line)
            continue
        if line.count('"""') % 2 == 1:
            in_raw = True
            out.append(line)
            continue

        work = line
        if in_block:
            end = work.find("*/")
            if end == -1:
                out.append("")
                continue
            work = work[end + 2 :]
            in_block = False

        start = work.find("/*")
        if start != -1:
            end = work.find("*/", start + 2)
            if end == -1:
                in_block = True
                work = work[:start]
            else:
                work = work[:start] + work[end + 2 :]

        work = re.sub(r"//[^\n]*", "", work)
        out.append(work)
    return "\n".join(out)


def body_after_imports(text):
    """返回「最后一个 import 之后」的全部内容。

    不能写成「遇到第一个非 import/非空行就停」：本工程里有些文件 import 之后
    紧跟着多行 KDoc 与三引号字符串，按行扫会被字符串内容带偏，
    结果把真正用到某个 import 的代码判成在 import 区之外而误报未使用。
    这里改成扫描全部 import 语句，取最后一处的结束位置。
    """
    last_end = 0
    for m in re.finditer(r"^import\s+[\w.]+(?:\s+as\s+\w+)?\s*$", text, flags=re.M):
        last_end = m.end()
    return text[last_end:]


def check_file(path):
    problems = []
    raw = open(path, encoding="utf-8").read()
    text = strip_comments(raw)
    body = body_after_imports(text)

    # Java getter 误当函数调用：`x.localName()` 应为 `x.localName`
    # 只在「点号 + 名字 + 空括号」这个形状上判断，精度足够高。
    # 注意这里用的是「去掉块注释」的视图，避免 KDoc 里的示例代码触发误报。
    getter_body = strip_comments_for_getter_rule(body)
    for getter in JAVA_GETTERS_AS_PROPERTIES:
        if getter in NOT_JAVA_GETTERS:
            continue
        for m in re.finditer(r"\.\s*" + re.escape(getter) + r"\s*\(\s*\)", getter_body):
            line_no = getter_body[: m.start()].count("\n") + 1
            problems.append(
                f"{path}: Java getter 误当函数调用 -> .{getter}() "
                f"应为 .{JAVA_GETTERS_AS_PROPERTIES[getter]}（约在代码体第 {line_no} 行）"
            )

    imports = re.findall(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?", text, flags=re.M)
    for full, alias in imports:
        name = alias or full.rsplit(".", 1)[-1]
        if name == "*":
            continue
        # getValue / setValue 是被 `by` 委托隐式调用的，代码里搜不到名字
        if full in SKIP_IMPORT_HINTS or any(full.startswith(h) for h in SKIP_IMPORT_HINTS):
            continue
        # KDoc 里可能用 [Name] 引用，这里只查代码体
        if not re.search(r"\b" + re.escape(name) + r"\b", body):
            problems.append(f"{path}: 导入了但未使用 -> import {full}")

    # 大写开头的简单名若出现在代码体里，但既没导入也不是本文件声明的，
    # 也没有同包/全限定调用，就可能是缺导入。
    declared = set(
        re.findall(r"\b(?:class|object|interface|enum class|fun|val|var|typealias)\s+(\w+)", text)
    )
    imported = {alias or full.rsplit(".", 1)[-1] for full, alias in imports}
    for name in sorted(set(re.findall(r"\b([A-Z][A-Za-z0-9_]*)\b", body))):
        if name in declared or name in imported:
            continue
        if name in SCOPE_PROVIDED or name in BUILTINS:
            continue
        # 工程里自己声明过的类型（同包引用无需 import）
        if name in ALL_DECLARED_TYPES:
            continue
        # 全限定调用 Foo.Bar 里的 Bar，跳过
        if re.search(r"\." + re.escape(name) + r"\b", body):
            continue
        # 注解
        if re.search(r"@\s*" + re.escape(name) + r"\b", body):
            continue
        # 全大写（含下划线）多半是枚举常量 / 顶层 const，不是类型
        if re.fullmatch(r"[A-Z][A-Z0-9_]*", name):
            continue
        if len(name) <= 3:
            continue
        # 只有当它出现在「构造 / 调用」位置时才报：Name( 或 Name. 开头，或 : Name
        if not re.search(
            r"(?:\bnew\s+)?" + re.escape(name) + r"\s*[(<]|:\s*" + re.escape(name) + r"\b",
            body,
        ):
            continue
        problems.append(f"{path}: 疑似缺少导入 -> {name}")
    return problems


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else SRC_ROOT
    if not os.path.isdir(root):
        print(f"目录不存在: {root}")
        return 1

    unused = []
    maybe_missing = []
    getter_errors = []
    collect_declared(root)
    files = sorted(kotlin_files(root))
    for path in files:
        for problem in check_file(path):
            if "Java getter 误当函数调用" in problem:
                getter_errors.append(problem)
            elif "导入了但未使用" in problem:
                unused.append(problem)
            else:
                maybe_missing.append(problem)

    print(f"扫描 {len(files)} 个 Kotlin 文件")

    # 这一类是**确定的编译错误**（Kotlin 把 Java getter 暴露成属性），必须失败
    if getter_errors:
        print(f"  发现 {len(getter_errors)} 处 Java getter 误当函数调用（会编译失败）：")
        for p in getter_errors:
            print("    " + p)

    if maybe_missing:
        # 这些只是启发式提示：KDoc 里的代码示例、全限定名等都会误报，
        # 不作为失败依据，打印出来供人工扫一眼。
        print(f"  提示：{len(maybe_missing)} 处疑似缺少导入（可能是误报，请自行确认）")
        for p in maybe_missing:
            print("    " + p)

    if unused:
        print(f"  发现 {len(unused)} 个未使用的 import：")
        for p in unused:
            print("    " + p)
        print("未使用的 import 不影响编译，但会拖慢编译并让 R8 更难裁剪，建议删掉。")

    if getter_errors or unused:
        return 1

    print("  未使用的 import：无")
    return 0


if __name__ == "__main__":
    sys.exit(main())
