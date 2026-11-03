package de.mmc.sendungen

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Ergebnis je Postfach: Zustand 0 = nicht eingerichtet, 1 = in Ordnung, 2 = Fehler. */
data class Ergebnis(val name: String, val text: String, val zustand: Int)

/** Der eigentliche Abgleich, gemeinsam für den Knopf in der App und den Hintergrund. */
object Abgleich {
    private val sperre = ReentrantLock()

    /** Gibt null zurück, wenn gerade schon ein Abgleich läuft. */
    fun ausfuehren(context: Context, quelle: String, melde: (String) -> Unit = {}): List<Ergebnis>? {
        if (!sperre.tryLock()) return null
        try {
            val c = context.applicationContext
            Protokoll.init(c)
            val db = SendungDb(c)
            val tage = Einstellungen.tage(c)
            val version = try {
                c.packageManager.getPackageInfo(c.packageName, 0).versionName
            } catch (_: Exception) {
                "?"
            }
            Protokoll.schreib("Aktualisierung gestartet ($quelle, App-Version $version, $tage Tage)")
            val vorher = try {
                Hinweise.vorher(db)
            } catch (_: Exception) {
                emptyMap()
            }
            val ergebnisse = Einstellungen.konten(c).map { k ->
                when {
                    k.passwort.isBlank() -> Ergebnis(k.name, "Nicht eingerichtet, Passwort fehlt", 0)
                    k.server.isBlank() -> Ergebnis(k.name, "Nicht eingerichtet, Server fehlt", 0)
                    else -> try {
                        val neu = Postfach.abrufen(k, tage, db, melde)
                        Ergebnis(k.name, if (neu == 1) "1 neue Sendung" else "$neu neue Sendungen", 1)
                    } catch (e: Throwable) {
                        Protokoll.fehler(k.name, e)
                        Ergebnis(k.name, fehlerText(e), 2)
                    }
                }
            }
            // Danach den aktuellen Stand direkt bei den Paketdiensten abfragen
            try {
                Live.aktualisieren(c, db, quelle == "von Hand", melde)
            } catch (e: Throwable) {
                Protokoll.fehler("Live-Abfrage", e)
            }
            if (ergebnisse.any { it.zustand == 1 }) {
                Einstellungen.prefs(c).edit().putLong("zuletzt", System.currentTimeMillis()).apply()
            }
            try {
                Hinweise.melden(c, vorher, db)
            } catch (e: Throwable) {
                Protokoll.fehler("Mitteilungen", e)
            }
            Protokoll.schreib("Aktualisierung beendet ($quelle)")
            SendungenWidget.aktualisiereAlle(c)
            return ergebnisse
        } finally {
            sperre.unlock()
        }
    }

    fun laeuft(): Boolean = sperre.isLocked

    fun fehlerText(e: Throwable): String {
        val art = e.javaClass.simpleName
        val m = (e.message ?: "").lowercase(Locale.GERMAN)
        var ursache: Throwable? = e
        var keinNetz = false
        while (ursache != null) {
            if (ursache is java.net.UnknownHostException) keinNetz = true
            ursache = ursache.cause
        }
        return when {
            art.contains("AuthenticationFailed") || m.contains("authentication") || m.contains("invalid credentials") ->
                "Anmeldung abgelehnt, Passwort prüfen"
            keinNetz -> "Kein Internet oder Servername falsch"
            m.contains("couldn't connect") -> "Server nicht erreichbar, Servername und Internet prüfen"
            m.contains("timed out") || m.contains("timeout") -> "Zeitüberschreitung, später noch einmal versuchen"
            else -> "Fehler: ${e.message ?: art}"
        }
    }
}

/** Läuft alle 30 Minuten im Hintergrund, sobald Internet da ist. */
class AbgleichWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            // Als Vordergrunddienst laufen, damit Android den Abruf nicht nach kurzer Zeit kappt
            try {
                setForegroundAsync(Hinweise.abrufInfo(applicationContext)).get()
            } catch (e: Throwable) {
                Protokoll.init(applicationContext)
                Protokoll.schreib("Hintergrund: ohne Vordergrunddienst (${e.javaClass.simpleName})")
            }
            Abgleich.ausfuehren(applicationContext, "Hintergrund")
            Result.success()
        } catch (e: Throwable) {
            Protokoll.init(applicationContext)
            Protokoll.fehler("Hintergrund", e)
            Result.success()
        }
    }
}

object Hintergrund {
    private const val NAME = "sendungen_abgleich"

    fun istAn(c: Context): Boolean = Einstellungen.prefs(c).getBoolean("automatisch", true)

    fun planen(c: Context) {
        val wm = WorkManager.getInstance(c.applicationContext)
        if (istAn(c)) {
            val auftrag = PeriodicWorkRequestBuilder<AbgleichWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, auftrag)
        } else {
            wm.cancelUniqueWork(NAME)
        }
    }
}
