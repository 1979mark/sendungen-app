# Holt den aktuellen Quellcode der Sendungen-App von GitHub in den Projektordner auf dem XPS.
$ziel = "C:\KI-Projekte\27_Sendungsverfolgung\Android-App"
$basis = "https://raw.githubusercontent.com/1979mark/sendungen-app/main"
$dateien = @(
  "app/build.gradle.kts",
  "app/src/main/AndroidManifest.xml",
  "app/src/main/java/de/mmc/sendungen/Logik.kt",
  "app/src/main/java/de/mmc/sendungen/Ui.kt",
  "app/src/main/java/de/mmc/sendungen/Live.kt",
  "app/src/main/java/de/mmc/sendungen/Hinweise.kt",
  "app/src/main/java/de/mmc/sendungen/Abgleich.kt",
  "app/src/main/java/de/mmc/sendungen/MainActivity.kt",
  "app/src/main/java/de/mmc/sendungen/SettingsActivity.kt",
  "app/src/main/java/de/mmc/sendungen/SendungenWidget.kt",
  "app/src/main/java/de/mmc/sendungen/Updater.kt",
  "app/src/main/java/de/mmc/sendungen/Protokoll.kt",
  "LIESMICH.txt"
)
foreach ($d in $dateien) {
  $pfad = Join-Path $ziel ($d -replace "/", "\")
  Invoke-WebRequest -UseBasicParsing -Uri "$basis/$d`?t=$(Get-Random)" -OutFile $pfad
  Write-Host "geholt: $d"
}
Write-Host "Fertig. Jetzt in Android Studio Sync und Build APK."
