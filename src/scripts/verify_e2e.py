#!/usr/bin/env python3
"""订单 REST API 端到端验证（apply 之后运行）。

读取 `terraform output -json`，对 5 个 API 做完整 CRUD：
创建订单 → 查询 → 列表 → 更新（含过期版本反例）→ 删除 → 删除后查询 404。
所有调用携带 X-Stage 头（非 RELEASE 环境必需）。结果写入 verify-result.json。
"""
from __future__ import annotations

import datetime
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RESULT_FILE = ROOT / "verify-result.json"
MAX_PAGES = 50
RETRYABLE_STATUS = {500, 502, 503, 504}


def terraform_exe() -> str:
    candidates = [
        os.environ.get("TERRAFORM_EXE", ""),
        shutil.which("terraform") or "",
        "/tmp/terraform.exe",
        "E:/code/huawei-samples/.tmp-tools/terraform.exe",
    ]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return candidate
    raise RuntimeError("未找到 terraform 可执行文件，请设置 TERRAFORM_EXE")


def load_outputs() -> dict:
    result = subprocess.run([terraform_exe(), "output", "-json"], cwd=ROOT / "infra",
                            capture_output=True, check=True)
    raw = json.loads(result.stdout.decode("utf-8", errors="replace"))
    return {key: value["value"] for key, value in raw.items()}


def request(method: str, url: str, stage: str, payload: dict | None = None,
            attempts: int = 3) -> tuple[int, str]:
    body = json.dumps(payload).encode("utf-8") if payload is not None else None
    last_error = ""
    for attempt in range(1, attempts + 1):
        req = urllib.request.Request(url, data=body, method=method, headers={
            "Content-Type": "application/json",
            "X-Stage": stage,
        })
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                return response.status, response.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as exc:
            text = exc.read().decode("utf-8", "replace")
            if exc.code in RETRYABLE_STATUS and attempt < attempts:
                last_error = f"HTTP {exc.code}: {text[:200]}"
                time.sleep(5 * attempt)
                continue
            return exc.code, text
        except (urllib.error.URLError, TimeoutError) as exc:
            last_error = str(exc)
            if attempt < attempts:
                time.sleep(5 * attempt)
                continue
            return 0, f"network error: {exc}"
    return 0, last_error


def parse(text: str) -> dict:
    try:
        parsed = json.loads(text)
        return parsed if isinstance(parsed, dict) else {}
    except (TypeError, ValueError):
        return {}


def find_order_in_list(base: str, stage: str, order_id: str) -> tuple[int, str]:
    """Follow exclusive_start_key pagination until the order is found."""
    url = f"{base}/orders"
    for _ in range(MAX_PAGES):
        status, text = request("GET", url, stage)
        if status != 200:
            return status, text
        body = parse(text)
        for order in body.get("orders") or []:
            if order.get("orderId") == order_id:
                return 200, text
        continuation = body.get("lastEvaluatedKey")
        if not continuation:
            return 404, f"order not found in list: {text[:200]}"
        url = f"{base}/orders?exclusive_start_key={urllib.parse.quote(continuation)}"
    return 404, "exceeded maximum page count"


def main() -> int:
    outputs = load_outputs()
    base = str(outputs.get("api_base_url", "")).rstrip("/")
    stage = str(outputs.get("apig_env_name", "prod"))
    if not base:
        raise RuntimeError("terraform output 缺少 api_base_url，请先 apply")
    print(f"[INFO] base={base} stage={stage}")

    checks: list[dict] = []
    customer = f"jora-e2e-{uuid.uuid4().hex[:8]}"
    order_id = ""

    def record(name: str, passed: bool, expected, actual, detail: str = "") -> None:
        checks.append({"name": name, "passed": passed,
                       "expected": expected, "actual": actual, "detail": detail[:500]})
        print(("[PASS] " if passed else "[FAIL] ") + name
              + ("" if passed else f" expected={expected} actual={actual} {detail[:200]}"))

    # 1. 创建订单
    status, text = request("POST", f"{base}/orders", stage,
                           {"customerId": customer, "preTaxAmount": 1000, "postTaxAmount": 1200})
    created = parse(text)
    order_id = created.get("orderId", "")
    record("create_order", status == 201 and bool(order_id) and created.get("version") == 1,
           "201 + orderId + version=1", f"{status} orderId={order_id}", text)

    # 2. 查询订单
    if order_id:
        status, text = request("GET", f"{base}/orders/{order_id}", stage)
        body = parse(text)
        record("get_order", status == 200 and body.get("orderId") == order_id
               and body.get("customerId") == customer,
               "200 + 字段一致", f"{status} {body.get('orderId')}", text)
    else:
        record("get_order", False, "200", "skipped", "create failed")

    # 3. 列表包含该订单
    if order_id:
        status, text = find_order_in_list(base, stage, order_id)
        record("list_orders", status == 200,
               "200 且列表包含新订单", f"{status}", text)
    else:
        record("list_orders", False, "200", "skipped", "create failed")

    # 4. 过期版本更新应被拒绝
    if order_id:
        status, text = request("POST", f"{base}/orders/{order_id}", stage,
                               {"customerId": customer, "preTaxAmount": 1,
                                "postTaxAmount": 2, "version": 999})
        record("update_stale_version_rejected", status == 409,
               "409", f"{status}", text)
    else:
        record("update_stale_version_rejected", False, "409", "skipped", "create failed")

    # 5. 更新订单
    if order_id:
        status, text = request("POST", f"{base}/orders/{order_id}", stage,
                               {"customerId": customer + "-u", "preTaxAmount": 2000,
                                "postTaxAmount": 2400, "version": 1})
        body = parse(text)
        record("update_order", status == 200 and body.get("version") == 2
               and body.get("customerId") == customer + "-u",
               "200 + version=2", f"{status} version={body.get('version')}", text)
    else:
        record("update_order", False, "200", "skipped", "create failed")

    # 6. 删除订单
    if order_id:
        status, text = request("DELETE", f"{base}/orders/{order_id}", stage)
        body = parse(text)
        record("delete_order", status == 200 and body.get("orderId") == order_id,
               "200 + 返回被删订单", f"{status}", text)
    else:
        record("delete_order", False, "200", "skipped", "create failed")

    # 7. 删除后查询应为 404
    if order_id:
        status, text = request("GET", f"{base}/orders/{order_id}", stage, attempts=1)
        record("get_deleted_order_404", status == 404, "404", f"{status}", text)
    else:
        record("get_deleted_order_404", False, "404", "skipped", "create failed")

    passed = all(check["passed"] for check in checks)
    result = {
        "passed": passed,
        "timestamp": datetime.datetime.now().isoformat(timespec="seconds"),
        "base_url": base,
        "stage": stage,
        "checks": checks,
    }
    RESULT_FILE.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n",
                           encoding="utf-8")
    print(f"[{'PASS' if passed else 'FAIL'}] {sum(1 for c in checks if c['passed'])}"
          f"/{len(checks)} checks passed, result written to {RESULT_FILE.name}")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
