#!/usr/bin/env python3
"""Build the reproducible FunctionGraph Java 11 deployment package.

确定性打包（固定 ZipInfo 时间戳与权限位），两次构建产物 sha256 一致；
编译期 Context 桩仅声明函数实际调用的方法，不打入函数 JAR；
gson 运行库（本地 Maven 缓存中的 gson-2.10.1.jar）按类展开并入函数 JAR，
展开条目同样以固定时间戳写入，保证整体可复现。
外层 ZIP 根目录仅包含 function.jar（FunctionGraph Java11 zip 部署要求）。
"""
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "src" / "main" / "java"
BUILD = ROOT / "src" / "build"
CLASSES = BUILD / "classes"
STUB = BUILD / "compile-stub" / "com" / "huawei" / "services" / "runtime" / "Context.java"
FIXED_TIME = (1980, 1, 1, 0, 0, 0)
GSON_CANDIDATES = [
    Path.home() / ".m2" / "repository" / "com" / "google" / "code" / "gson" / "gson" / "2.10.1" / "gson-2.10.1.jar",
]

# 桩只声明函数实际调用的 Context 方法，编译后从产物中剔除。
STUB_SOURCE = (
    "package com.huawei.services.runtime;"
    " public interface Context {"
    " String getRequestID();"
    " }\n"
)

HANDLERS = [
    "com/example/jora/handler/CreateOrderHandler.class",
    "com/example/jora/handler/GetOrderHandler.class",
    "com/example/jora/handler/GetOrdersHandler.class",
    "com/example/jora/handler/UpdateOrderHandler.class",
    "com/example/jora/handler/DeleteOrderHandler.class",
]


def fail(message):
    print("[ERROR] " + message, file=sys.stderr)
    raise SystemExit(1)


def executable(explicit, name):
    candidate = explicit or shutil.which(name) or shutil.which(name + ".exe")
    if not candidate:
        fail("未找到 %s" % name)
    return candidate


def run(command):
    result = subprocess.run(command, cwd=ROOT, check=False)
    if result.returncode:
        fail("命令失败: " + " ".join(command))


def zip_entry(name, content):
    info = zipfile.ZipInfo(name, FIXED_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    info.external_attr = 0o100644 << 16
    return info, content


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def find_gson(explicit):
    candidates = [Path(explicit)] if explicit else GSON_CANDIDATES
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    fail("未找到 gson-2.10.1.jar（尝试过: %s）" % ", ".join(str(c) for c in candidates))


def gson_classes(gson_jar):
    """读取 gson JAR 中的类条目（名字排序，字节原样；剔除 META-INF 元数据）。"""
    entries = []
    with zipfile.ZipFile(gson_jar) as package:
        for name in sorted(package.namelist()):
            if name.endswith("/") or name.startswith("META-INF/"):
                continue
            entries.append((name, package.read(name)))
    if not any(name.startswith("com/google/gson/Gson") for name, _ in entries):
        fail("gson JAR 内容异常")
    return entries


def main():
    parser = argparse.ArgumentParser(description="构建 FunctionGraph Java 11 订单 REST 部署包")
    parser.add_argument("--javac")
    parser.add_argument("--gson")
    args = parser.parse_args()
    javac = executable(args.javac, "javac")
    gson_jar = find_gson(args.gson)

    if BUILD.exists():
        shutil.rmtree(BUILD)
    CLASSES.mkdir(parents=True)
    STUB.parent.mkdir(parents=True)
    STUB.write_text(STUB_SOURCE, encoding="utf-8")

    sources = [str(path) for path in sorted(SOURCE.rglob("*.java"))]
    if not sources:
        fail("未找到 Java 源码")
    run([javac, "--release", "11", "-encoding", "UTF-8", "-cp", str(gson_jar),
         "-d", str(CLASSES), str(STUB)] + sources)

    compiled_stub = CLASSES / "com" / "huawei" / "services" / "runtime" / "Context.class"
    if not compiled_stub.is_file():
        fail("编译期 Context 桩缺失")
    compiled_stub.unlink()

    jar_file = BUILD / "function.jar"
    with zipfile.ZipFile(jar_file, "w") as package:
        for class_file in sorted(CLASSES.rglob("*.class")):
            relative = class_file.relative_to(CLASSES).as_posix()
            info, content = zip_entry(relative, class_file.read_bytes())
            package.writestr(info, content)
        for entry_name, entry_bytes in gson_classes(gson_jar):
            info, content = zip_entry(entry_name, entry_bytes)
            package.writestr(info, content)

    with zipfile.ZipFile(jar_file) as package:
        names = set(package.namelist())
    for handler in HANDLERS:
        if handler not in names:
            fail("函数 JAR 缺少入口类 %s" % handler)
    if any(item.startswith("com/huawei/") for item in names):
        fail("编译期 Context 桩泄漏进函数 JAR")
    if not any(item.startswith("com/google/gson/") for item in names):
        fail("函数 JAR 未并入 gson 运行库")

    archive = BUILD / "function.zip"
    with zipfile.ZipFile(archive, "w") as package:
        info, content = zip_entry("function.jar", jar_file.read_bytes())
        package.writestr(info, content)
    with zipfile.ZipFile(archive) as package:
        if package.namelist() != ["function.jar"]:
            fail("外层 ZIP 根目录必须仅包含 function.jar")

    digest = sha256(archive)
    print("[PASS] Java 11 编译完成，编译期 Context 桩未打包")
    print("[PASS] function.zip 根目录仅包含 function.jar")
    print("[PASS] function.zip sha256=%s" % digest)
    (BUILD / "sha256.json").write_text(
        json.dumps({"function.zip": digest}, indent=2) + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    main()
