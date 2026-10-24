# 获取小红书 MCP 登录二维码（XHS MCP 需先运行在 18060）
# 用法： powershell -ExecutionPolicy Bypass -File scripts/xhs-login-qr.ps1
param(
    [string]$McpUrl = 'http://localhost:18060/mcp',
    [string]$OutFile = 'E:\JavaProjects\Contentops\.runtime\xhs-mcp\login-qr.png'
)

$ErrorActionPreference = 'Stop'
$headers = @{ Accept = 'application/json, text/event-stream'; 'Content-Type' = 'application/json' }

function Get-JsonRpcResult($body) {
    $raw = [string]$body
    $data = ($raw -split "`n" | Where-Object { $_.StartsWith('data: ') } |
        ForEach-Object { $_.Substring(6) } | Select-Object -Last 1)
    if (-not $data) { $data = $raw }
    return $data | ConvertFrom-Json
}

$init = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"contentops","version":"1.0.0"}}}'
$resp = Invoke-WebRequest -Uri $McpUrl -Method Post -Body $init -Headers $headers -TimeoutSec 30 -UseBasicParsing
$session = $resp.Headers['Mcp-Session-Id']
if ($session -is [array]) { $session = $session[0] }

$headers2 = @{ Accept = 'application/json, text/event-stream'; 'Content-Type' = 'application/json'; 'Mcp-Session-Id' = $session }
Invoke-WebRequest -Uri $McpUrl -Method Post -Body '{"jsonrpc":"2.0","method":"notifications/initialized"}' `
    -Headers $headers2 -TimeoutSec 20 -UseBasicParsing | Out-Null

$call = '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_login_qrcode","arguments":{}}}'
$qr = Invoke-WebRequest -Uri $McpUrl -Method Post -Body $call -Headers $headers2 -TimeoutSec 180 -UseBasicParsing
$obj = Get-JsonRpcResult $qr.Content

$text = ($obj.result.content | Where-Object { $_.type -eq 'text' } | ForEach-Object { $_.text }) -join "`n"
$img = $obj.result.content | Where-Object { $_.type -eq 'image' } | Select-Object -First 1
if (-not $img -or -not $img.data) {
    throw "没有拿到二维码图片：$text"
}

$dir = Split-Path -Parent $OutFile
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
[IO.File]::WriteAllBytes($OutFile, [Convert]::FromBase64String(($img.data -replace '\s', '')))
Write-Host $text
Write-Host "二维码已保存：$OutFile"
Start-Process $OutFile
