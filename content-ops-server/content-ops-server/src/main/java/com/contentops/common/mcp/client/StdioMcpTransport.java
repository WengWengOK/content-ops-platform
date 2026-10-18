package com.contentops.common.mcp.client;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * stdio 传输：拉起本地 MCP server 进程，按行收发 JSON-RPC。
 *
 * <p>进程生命周期跟随客户端：{@link #close()} 时销毁，避免每次发送都重启进程（浏览器自动化 MCP 启动很慢）。
 */
@Slf4j
public class StdioMcpTransport implements McpTransport {

    private final List<String> command;
    private final Map<String, String> env;
    private final String workDir;
    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private Process externalProcess;

    public StdioMcpTransport(List<String> command, Map<String, String> env, String workDir) {
        this.command = resolveForCurrentOs(command);
        // Python 写的 MCP server 在管道里默认用系统编码，中文会变乱码；
        // 这里默认强制 UTF-8（MCP 规范要求 UTF-8，对 Node 实现无副作用）。
        Map<String, String> merged = new java.util.LinkedHashMap<>();
        merged.putAll(defaultUtf8Env());
        if (env != null) {
            merged.putAll(env);
        }
        this.env = merged;
        this.workDir = workDir;
    }

    /** 强制 UTF-8 的环境变量（测试里自定义起进程时也复用，避免中文乱码）。 */
    public static Map<String, String> defaultUtf8Env() {
        return Map.of("PYTHONIOENCODING", "utf-8", "PYTHONUTF8", "1");
    }

    /**
     * 在 Windows 上把 {@code npx} / {@code node} / {@code uvx} 解析成真实可执行文件。
     *
     * <p>坑：Windows 上这些是 {@code npx.cmd} 而不是 {@code npx}，{@link ProcessBuilder} 直接启动会报
     * "Cannot run program"，社区 MCP server 的安装说明基本都用裸名（npx -y xxx），所以这里替他解析。
     */
    private static List<String> resolveForCurrentOs(List<String> command) {
        if (command == null || command.isEmpty()) {
            return command;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return resolveCommand(command, System.getenv("PATH"), windows);
    }

    /** 解析逻辑（纯函数，便于单测）：把裸可执行名按 PATH + PATHEXT 展开成绝对路径。 */
    public static List<String> resolveCommand(List<String> command, String pathEnv, boolean windows) {
        if (!windows || command == null || command.isEmpty()) {
            return command;
        }
        String exe = command.get(0);
        if (exe.contains("/") || exe.contains("\\") || exe.contains(".")) {
            return command; // 已经是路径或带扩展名，交给 ProcessBuilder
        }
        if (pathEnv == null || pathEnv.isBlank()) {
            return command;
        }
        for (String ext : new String[]{".cmd", ".exe", ".bat"}) {
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                java.io.File candidate = new java.io.File(dir, exe + ext);
                if (candidate.isFile()) {
                    List<String> resolved = new java.util.ArrayList<>(command);
                    resolved.set(0, candidate.getAbsolutePath());
                    return resolved;
                }
            }
        }
        return command;
    }

    /** 测试用：直接接管一个已启动的进程（例如脚本桩）。 */
    StdioMcpTransport(Process process) {
        this.command = List.of("(injected)");
        this.env = Map.of();
        this.workDir = null;
        this.externalProcess = process;
    }

    private synchronized void ensureStarted() {
        if (process != null && process.isAlive()) {
            return;
        }
        if (externalProcess != null) {
            process = externalProcess;
        } else {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().putAll(env);
            if (workDir != null && !workDir.isBlank()) {
                builder.directory(new java.io.File(workDir));
            }
            builder.redirectErrorStream(false);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            try {
                process = builder.start();
            } catch (IOException e) {
                throw new McpClientException("启动 MCP 进程失败：" + String.join(" ", command), e);
            }
            log.info("[MCP-stdio] 已启动 {} (pid={})", String.join(" ", command), process.pid());
        }
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public synchronized void notify(String requestJson) {
        ensureStarted();
        try {
            stdin.write(requestJson);
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            throw new McpClientException("向 MCP 进程写入通知失败", e);
        }
    }

    @Override
    public synchronized String exchange(String requestJson, Duration timeout) {
        ensureStarted();
        if (process == null || !process.isAlive()) {
            throw new McpClientException("MCP 进程未运行：" + String.join(" ", command));
        }
        try {
            stdin.write(requestJson);
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            throw new McpClientException("向 MCP 进程写入失败", e);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (System.nanoTime() < deadline) {
                if (stdout.ready()) {
                    String line = stdout.readLine();
                    if (line == null) {
                        throw new McpClientException("MCP 进程已退出（stdout 关闭）");
                    }
                    if (McpProtocol.looksLikeJsonRpc(line)) {
                        return line;
                    }
                    log.debug("[MCP-stdio] 跳过非协议输出: {}", line);
                } else {
                    TimeUnit.MILLISECONDS.sleep(25);
                }
            }
        } catch (IOException e) {
            throw new McpClientException("读取 MCP 进程输出失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpClientException("等待 MCP 响应被中断", e);
        }
        throw new McpClientException("MCP 响应超时（" + timeout.toSeconds() + "s）：" + String.join(" ", command));
    }

    @Override
    public String describe() {
        return "stdio:" + String.join(" ", command);
    }

    @Override
    public synchronized void close() {
        if (externalProcess != null) {
            externalProcess = null;
            process = null;
            return;
        }
        if (process != null) {
            process.destroy();
            process = null;
            log.info("[MCP-stdio] 已停止 {}", String.join(" ", command));
        }
    }
}
