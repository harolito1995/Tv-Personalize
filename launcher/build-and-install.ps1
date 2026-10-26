# Build and update HarolHome TV on one explicitly selected Android TV.
# The stock launcher remains enabled; app data is retained with adb install -r.
param(
    [Parameter(Mandatory = $true)][string]$TvSerial,
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [switch]$EnableAudioPause,
    [switch]$GrantLocation
)
$ErrorActionPreference = 'Stop'
$oldLocation = Get-Location
Set-Location $PSScriptRoot
try {
    if (-not (Test-Path -LiteralPath $AdbPath)) { throw "ADB not found: $AdbPath" }
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
    if (-not (Test-Path local.properties)) {
        $sdkPath = $env:ANDROID_HOME -replace '\\', '\\\\'
        "sdk.dir=$sdkPath" | Set-Content -Path local.properties -Encoding Ascii
    }
    .\gradlew.bat :app:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }

    $state = & $AdbPath -s $TvSerial get-state 2>$null
    if ($LASTEXITCODE -ne 0 -or "$state".Trim() -ne 'device') {
        throw "TV serial '$TvSerial' is not connected/authorized. Check adb devices -l."
    }
    $apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
    & $AdbPath -s $TvSerial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK update failed; app data was not cleared.' }

    & $AdbPath -s $TvSerial shell cmd package set-home-activity --user 0 com.harolhome.tv/com.harolhome.tv.MainActivity
    if ($LASTEXITCODE -ne 0) { throw 'Could not select HarolHome as Home. The stock launcher remains enabled.' }

    if ($GrantLocation) {
        & $AdbPath -s $TvSerial shell pm grant com.harolhome.tv android.permission.ACCESS_FINE_LOCATION
        if ($LASTEXITCODE -ne 0) { throw 'Could not grant location permission.' }
    }
    if ($EnableAudioPause) {
        & $AdbPath -s $TvSerial shell cmd notification allow_listener com.harolhome.tv/com.harolhome.tv.MediaSessionListener
        if ($LASTEXITCODE -ne 0) { throw 'Could not enable the media-session listener.' }
    }
    Write-Host 'HarolHome updated. User data and the stock launcher were preserved.'
}
finally {
    Set-Location $oldLocation
}
