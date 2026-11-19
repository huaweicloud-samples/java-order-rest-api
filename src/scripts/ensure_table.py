#!/usr/bin/env python3
"""通过 GeminiDB DynamoDB 兼容接口创建（或删除）订单表。

供 Terraform local-exec 在 apply/destroy 阶段调用，数据面连接信息来自环境变量：
TABLE_NAME / GEMINI_ENDPOINT / GEMINI_PORT / GEMINI_PROTOCOL / GEMINI_REGION /
GEMINI_ACCESS_KEY / GEMINI_SECRET_KEY。仅使用标准库，签名为四段式派生凭据。

无 VPC 连通性的部署机可设置 GEMINI_PROXY_URL + GEMINI_PROXY_TOKEN，经共享
数据面代理函数的公网 APIG 入口转发（请求体 {"target","payload"}），两个变量
留空时仍走本机直连签名路径。
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

# Capacity mode used when the table is created: on demand, no provisioned throughput.
CAPACITY_MODE = "ON_DEMAND"


SERVICE = "dynamodb"

class TableError(RuntimeError):
    def __init__(self, operation: str, status: int, code: str, message: str) -> None:
        super().__init__(f"{operation} failed with HTTP {status}: {code} {message}")
        self.status = status
        self.code = code

def env(name: str, default: str = "") -> str:
    value = os.environ.get(name, default)
    return default if value is None else value

def endpoint_config() -> tuple[str, str]:
    endpoint = env("GEMINI_ENDPOINT")
    if not endpoint:
        raise RuntimeError("GEMINI_ENDPOINT is required")
    protocol = env("GEMINI_PROTOCOL", "http").lower()
    if protocol not in {"http", "https"}:
        raise RuntimeError("GEMINI_PROTOCOL must be http or https")
    port = int(env("GEMINI_PORT", "8000"))
    raw = endpoint if "://" in endpoint else f"{protocol}://{endpoint}"
    parsed = urllib.parse.urlsplit(raw)
    host = parsed.hostname or ""
    if not host:
        raise RuntimeError("GEMINI_ENDPOINT must contain a host")
    selected_port = parsed.port or port
    url = f"{parsed.scheme}://{host}:{selected_port}{parsed.path.rstrip('/')}"
    return url, f"{host}:{selected_port}"

def signing_key(secret_key: str, date: str, region: str) -> bytes:
    def sign(key: bytes, value: str) -> bytes:
        return hmac.new(key, value.encode("utf-8"), hashlib.sha256).digest()

    key = sign(("AWS4" + secret_key).encode("utf-8"), date)
    key = sign(key, region)
    key = sign(key, SERVICE)
    return sign(key, "aws4_request")

def send_request(operation: str, payload: dict) -> dict:
    """所有 DynamoDB 请求的单一出口：配置了代理时走公网代理，否则直连签名。"""
    proxy_url = env("GEMINI_PROXY_URL")
    if proxy_url:
        return call_via_proxy(proxy_url, env("GEMINI_PROXY_TOKEN"), operation, payload)
    url, host, region, access_key, secret_key = connect()
    return call(url, host, region, access_key, secret_key, operation, payload)

def call_via_proxy(proxy_url: str, token: str, operation: str, payload: dict) -> dict:
    """经共享数据面代理函数（APIG 公网入口）转发一次 DynamoDB 操作。"""
    event = json.dumps({"target": operation, "payload": payload},
                       separators=(",", ":")).encode("utf-8")
    headers = {"Content-Type": "application/json", "X-Stage": "prod"}
    if token:
        headers["X-Proxy-Token"] = token
    request = urllib.request.Request(proxy_url, data=event, method="POST", headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            envelope = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raw = exc.read(1024).decode("utf-8", "replace")
        raise TableError(operation, exc.code, "ProxyError", raw) from exc
    except (urllib.error.URLError, ValueError) as exc:
        raise RuntimeError(f"{operation} proxy failure: {exc}") from exc
    status = int(envelope.get("status", 0))
    body_text = str(envelope.get("body", ""))
    if status != 200:
        code, message = "ProxyError", ""
        try:
            error = json.loads(body_text)
            code = str(error.get("__type") or error.get("code") or code).split("#")[-1]
            message = str(error.get("message") or "")
        except (TypeError, ValueError):
            message = body_text[:512]
        raise TableError(operation, status, code, message)
    return json.loads(body_text) if body_text else {}

def call(url: str, host: str, region: str, access_key: str, secret_key: str,
         operation: str, payload: dict) -> dict:
    body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    now = time.gmtime()
    date = time.strftime("%Y%m%d", now)
    amz_date = time.strftime("%Y%m%dT%H%M%SZ", now)
    payload_hash = hashlib.sha256(body).hexdigest()
    path = urllib.parse.urlsplit(url).path or "/"
    headers = {
        "content-type": "application/x-amz-json-1.0",
        "host": host,
        "x-amz-content-sha256": payload_hash,
        "x-amz-date": amz_date,
        "x-amz-target": f"DynamoDB_20120810.{operation}",
    }
    canonical_headers = "".join(f"{key}:{headers[key]}\n" for key in sorted(headers))
    signed_headers = ";".join(sorted(headers))
    canonical_request = "\n".join(["POST", path, "", canonical_headers, signed_headers, payload_hash])
    scope = f"{date}/{region}/{SERVICE}/aws4_request"
    string_to_sign = "\n".join(
        ["AWS4-HMAC-SHA256", amz_date, scope, hashlib.sha256(canonical_request.encode("utf-8")).hexdigest()]
    )
    signature = hmac.new(signing_key(secret_key, date, region), string_to_sign.encode("utf-8"),
                         hashlib.sha256).hexdigest()
    request = urllib.request.Request(url, data=body, method="POST", headers={
        "Content-Type": headers["content-type"],
        "X-Amz-Date": amz_date,
        "X-Amz-Content-Sha256": payload_hash,
        "X-Amz-Target": headers["x-amz-target"],
        "Authorization": f"AWS4-HMAC-SHA256 Credential={access_key}/{scope}, "
                         f"SignedHeaders={signed_headers}, Signature={signature}",
    })
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            raw = response.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        raw = exc.read(1024).decode("utf-8", "replace")
        code, message = "HTTPError", ""
        try:
            error = json.loads(raw)
            code = str(error.get("__type") or error.get("code") or code).split("#")[-1]
            message = str(error.get("message") or "")
        except (TypeError, ValueError):
            pass
        raise TableError(operation, exc.code, code, message) from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"{operation} network failure: {exc.reason}") from exc

def connect() -> tuple[str, str, str, str, str]:
    url, host = endpoint_config()
    region = env("GEMINI_REGION", "cn-north-4")
    access_key = env("GEMINI_ACCESS_KEY")
    secret_key = env("GEMINI_SECRET_KEY")
    if not access_key or not secret_key:
        raise RuntimeError("GEMINI_ACCESS_KEY and GEMINI_SECRET_KEY are required")
    return url, host, region, access_key, secret_key

TABLE_PREFIX = "jora_"

def ensure_table(delete: bool) -> None:
    table = env("TABLE_NAME", "jora_orders")
    if delete:
        # 只删除本项目创建的表（表名带 jora_ 前缀）；共享 GeminiDB 上
        # 其他项目的表一律不碰，即使 TABLE_NAME 被指向它们也拒绝删除。
        if not table.startswith(TABLE_PREFIX):
            print(f"[SKIP] table {table} lacks the {TABLE_PREFIX} prefix; "
                  "not created by this project, delete skipped")
            return
        try:
            send_request("DeleteTable", {"TableName": table})
            print(f"[PASS] deleted table {table}")
        except TableError as exc:
            if exc.code in {"ResourceNotFoundException", "TableNotFoundException"}:
                print(f"[PASS] table {table} already absent")
                return
            raise
        return

    try:
        send_request("CreateTable", {
            "TableName": table,
            "KeySchema": [{"AttributeName": "orderId", "KeyType": "HASH"}],
            "AttributeDefinitions": [{"AttributeName": "orderId", "AttributeType": "S"}],
            "BillingMode": CAPACITY_MODE,
        })
        print(f"[INFO] create requested for table {table}")
    except TableError as exc:
        if exc.code not in {"ResourceInUseException", "TableAlreadyExistsException"}:
            raise
        print(f"[INFO] table {table} already exists")

    deadline = time.monotonic() + 180
    while True:
        try:
            description = send_request("DescribeTable", {"TableName": table}).get("Table", {})
        except TableError as exc:
            if exc.code != "ResourceNotFoundException" or time.monotonic() >= deadline:
                raise
            time.sleep(2)
            continue
        status = str(description.get("TableStatus", ""))
        if status == "ACTIVE":
            print(f"[PASS] table {table} is ACTIVE")
            return
        if status in {"DELETING", "ARCHIVING", "ARCHIVED"}:
            raise RuntimeError(f"table {table} is not usable: {status}")
        if time.monotonic() >= deadline:
            raise TimeoutError(f"timed out waiting for table {table} to become ACTIVE")
        time.sleep(2)

def main() -> int:
    parser = argparse.ArgumentParser(description="Ensure the orders table exists")
    parser.add_argument("--delete", action="store_true", help="delete the table instead")
    args = parser.parse_args()
    ensure_table(args.delete)
    return 0

if __name__ == "__main__":
    sys.exit(main())
