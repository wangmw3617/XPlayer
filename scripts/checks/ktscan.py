#!/usr/bin/env python3
"""Kotlin 源码浅层扫描。

不是编译器，只拦三类「改完代码一眼看不出、但 CI 编译必炸」的问题：

1. **括号不配对** —— 批量编辑/合并代码时最常见的破坏，报错位置往往离真正的问题很远；
2. **同一文件里重复的顶层声明** —— 复制粘贴忘了改名，Kotlin 报
   "Conflicting declarations"，但只给类名不给行号；
3. **文件里出现的 `TODO(` 调用**（`TODO("...")` 是抛异常，不是注释）——
   漏在提交里会导致运行期直接崩。

字符串、字符字面量与注释会被跳过，避免把里面的括号算进去。
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC_DIRS = [ROOT / "app" / "src"]

# 顶层声明：class / object / interface / fun / val / var，缩进为 0 才算是顶层。
#
# 名字部分要同时处理扩展函数 `fun Modifier.foo(...)`：接收者与函数名用点分隔，
# 只用「第一个标识符」当名字的话，同一文件里两个 `fun Modifier.xxx` 会被误判成重复。
MODIFIERS = (
    r"(?:internal|private|public|protected|abstract|open|sealed|data|enum|annotation|"
    r"const|inline|suspend|operator|override|external|tailrec|expect|actual|lateinit|"
    r"noinline|crossinline|vararg)\s+"
)
ANNOTATION = r"@\w+(?:\([^)]*\))?\s*"
TOP_LEVEL_RE = re.compile(
    r"^(?:" + ANNOTATION + r")*"
    r"(?:" + MODIFIERS + r")*"
    r"(class|object|interface|fun|val|var)\s+"
    r"([A-Za-z_][A-Za-z0-9_]*)(?:\.([A-Za-z_][A-Za-z0-9_]*))?"
    r"(?=[\s<(:{=]|$)"
)

PAIRS = {"(": ")", "[": "]", "{": "}"}
CLOSERS = {v: k for k, v in PAIRS.items()}


def strip_literals_and_comments(text: str) -> str:
    """把字符串、字符、注释替换成等长空白，保留换行以便报行号。"""
    out: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        # 行注释
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                out.append(" ")
                i += 1
            continue
        # 块注释（Kotlin 支持嵌套）
        if ch == "/" and i + 1 < n and text[i + 1] == "*":
            depth = 0
            while i < n:
                if text[i] == "/" and i + 1 < n and text[i + 1] == "*":
                    depth += 1
                    out.append("  ")
                    i += 2
                    continue
                if text[i] == "*" and i + 1 < n and text[i + 1] == "/":
                    depth -= 1
                    out.append("  ")
                    i += 2
                    if depth == 0:
                        break
                    continue
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            continue
        # 三引号字符串
        if text.startswith('"""', i):
            out.append("   ")
            i += 3
            while i < n and not text.startswith('"""', i):
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            out.append("   ")
            i += 3
            continue
        # 普通字符串
        if ch == '"':
            out.append(" ")
            i += 1
            while i < n and text[i] != '"':
                if text[i] == "\\" and i + 1 < n:
                    out.append("  ")
                    i += 2
                    continue
                if text[i] == "\n":
                    break
                out.append(" ")
                i += 1
            if i < n:
                out.append(" ")
                i += 1
            continue
        # 字符字面量
        if ch == "'":
            out.append(" ")
            i += 1
            while i < n and text[i] != "'":
                if text[i] == "\\" and i + 1 < n:
                    out.append("  ")
                    i += 2
                    continue
                out.append(" ")
                i += 1
            if i < n:
                out.append(" ")
                i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def check_balance(path: pathlib.Path, cleaned: str) -> list[str]:
    errors: list[str] = []
    stack: list[tuple[str, int]] = []
    line = 1
    for ch in cleaned:
        if ch == "\n":
            line += 1
        elif ch in PAIRS:
            stack.append((ch, line))
        elif ch in CLOSERS:
            if not stack:
                errors.append(f"{path.relative_to(ROOT)}:{line}: 多余的 '{ch}'")
                return errors
            opener, opened_at = stack.pop()
            if opener != CLOSERS[ch]:
                errors.append(
                    f"{path.relative_to(ROOT)}:{line}: '{ch}' 与第 {opened_at} 行的 "
                    f"'{opener}' 不配对"
                )
                return errors
    if stack:
        opener, opened_at = stack[-1]
        errors.append(
            f"{path.relative_to(ROOT)}:{opened_at}: '{opener}' 没有对应的 '{PAIRS[opener]}'"
        )
    return errors


def check_duplicate_top_level(path: pathlib.Path, cleaned: str) -> list[str]:
    seen: dict[str, int] = {}
    errors: list[str] = []
    for index, raw in enumerate(cleaned.splitlines(), start=1):
        if raw[:1] in (" ", "\t"):
            continue
        match = TOP_LEVEL_RE.match(raw)
        if not match:
            continue
        kind, head, tail = match.group(1), match.group(2), match.group(3)
        name = f"{head}.{tail}" if tail else head
        key = f"{kind} {name}"
        if key in seen:
            errors.append(
                f"{path.relative_to(ROOT)}:{index}: 顶层声明重复 `{key}`（首次出现在第 {seen[key]} 行）"
            )
        else:
            seen[key] = index
    return errors


def check_todo_calls(path: pathlib.Path, cleaned: str) -> list[str]:
    errors: list[str] = []
    for index, raw in enumerate(cleaned.splitlines(), start=1):
        if "TODO(" in raw:
            errors.append(
                f"{path.relative_to(ROOT)}:{index}: 残留 `TODO(...)` 调用（会抛 NotImplementedError）"
            )
    return errors


def main(argv: list[str]) -> int:
    files: list[pathlib.Path] = []
    if argv:
        files = [pathlib.Path(p).resolve() for p in argv]
    else:
        for src in SRC_DIRS:
            files.extend(sorted(src.rglob("*.kt")))

    files = [f for f in files if f.exists() and f.suffix == ".kt"]
    if not files:
        print("没有找到任何 Kotlin 文件", file=sys.stderr)
        return 1

    errors: list[str] = []
    for path in files:
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError as exc:
            errors.append(f"{path.relative_to(ROOT)}: 不是合法的 UTF-8 -> {exc}")
            continue
        cleaned = strip_literals_and_comments(text)
        errors += check_balance(path, cleaned)
        errors += check_duplicate_top_level(path, cleaned)
        errors += check_todo_calls(path, cleaned)

    print(f"扫描 Kotlin 文件 {len(files)} 个")
    if errors:
        for message in errors:
            print(f"::error::{message}")
        return 1
    print("Kotlin 浅层扫描通过")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
