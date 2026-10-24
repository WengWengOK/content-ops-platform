# 影刀 RPA 接入自检：检查 ShadowBot / 用户目录 / npx，并（可选）拉起影刀 MCP 探活
# 用法：
#   powershell -File scripts/yingdao-check.ps1
#   powershell -File scripts/yingdao-check.ps1 -ShadowBotPath "D:\Program Files\ShadowBot\ShadowBot.exe" -UserFolder "C:\Users\you\AppData\Roaming\ShadowBot" -Probe
param(
    [string]$ShadowBotPath = '',
    [string]$UserFolder = '',
    [switch]$Probe
)

$ErrorActionPreference = 'Stop'
$ok = $true

if (-not $ShadowBotPath) {
    $candidates = @(
        'C:\Program Files\ShadowBot\ShadowBot.exe',
        'C:\Program Files (x86)\ShadowBot\ShadowBot.exe',
        'D:\Program Files\ShadowBot\ShadowBot.exe',
        "$env:LOCALAPPDATA\Programs\ShadowBot\ShadowBot.exe"
    )
    $ShadowBotPath = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $ShadowBotPath -or -not (Test-Path $ShadowBotPath)) {
    Write-Host "[X] 未找到 ShadowBot.exe（影刀未安装或路径不同）"
    Write-Host "    下载安装：https://www.yingdao.com/client-download/"
    $ok = $false
} else {
    Write-Host "[OK] ShadowBot: $ShadowBotPath"
}

if (-not $UserFolder) {
    $candidates = @(
        "$env:APPDATA\ShadowBot",
        "$env:LOCALAPPDATA\ShadowBot",
        "$env:USERPROFILE\Documents\ShadowBot"
    )
    $UserFolder = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $UserFolder -or -not (Test-Path $UserFolder)) {
    Write-Host "[X] 未找到影刀用户文件夹（在影刀 设置 -> 用户文件夹 里查看）"
    $ok = $false
} else {
    Write-Host "[OK] 用户文件夹: $UserFolder"
}

$npx = Get-Command npx.cmd -ErrorAction SilentlyContinue
if (-not $npx) {
    Write-Host "[X] 未找到 npx.cmd（需要 Node.js）"
    $ok = $false
} else {
    Write-Host "[OK] npx: $($npx.Source)"
}

if (-not $ok) {
    Write-Host "`n请先完成以上项；影刀本地模式还要求：目标应用属于「我获取的应用」且至少手动执行过一次。"
    exit 1
}

if ($Probe) {
    Write-Host "`n拉起影刀 MCP 探活（stdio，超时 90s）…"
    $env:RPA_MODEL = 'local'
    $env:SHADOWBOT_PATH = $ShadowBotPath
    $env:USER_FOLDER = $UserFolder
    $init = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"contentops","version":"1.0.0"}}}'
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'npx.cmd'
    $psi.Arguments = '-y yingdao-mcp-server'
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $proc = [System.Diagnostics.Process]::Start($psi)
    $proc.StandardInput.WriteLine($init)
    $proc.StandardInput.Flush()
    $deadline = (Get-Date).AddSeconds(90)
    $found = $false
    while ((Get-Date) -lt $deadline -and -not $proc.HasExited) {
        $line = $proc.StandardOutput.ReadLine()
        if ($line -and $line.Contains('jsonrpc')) {
            Write-Host "[OK] MCP initialize: $($line.Substring(0, [Math]::Min(200, $line.Length)))"
            $found = $true
            break
        }
    }
    if (-not $found) {
        Write-Host "[X] 影刀 MCP 未返回 initialize（检查 Node 网络/影刀版本）"
        $err = $proc.StandardError.ReadToEnd()
        if ($err) { Write-Host $err }
    }
    if (-not $proc.HasExited) { $proc.Kill() }
}
