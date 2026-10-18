#!/usr/bin/env python
"""本地 MCP 桩服务（stdio）——用于冒烟验证发布链路，不连任何真实平台。

用途：没有真实平台账号时，验证「配置 → 启动 MCP 进程 → 握手 → tools/list → 选工具 → 传参 → 调用」整条链路。

启用（开发环境）：
    set CONTENTOPS_PUBLISH_STUB=true
然后：
    GET  /api/v1/publish/platforms/status?probe=true
    POST /api/v1/publish/platforms/stub/publish   {"title":"...","content":"..."}
"""

import json
import sys

TOOLS = [
    {"name": "check_login", "description": "检查发布账号登录态",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "publish_note", "description": "发布图文笔记（桩）",
     "inputSchema": {"type": "object", "properties": {
         "title": {"type": "string"}, "content": {"type": "string"},
         "images": {"type": "array", "items": {"type": "string"}}}}},
]


def handle(request):
    method = request.get("method")
    if method == "initialize":
        return {"jsonrpc": "2.0", "id": request.get("id"),
                "result": {"protocolVersion": "2024-11-05",
                           "capabilities": {"tools": {}},
                           "serverInfo": {"name": "contentops-mcp-stub", "version": "1.0.0"}}}
    if method == "notifications/initialized":
        return None
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": request.get("id"), "result": {"tools": TOOLS}}
    if method == "tools/call":
        name = (request.get("params") or {}).get("name")
        args = (request.get("params") or {}).get("arguments") or {}
        if name == "check_login":
            text = "已登录：demo 账号（桩）"
        elif name == "publish_note":
            text = ("桩服务已接收发布请求\n标题：%s\n正文长度：%d\n图片：%s\n笔记ID：stub-note-0001"
                    % (args.get("title", ""), len(str(args.get("content", ""))), args.get("images", [])))
        else:
            return {"jsonrpc": "2.0", "id": request.get("id"),
                    "error": {"code": -32601, "message": "unknown tool: %s" % name}}
        return {"jsonrpc": "2.0", "id": request.get("id"),
                "result": {"isError": False, "content": [{"type": "text", "text": text}]}}
    return {"jsonrpc": "2.0", "id": request.get("id"),
            "error": {"code": -32601, "message": "method not found: %s" % method}}


def main():
    print("INFO contentops-mcp-stub 已启动（stdio）", flush=True)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except ValueError:
            print("WARN 非法 JSON 请求已忽略", flush=True)
            continue
        response = handle(request)
        if response is not None:
            print(json.dumps(response, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
