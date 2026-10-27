# Debloat reversible para TV Kalley (SCBC R3). Uso: .\tools\debloat_kalley.ps1 [-Serial 192.168.1.13:5555] [-Restore]
param([string]$Serial = "192.168.1.13:5555", [switch]$Restore)
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
# Solo apps que no afectan TV en vivo, HDMI, ajustes, Play Store ni GMS.
$bloat = @(
  "com.google.android.play.games",          # Play Juegos
  "com.google.android.youtube.tvmusic",     # YouTube Music
  "com.google.android.feedback",            # envio de feedback
  "com.android.tv.feedbackconsent",
  "com.creative.fastscreen.tv",             # FastScreen (duplicado de cast)
  "com.apps.wifihotspot",
  "com.apps.esticker",                      # e-sticker de fabrica
  "com.mk.tv.meeting.service",              # videoconferencia
  "com.apps.logapp",                        # logs de fabrica
  "com.apps.function.gdpr",
  "com.android.printspooler",
  "com.android.htmlviewer",
  "com.apps.atsc"                           # sintonizador ATSC (Colombia usa DVB-T2)
)
$log = "backups\kalley_evidence\debloat_log.txt"
foreach ($p in $bloat) {
  if ($Restore) { $r = & $adb -s $Serial shell cmd package install-existing --user 0 $p }
  else { $r = & $adb -s $Serial shell pm uninstall -k --user 0 $p
         if ($r -notmatch "Success") { $r = "$r | " + (& $adb -s $Serial shell pm disable-user --user 0 $p) } }
  "$p -> $r" | Tee-Object -FilePath $log -Append
}
if (-not $Restore) { & $adb -s $Serial shell pm trim-caches 2G }
& $adb -s $Serial shell df -h /data