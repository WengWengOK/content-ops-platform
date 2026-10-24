# 小红书 MCP 启停（WSL 版：绕开 Windows Defender 对 Rod leakless.exe 的拦截）
# 用法：
#   powershell -File scripts/xhs-mcp.ps1 -Action start
#   powershell -File scripts/xhs-mcp.ps1 -Action status
#   powershell -File scripts/xhs-mcp.ps1 -Action stop
param(
    [ValidateSet('start', 'stop', 'status')]
    [string]$Action = 'status',
    [string]$Distro = 'xhs'
)

$ErrorActionPreference = 'Stop'

function Get-WslIp {
    $ips = (& wsl.exe -d $Distro -- hostname -I) -join ' '
    $ip = ($ips.Trim() -split '\s+') | Where-Object { $_ -match '^\d+\.\d+\.\d+\.\d+$' } | Select-Object -First 1
    if (-not $ip) { throw "无法获取 WSL($Distro) 的 IPv4 地址" }
    return $ip
}

switch ($Action) {
    'start' {
        & wsl.exe -d $Distro -- /bin/sh -c "pkill -x xhs-mcp-linux 2>/dev/null; cd /opt/xhs-data && nohup /opt/xhs-mcp-linux -headless -port :18060 > /tmp/xhs-linux.log 2>&1 < /dev/null &"
        Start-Sleep -Seconds 8
        $ip = Get-WslIp
        Write-Host "小红书 MCP 已启动"
        Write-Host "请把后端环境变量设为：`$env:XHS_MCP_URL='http://$ip:18060/mcp'"
        Write-Host "登录二维码：powershell -File scripts/xhs-login-qr.ps1"
    }
    'stop' {
        & wsl.exe -d $Distro -- /bin/sh -c "pkill -x xhs-mcp-linux 2>/dev/null; true"
        Write-Host "小红书 MCP 已停止"
    }
    'status' {
        $ip = Get-WslIp
        $url = "http://$ip:18060/mcp"
        try {
            $headers = @{ Accept = 'application/json, text/event-stream'; 'Content-Type' = 'application/json' }
            $init = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"status","version":"1"}}}'
            $resp = Invoke-WebRequest -Uri $url -Method Post -Body $init -Headers $headers -TimeoutSec 15 -UseBasicParsing
            Write-Host "运行中：$url (HTTP $($resp.StatusCode))"
            Write-Host "后端环境变量：`$env:XHS_MCP_URL='$url'"
        } catch {
            Write-Host "未运行：$url"
            Write-Host "启动：powershell -File scripts/xhs-mcp.ps1 -Action start"
        }
    }
}
