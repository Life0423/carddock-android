param(
    [Parameter(Mandatory)][string]$Serial,
    [ValidateRange(1, 600)][int]$TimeoutSeconds = 90
)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
$previous = ''

# 点击新建模板的试运行后调用。只向卡坞询问阶段，不接管无障碍或读取来源页面。
do {
    $dump = & $adb -s $Serial shell dumpsys activity com.life0423.carddock/.MainActivity carddock-trial
    if ($LASTEXITCODE -ne 0) { throw '无法读取卡坞试运行阶段。' }
    $line = $dump | Where-Object { $_ -match '^\s*CardDockTrial phase=' } | Select-Object -Last 1
    if ($line -and $line -match 'phase=(\w+) preview=(true|false) reader=(true|false)') {
        $phase = $Matches[1]
        if ($previous -ne $line) {
            Write-Output ("{0:HH:mm:ss} {1}" -f (Get-Date), $line.Trim())
            $previous = $line
        }
        if ($phase -eq 'SUCCEEDED' -and $line -match 'preview=true') { exit 0 }
        if ($phase -in 'CLOSED', 'FAILED', 'NEEDS_USER', 'CANCELLED', 'INTERRUPTED') { exit 1 }
    }
    Start-Sleep -Milliseconds 350
} while ([DateTime]::UtcNow -lt $deadline)
throw '未在限定时间内取得试运行结果，请查看卡坞当前页面。'
