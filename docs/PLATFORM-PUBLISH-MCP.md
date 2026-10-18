# 多平台发布走 MCP（把作品真正传上去）

> 结论先说：**不自己重新实现各平台上传**，而是接社区现成的平台 MCP server。
> 平台上传协议常变、几乎都要登录态（浏览器 profile / Cookie / AppID），
> 社区 MCP 已经把这两件难事做完了；我们只负责把「作品内容」映射成工具参数。

---

## 一、调研到的可用 MCP server

| 平台 | 项目 | 传输 | 说明 |
|------|------|------|------|
| 抖音 | [WJZ-P/douyin-upload-mcp-skill](https://github.com/WJZ-P/douyin-upload-mcp-skill) | **stdio** | 基于 CDP（Chrome DevTools Protocol）的创作者平台自动化，视频与图文全自动发布 |
| 小红书 | [xpzouying/xiaohongshu-mcp](https://github.com/xpzouying/xiaohongshu-mcp) | **HTTP + MCP** | 支持 HTTP API 和 MCP 工具调用（评论采集模块已在用它的 HTTP 接口） |
| 公众号 | [caol64/wenyan-mcp](https://github.com/caol64/wenyan-mcp) | **stdio / 远程** | 文颜：Markdown 排版后直接发布，支持本地 Stdio 模式与 Client–Server 远程模式 |
| B 站 | [34892002/bilibili-mcp-js](https://github.com/34892002/bilibili-mcp-js) | HTTP | 以视频搜索为主，发布能力有限（配置里保留占位，可替换） |
| 多平台参考 | [dreammis/social-auto-upload](https://github.com/dreammis/social-auto-upload) | 非 MCP | 15.5k★ 自动化上传（抖音/小红书/视频号/tiktok/youtube/bilibili），可包一层 MCP |

> 互通性验证用的是官方参考实现 `@modelcontextprotocol/server-filesystem`（见第五节实测）。

---

## 二、架构

```
POST /api/v1/publish/platforms/{platform}/publish
        │
        ├─ 该平台配了 MCP？ ── 是 ──▶ McpClient（握手 → tools/list → tools/call）
        │                              ├─ stdio：拉起本地进程（npx/node/python）
        │                              └─ http：Streamable HTTP JSON-RPC
        │                                   │
        │                                   └─ 工具名：配置的 publish-tool 或按强语义识别
        │                                      参数：作品字段 → param-names 映射
        │
        └─ 否 ──▶ 明确返回「未真实上传」（channel=simulated, ok=false），**绝不伪造成功**
```

三层分离：

1. `common/mcp/client` —— 协议层（传输、握手、工具调用），与业务无关；
2. `publish/mcp` —— 配置层 + 发布编排（工具选择、参数映射、回退与审计）；
3. 平台差异全部落在 `application.yml` 的 `param-names` 里，不动代码。

---

## 三、配置

```yaml
contentops:
  publish:
    mcp:
      enabled: true
      initialize-timeout-ms: 60000
      platforms:
        douyin:
          enabled: true                  # 先本地登录抖音创作者平台再打开
          transport: stdio
          command: ["npx", "-y", "douyin-upload-mcp"]
          env:
            BROWSER_DEBUG_PORT: "40821"  # CDP 调试端口（复用已登录浏览器）
          timeout-ms: 300000
          param-names:                   # 我们的规范字段 → 该工具的参数名
            title: title
            content: desc
            images: images
            video: video_path
          extra-params: { }
          publish-tool: publish_video     # 不填则按强语义从 tools/list 里识别

        xiaohongshu:
          enabled: true
          transport: http
          url: http://127.0.0.1:18060/mcp
          token: ${XHS_MCP_TOKEN:}

        wechat:
          enabled: true
          transport: stdio
          command: ["npx", "-y", "@wenyan-md/mcp"]
          param-names:
            content: markdown            # 公众号走 Markdown 排版
            cover: cover
```

规范字段（可被映射）：`title` / `content` / `images` / `cover` / `video` / `tags`，另可用 `extra-params` 追加常量。

---

## 四、接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/publish/platforms/status?probe=` | 各平台配置总览；`probe=true` 时按平台拉 `tools/list`（会拉起 stdio 进程，按需用） |
| GET | `/api/v1/publish/platforms/fields` | 规范字段列表 |
| POST | `/api/v1/publish/platforms/{platform}/publish` | 发布作品（含 `dryRun` 演练模式） |
| POST | `/api/v1/publish/platforms/{platform}/tools/{tool}/call` | 直接调工具（排查登录态，如 `check_login`） |
| POST | `/api/v1/publish/platforms/{platform}/reset` | 重建 MCP 客户端（改完配置或进程挂掉后） |

发布请求体：

```json
{
  "title": "AI 提效实测：周报从 40 分钟到 8 分钟",
  "content": "正文……",
  "images": ["https://cdn/1.jpg"],
  "coverUrl": "https://cdn/cover.jpg",
  "videoPath": "C:/works/demo.mp4",
  "tags": ["AI", "提效"],
  "dryRun": false
}
```

返回：`channel`（`mcp` / `mcp-dry-run` / `simulated`）、`ok`、`tool`、`detail`、`elapsedMs`。

---

## 五、实测（本机真实进程 + 真实协议）

| 场景 | 结果 |
|------|------|
| 桩 MCP 通道探活 | 真实拉起 stdio 进程 → 握手 → `tools/list` 返回 2 个工具（`check_login` / `publish_note`） |
| 桩通道演练模式 | `channel=mcp-dry-run`，回显映射后参数（中文完好：`title=AI 提效实测…`） |
| 桩通道真实上传 | `channel=mcp, ok=true, tool=publish_note, elapsedMs=135`，返回「笔记ID：stub-note-0001」 |
| 未配置平台（douyin/wechat） | `channel=simulated, ok=false`，提示去 `contentops.publish.mcp.platforms.<平台>` 配置 |
| **官方参考 MCP server** | `@modelcontextprotocol/server-filesystem` 握手成功，列出 **14 个真实工具**（`read_file`/`create_directory`/…）——第三方互通验证 |
| 用文件系统 server 当发布通道 | 守卫拦住并列出可用工具，提示显式配置 `publish-tool` |
| 单元测试 | MCP 协议/传输/发布链路 24 个用例（含 stdio 真进程、HTTP 桩服务、Windows 解析） |

---

## 六、踩坑记录（都已修，都有测试保护）

1. **通知也等响应 → 每次白等满超时**：`notifications/initialized` 按规范不该有响应，
   最初复用 `exchange()` 等响应，stdio 下把 30s 超时等满。现在拆出 `notify()` 只发不等
   （stdin 写完即返回 / HTTP 只 POST 不读体），实测同一条链路从 30s 降到 0.29s。
2. **Windows 上 `npx` 其实是 `npx.cmd`**：ProcessBuilder 直接启动裸名会 "Cannot run program"，
   而社区 MCP 的安装命令清一色是裸名。现在按 PATH + PATHEXT 解析 `.cmd/.exe/.bat`。
3. **弱语义猜工具很危险**：接了官方 filesystem MCP 后，`create` 弱词让 `create_directory`
   被当成发布工具，带着发布参数去建目录（服务端报参数校验错误）。
   现在只认强语义（publish / upload / post_note / 发布 / 上传…），识别不出来就要求显式配置，
   并把可用工具列在报错里。
4. **Python 写的 stdio server 中文乱码**：管道里 Python 默认用系统编码，Java 侧按 UTF-8 解码就花了。
   现在 stdio 环境默认注入 `PYTHONIOENCODING=utf-8` / `PYTHONUTF8=1`（Node 实现无副作用）。
5. **一个平台一个常驻进程**：stdio MCP 启动慢（浏览器自动化要几十秒），客户端按平台缓存，
   `reset` 接口用于改配置或进程挂掉后重建；`@PreDestroy` 统一回收。

---

## 七、已知限制

| 限制 | 说明 |
|------|------|
| 没有真实平台账号 | 本机验证用的是桩通道 + 官方参考 server；接真平台需要各自的登录态（抖音创作者平台登录 / 小红书扫码 / 公众号 AppID） |
| 视频/图片的本地路径 | 抖音与小红书基本只接受**本地文件路径**，图文作品里的图片是 URL，发布前需要先落盘（`images` 传本地路径即可） |
| 平台差异仍在 | 每个 MCP server 的参数名/形状不同，靠 `param-names` 适配；接入新平台时先用 `dry-run` + `tools/{tool}/call` 摸清参数 |
| 未进工作流编排 | 目前是独立发布入口；接进 4 阶段流水线的「发布阶段」是下一步（复用同一 Service 即可） |
