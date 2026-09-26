#!/usr/bin/env python3
"""单次发送一份 I01 报价需求 JSON，保留文件中的单号和全部业务数据。"""

import argparse
import json
from pathlib import Path
import sys
import urllib.error
import urllib.parse
import urllib.request


# 测试前直接修改这两项。Token 填后端配置的令牌本身，不加 Bearer 前缀。
BASE_URL = "http://127.0.0.1:8081"
TOKEN = ""  # 填写你确定的 Token，至少32个 ASCII 字符且不含空白


class NoRedirect(urllib.request.HTTPRedirectHandler):
    # 一次命令只调用指定接口；重定向应由使用者确认正确地址后重新发送。
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--request", type=Path, required=True,
                        help="本次发送的一张需求单 JSON 文件，不改写文件内容")
    parser.add_argument("--output", type=Path, help="可选：保存本次接口响应")
    args = parser.parse_args()
    base_url = urllib.parse.urlsplit(BASE_URL)
    if base_url.scheme not in ("http", "https") or not base_url.hostname or base_url.query or base_url.fragment:
        parser.error("请修改脚本顶部 BASE_URL，填写 HTTP/HTTPS 服务地址，不包含查询参数或片段")
    try:
        payload = args.request.read_bytes()
        body = json.loads(payload.decode("utf-8"))
    except (OSError, ValueError):
        parser.error("无法读取请求文件，请检查路径以及 UTF-8 JSON 格式")
    if not isinstance(body, dict):
        parser.error("请求文件必须是一个 JSON 对象，表示一张需求单")
    if args.output and args.output.resolve() == args.request.resolve():
        parser.error("响应文件不能覆盖请求 JSON 文件")
    if len(TOKEN) < 32 or any(char.isspace() for char in TOKEN) or not TOKEN.isascii():
        parser.error("请填写脚本顶部 TOKEN；"
                     "必须与后端一致，至少32个ASCII字符且不含空白")

    url = BASE_URL.rstrip("/") + "/open-api/v1/oa/quotation-requests"
    print(f"POST {url}")
    print(f"请求文件：{args.request.resolve()}")
    print(f"requestId：{body.get('requestId')}，formNo：{body.get('formNo')}")
    request = urllib.request.Request(
        url, data=payload, method="POST",
        headers={"Content-Type": "application/json; charset=UTF-8",
                 "Authorization": "Bearer " + TOKEN},
    )
    opener = urllib.request.build_opener(NoRedirect())
    try:
        try:
            response = opener.open(request, timeout=45)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status = response.status
            response_body = response.read().decode("utf-8", errors="replace")
    except (urllib.error.URLError, OSError) as error:
        print(f"请求未取得完整响应（{type(error).__name__}），请检查服务地址和网络。"
              "脚本未自动重试；需要重发时使用同一份 JSON。", file=sys.stderr)
        return 1

    print(f"HTTP {status}")
    try:
        result = json.loads(response_body)
    except ValueError:
        result = None
    formatted = json.dumps(result, ensure_ascii=False, indent=2) if result is not None else response_body
    print(formatted)
    if args.output:
        try:
            args.output.write_text(formatted + "\n", encoding="utf-8")
        except OSError:
            print("请求已发送，但响应文件保存失败，请查看上方接口返回值。", file=sys.stderr)
            return 1
    data = result.get("data") if isinstance(result, dict) else None
    if 200 <= status < 300 and isinstance(data, dict) and data.get("status") == "SUCCEEDED":
        print("接收成功。相同报文重发时，接口返回原接收结果。")
        return 0
    print("未收到接收成功结果，请根据 HTTP 状态和接口响应检查；脚本未自动重试。", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
