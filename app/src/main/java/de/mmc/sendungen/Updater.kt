package de.mmc.sendungen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Angaben zur neuesten Version auf GitHub. */
data class NeueVersion(val code: Long, val name: String, val apk: String, val hinweis: String)

/**
 * Prüft auf GitHub, ob es eine neuere Version gibt, lädt sie herunter
 * und öffnet den Installationsdialog von Android.
 */
object Updater {
    private const val ADRESSE =
        "https://raw.githubusercontent.com/1979mark/sendungen-app/main/update/version.json"

    fun eigeneVersion(c: Context): Long = try {
        val info = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    } catch (_: Exception) {
        0L
    }

    /** Läuft im Hintergrund-Thread. Gibt null zurück, wenn keine neuere Version da ist. */
    fun pruefen(c: Context): NeueVersion? {
        val verbindung = URL(ADRESSE + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        verbindung.connectTimeout = 10_000
        verbindung.readTimeout = 10_000
        verbindung.useCaches = false
        try {
            if (verbindung.responseCode != 200) return null
            val text = verbindung.inputStream.bufferedReader().readText()
            val j = JSONObject(text)
            val neu = NeueVersion(
                code = j.optLong("versionCode", 0),
                name = j.optString("versionName", ""),
                apk = j.optString("apk", ""),
                hinweis = j.optString("hinweis", "")
            )
            return if (neu.code > eigeneVersion(c) && neu.apk.isNotBlank()) neu else null
        } finally {
            verbindung.disconnect()
        }
    }

    /** Lädt die APK herunter (im Hintergrund-Thread) und gibt die Datei zurück. */
    fun herunterladen(c: Context, neu: NeueVersion, fortschritt: (Int) -> Unit): File {
        val ordner = File(c.cacheDir, "updates").apply { mkdirs() }
        ordner.listFiles()?.forEach { it.delete() }
        val ziel = File(ordner, "Sendungen-${neu.name}.apk")
        var adresse = neu.apk
        var verbindung: HttpURLConnection
        var umleitungen = 0
        while (true) {
            verbindung = URL(adresse).openConnection() as HttpURLConnection
            verbindung.connectTimeout = 15_000
            verbindung.readTimeout = 30_000
            verbindung.instanceFollowRedirects = true
            val code = verbindung.responseCode
            if (code in 300..399 && umleitungen < 5) {
                adresse = verbindung.getHeaderField("Location") ?: break
                verbindung.disconnect()
                umleitungen++
                continue
            }
            if (code != 200) throw IllegalStateException("Download fehlgeschlagen (Code $code)")
            break
        }
        val gesamt = verbindung.contentLengthLong
        verbindung.inputStream.use { ein ->
            ziel.outputStream().use { aus ->
                val puffer = ByteArray(32 * 1024)
                var gelesen = 0L
                while (true) {
                    val n = ein.read(puffer)
                    if (n < 0) break
                    aus.write(puffer, 0, n)
                    gelesen += n
                    if (gesamt > 0) fortschritt((gelesen * 100 / gesamt).toInt())
                }
            }
        }
        verbindung.disconnect()
        return ziel
    }

    /** Darf die App Updates installieren? Wenn nicht, öffnet sie die passende Einstellung. */
    fun installierenErlaubt(a: Activity): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        if (a.packageManager.canRequestPackageInstalls()) return true
        a.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}"))
        )
        return false
    }

    fun installieren(a: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(a, "${a.packageName}.dateien", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        a.startActivity(intent)
    }
}
