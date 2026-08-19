# ContentOps dedicated PostgreSQL manager (port 5433, data: E:\JavaProjects\Contentops\.pgdata)
# Usage:
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
            Write-Host "PostgreSQL already running on port $pgPort"
        } else {
            Start-Process "$pgBin\postgres.exe" -ArgumentList "-D `"$pgData`" -p $pgPort" `
                -WindowStyle Hidden `
                -RedirectStandardOutput "$pgData\pg.stdout.log" `
                -RedirectStandardError "$pgData\pg.stderr.log"
            Start-Sleep -Seconds 5
            if (Test-Listening $pgPort) {
                Write-Host "PostgreSQL started (port $pgPort, data $pgData)"
            } else {
                Write-Host "Start failed, check $pgData\pg.stderr.log"
            }
        }
    }
    'stop' {
        if (Test-Listening $pgPort) {
            $conn = Get-NetTCPConnection -State Listen -LocalPort $pgPort | Select-Object -First 1
            Stop-Process -Id $conn.OwningProcess -Force
            Write-Host "PostgreSQL stopped"
        } else {
            Write-Host "PostgreSQL not running"
        }
    }
    'status' {
        if (Test-Listening $pgPort) {
            Write-Host "PostgreSQL running (port $pgPort)"
        } else {
            Write-Host "PostgreSQL not running"
        }
    }
}
