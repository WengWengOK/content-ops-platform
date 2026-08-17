# 启动后端：自动加载 content-ops-configs/local/alerts.env（飞书/企微 Webhook + 模型 Key）
# 用法：powershell -ExecutionPolicy Bypass -File scripts/start-backend.ps1

$jar = 'E:\JavaProjects\Contentops\content-ops-server\content-ops-server\target\content-ops-server-1.0.0.jar'
$envFile = 'E:\JavaProjects\Contentops\content-ops-configs\local\alerts.env'

if (Test-Path $envFile) {
    Get-Content $envFile -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith('#')) {
            $kv = $line -split '=', 2
            if ($kv.Count -eq 2) {
                Set-Item -Path "Env:$($kv[0].Trim())" -Value $kv[1].Trim()
                Write-Host "已加载环境变量: $($kv[0].Trim())"
            }
        }
    }
} else {
    Write-Host "未找到 $envFile（可从 alerts.env.example 复制），使用默认配置"
}

Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 2
Start-Process -FilePath 'D:\develop\Java\jdk-21\bin\java.exe' -ArgumentList "-jar `"$jar`"" `
    -WindowStyle Hidden `
    -RedirectStandardOutput 'E:\JavaProjects\Contentops\backend.out.log' `
    -RedirectStandardError 'E:\JavaProjects\Contentops\backend.err.log'
Write-Host '后端启动中（约 50 秒）...'
