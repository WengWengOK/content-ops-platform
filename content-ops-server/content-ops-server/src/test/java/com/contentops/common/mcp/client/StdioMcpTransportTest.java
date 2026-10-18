package com.contentops.common.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * stdio 传输测试：真起一个子进程，按行收发 JSON-RPC（并跳过进程打印的日志行）。
 *
 * <p>桩用 Python 写（本机/CI 有 Python 才跑；没有则跳过，不影响其它测试）。
 */
class StdioMcpTransportTest {

    private static final String STUB = """
            import sys, json
            print("INFO stub mcp server started", flush=True)
            print("正在等待客户端握手...", flush=True)
            for line in sys.stdin:
                line = line.strip()
                if not line:
                    continue
                try:
                    req = json.loads(line)
                except Exception:
                    continue
                method = req.get("method")
                if method == "notifications/initialized":
                    continue
                if method == "initialize":
                    resp = {"jsonrpc": "2.0", "id": req["id"],
                            "result": {"protocolVersion": "2024-11-05",
                                       "serverInfo": {"name": "stub-douyin", "version": "1.0"}}}
                elif method == "tools/list":
                    resp = {"jsonrpc": "2.0", "id": req["id"], "result": {"tools": [
                        {"name": "check_login", "description": "检查登录态"},
                        {"name": "publish_video", "description": "上传视频到抖音"}]}}
                elif method == "tools/call":
                    name = req["params"]["name"]
                    args = req["params"].get("arguments") or {}
                    if name == "publish_video":
                        text = "已提交上传 " + str(args.get("video_path"))
                    else:
                        text = "ok"
                    resp = {"jsonrpc": "2.0", "id": req["id"],
                            "result": {"isError": False,
                                       "content": [{"type": "text", "text": text}]}}
                else:
                    resp = {"jsonrpc": "2.0", "id": req["id"],
                            "error": {"code": -32601, "message": "method not found"}}
                print(json.dumps(resp, ensure_ascii=False), flush=True)
            """;

    private static boolean pythonAvailable() {
        try {
            Process process = new ProcessBuilder("python", "--version")
                    .redirectErrorStream(true).start();
            boolean exited = process.waitFor(15, TimeUnit.SECONDS);
            return exited && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName("stdio 全链路：握手 → tools/list → tools/call（跳过进程日志行）")
    void stdioRoundTrip() throws Exception {
        assumeTrue(pythonAvailable(), "本机没有 python，跳过 stdio 进程测试");
        Path script = Files.createTempFile("mcp-stub-", ".py");
        Files.writeString(script, STUB, StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder("python", script.toString());
        builder.redirectErrorStream(true);
        builder.environment().putAll(StdioMcpTransport.defaultUtf8Env());
        Process process = builder.start();
        StdioMcpTransport transport = new StdioMcpTransport(process);
        McpClient client = new McpClient("douyin", transport, new ObjectMapper(), Duration.ofSeconds(30));
        try {
            assertThat(client.listTools()).extracting(McpClient.McpToolInfo::name)
                    .containsExactly("check_login", "publish_video");

            McpClient.McpCallResult result = client.callTool("publish_video",
                    Map.of("video_path", "C:/works/demo.mp4", "desc", "AI 提效实测"));

            assertThat(result.ok()).isTrue();
            assertThat(result.text()).contains("已提交上传").contains("C:/works/demo.mp4");
            assertThat(transport.describe()).contains("stdio:");
        } finally {
            transport.close();
            process.destroyForcibly();
            Files.deleteIfExists(script);
        }
    }
}
