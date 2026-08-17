# 可观测性栈管理脚本：Prometheus(9090) + Grafana(3000)
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/observability-manage.ps1 start
#   powershell -ExecutionPolicy Bypass -File scripts/observability-manage.ps1 stop
#   powershell -ExecutionPolicy Bypass -File scripts/observability-manage.ps1 status
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('start', 'stop', 'status')]
    [string]$Action
)

$cfgRoot = 'E:\JavaProjects\Contentops\content-ops-configs\local'
$promExe = 'F:\prometheus-3.13.2.windows-amd64\prometheus.exe'
$grafanaExe = 'F:\grafana-13.1.3\bin\grafana.exe'

function Test-Listening([int]$Port) {
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

switch ($Action) {
    'start' {
        if (-not (Test-Listening 9090)) {
            New-Item -ItemType Directory -Force -Path "$cfgRoot\data\prometheus" | Out-Null
            Start-Process $promExe `
                -ArgumentList "--config.file=$cfgRoot\prometheus.yml", "--storage.tsdb.path=$cfgRoot\data\prometheus" `
                -WindowStyle Hidden `
                -RedirectStandardOutput "$cfgRoot\data\prometheus.log" `
                -RedirectStandardError "$cfgRoot\data\prometheus.err.log"
            Write-Host 'Prometheus 启动中 (9090)...'
        } else {
            Write-Host 'Prometheus 已在运行 (9090)'
        }
        if (-not (Test-Listening 3000)) {
            Start-Process $grafanaExe `
                -ArgumentList 'server', "--config=$cfgRoot\grafana\grafana.ini", '--homepath=F:\grafana-13.1.3' `
                -WindowStyle Hidden `
                -RedirectStandardOutput "$cfgRoot\grafana\logs\stdout.log" `
                -RedirectStandardError "$cfgRoot\grafana\logs\stderr.log"
            Write-Host 'Grafana 启动中 (3000)...'
        } else {
            Write-Host 'Grafana 已在运行 (3000)'
        }
    }
    'stop' {
        foreach ($port in 9090, 3000) {
            $conn = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($conn) {
                Stop-Process -Id $conn.OwningProcess -Force
                Write-Host "端口 $port 已停止"
            }
        }
    }
    'status' {
        Write-Host ("Prometheus(9090): " + $(if (Test-Listening 9090) { '运行中' } else { '未运行' }))
        Write-Host ("Grafana(3000):   " + $(if (Test-Listening 3000) { '运行中' } else { '未运行' }))
    }
}
