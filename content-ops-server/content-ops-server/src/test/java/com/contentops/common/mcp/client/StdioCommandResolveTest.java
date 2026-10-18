package com.contentops.common.mcp.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Windows 可执行名解析测试：{@code npx} → {@code npx.cmd}。
 *
 * <p>社区 MCP server 的启动命令基本都是裸名（npx/node/uvx），Windows 上直接 ProcessBuilder 会失败。
 */
class StdioCommandResolveTest {

    @Test
    @DisplayName("Windows：按 PATH 把 npx 解析成 npx.cmd 绝对路径")
    void resolvesCmdShim() throws IOException {
        Path fakeBin = Files.createTempDirectory("fake-npm");
        Path npxCmd = fakeBin.resolve("npx.cmd");
        Files.writeString(npxCmd, "@echo off\r\n");
        try {
            List<String> resolved = StdioMcpTransport.resolveCommand(
                    List.of("npx", "-y", "@modelcontextprotocol/server-everything"),
                    fakeBin.toString(), true);

            assertThat(resolved.get(0)).isEqualTo(npxCmd.toAbsolutePath().toString());
            assertThat(resolved).hasSize(3).containsExactlyElementsOf(
                    List.of(npxCmd.toAbsolutePath().toString(), "-y", "@modelcontextprotocol/server-everything"));
        } finally {
            Files.deleteIfExists(npxCmd);
            Files.deleteIfExists(fakeBin);
        }
    }

    @Test
    @DisplayName("Windows：优先 .cmd，其次 .exe")
    void prefersCmdThenExe() throws IOException {
        Path bin = Files.createTempDirectory("fake-bin");
        Path py = bin.resolve("python.exe");
        Files.writeString(py, "");
        try {
            List<String> resolved = StdioMcpTransport.resolveCommand(
                    List.of("python", "scripts/mcp-stub-server.py"), bin.toString(), true);

            assertThat(resolved.get(0)).isEqualTo(py.toAbsolutePath().toString());
        } finally {
            Files.deleteIfExists(py);
            Files.deleteIfExists(bin);
        }
    }

    @Test
    @DisplayName("非 Windows 或已是路径：原样返回")
    void leavesCommandUntouched() {
        assertThat(StdioMcpTransport.resolveCommand(List.of("npx", "-y", "x"), "/usr/local/bin", false))
                .containsExactly("npx", "-y", "x");
        assertThat(StdioMcpTransport.resolveCommand(List.of("./local-server", "--port", "1"), "C:/bin", true))
                .containsExactly("./local-server", "--port", "1");
    }

    @Test
    @DisplayName("PATH 里找不到：返回原命令（让 ProcessBuilder 报出真实错误）")
    void keepsOriginalWhenNotFound() {
        assertThat(StdioMcpTransport.resolveCommand(List.of("no-such-tool", "arg"), "/tmp/definitely-missing", true))
                .containsExactly("no-such-tool", "arg");
    }
}
