# 内容平台专属 PostgreSQL 实例管理脚本（端口 5433，数据目录 E:\JavaProjects\Contentops\.pgdata）
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/pg-manage.ps1 start
#   powershell -ExecutionPolicy Bypass -File scripts/pg-manage.ps1 stop
#   powershell -ExecutionPolicy Bypass -File scripts/pg-manage.ps1 status
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('start', 'stop', 'status')]
    [string]$Action
)

$pgBin = 'F:\PostgreSQL\18\bin'
$pgData = 'E:\JavaProjects\Contentops\.pgdata'
$pgPort = 5433

function Test-Listening([int]$Port) {
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

switch ($Action) {
    'start' {
        if (Test-Listening $pgPort) {
            Write-Host "PostgreSQL 已在端口 $pgPort 运行"
        } else {
            Start-Process "$pgBin\postgres.exe" -ArgumentList "-D `"$pgData`" -p $pgPort" `
                -WindowStyle Hidden `
                -RedirectStandardOutput "$pgData\pg.stdout.log" `
                -RedirectStandardError "$pgData\pg.stderr.log"
            Start-Sleep -Seconds 5
            if (Test-Listening $pgPort) {
                Write-Host "PostgreSQL 已启动（端口 $pgPort，数据目录 $pgData）"
            } else {
                Write-Host "启动失败，请查看 $pgData\pg.stderr.log"
            }
        }
    }
    'stop' {
        if (Test-Listening $pgPort) {
            $conn = Get-NetTCPConnection -State Listen -LocalPort $pgPort | Select-Object -First 1
            Stop-Process -Id $conn.OwningProcess -Force
            Write-Host "PostgreSQL 已停止"
        } else {
            Write-Host "PostgreSQL 未在运行"
        }
    }
    'status' {
        if (Test-Listening $pgPort) {
            Write-Host "PostgreSQL 运行中（端口 $pgPort）"
        } else {
            Write-Host "PostgreSQL 未运行"
        }
    }
}
