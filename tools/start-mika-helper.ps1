param([Parameter(Mandatory)][string]$Serial)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$package = 'com.life0423.carddock.prototype'
$component = "$package/com.life0423.carddock.MainActivity"
& $adb -s $Serial shell am start -W -n $component
if ($LASTEXITCODE -ne 0) { throw 'CardDock did not start' }
$base = @(& $adb -s $Serial shell pm path --user 0 $package | Where-Object { $_ -match '^package:/[a-zA-Z0-9/_~\.=-]+/base\.apk$' })
if ($base.Count -ne 1) { throw 'Cannot resolve CardDock base APK' }
$apk = $base[0].Substring(8)
& $adb -s $Serial shell "CLASSPATH=$apk nohup app_process /system/bin --nice-name=carddock-mika com.life0423.carddock.platform.mika.DockMikaHelper $package </dev/null >/data/local/tmp/carddock-mika.log 2>&1 &"
if ($LASTEXITCODE -ne 0) { throw 'Mika helper launch failed' }
