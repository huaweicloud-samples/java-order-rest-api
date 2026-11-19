#!/usr/bin/env python3
"""编译并运行纯逻辑单元测试（无需 JUnit，直接 java 驱动）。

前置条件：先运行 scripts/build.py 产出 build/classes（主代码 + gson 依赖类）。
"""
import argparse
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEST_SOURCE = ROOT / "src" / "test" / "java"
BUILD = ROOT / "src" / "build"
TEST_CLASSES = BUILD / "test-classes"
TEST_STUB = BUILD / "test-stub" / "com" / "huawei" / "services" / "runtime" / "Context.java"
# 编译期桩仅供测试编译使用，不会进入函数 JAR（build.py 从主产物中剔除）。
TEST_STUB_SOURCE = (
    "package com.huawei.services.runtime;"
    " public interface Context {"
    " String getRequestID();"
    " }\n"
)
GSON_CANDIDATES = [
    Path.home() / ".m2" / "repository" / "com" / "google" / "code" / "gson" / "gson" / "2.10.1" / "gson-2.10.1.jar",
]


def fail(message):
    print("[ERROR] " + message, file=sys.stderr)
    raise SystemExit(1)


def executable(explicit, name):
    candidate = explicit or shutil.which(name) or shutil.which(name + ".exe")
    if not candidate:
        fail("未找到 %s" % name)
    return candidate


def main():
    parser = argparse.ArgumentParser(description="运行订单 REST 纯逻辑单元测试")
    parser.add_argument("--javac")
    parser.add_argument("--java")
    parser.add_argument("--gson")
    args = parser.parse_args()
    javac = executable(args.javac, "javac")
    java = executable(args.java, "java")
    gson_jar = Path(args.gson) if args.gson else next(
        (c for c in GSON_CANDIDATES if c.is_file()), None)
    if gson_jar is None:
        fail("未找到 gson-2.10.1.jar")
    if not (BUILD / "classes").is_dir():
        fail("缺少 build/classes，请先运行 scripts/build.py")

    TEST_CLASSES.mkdir(parents=True, exist_ok=True)
    TEST_STUB.parent.mkdir(parents=True, exist_ok=True)
    TEST_STUB.write_text(TEST_STUB_SOURCE, encoding="utf-8")
    sources = [str(path) for path in sorted(TEST_SOURCE.rglob("*.java"))] + [str(TEST_STUB)]
    classpath = "%s;%s" % (BUILD / "classes", gson_jar)
    compile_cmd = [javac, "--release", "11", "-encoding", "UTF-8",
                   "-cp", classpath, "-d", str(TEST_CLASSES)] + sources
    if subprocess.run(compile_cmd, cwd=ROOT).returncode:
        fail("测试编译失败")

    run_cmd = [java, "-cp", "%s;%s" % (TEST_CLASSES, classpath),
               "com.example.jora.tests.UnitTests"]
    result = subprocess.run(run_cmd, cwd=ROOT)
    if result.returncode:
        fail("单元测试失败")
    print("[PASS] 单元测试全部通过")


if __name__ == "__main__":
    main()
