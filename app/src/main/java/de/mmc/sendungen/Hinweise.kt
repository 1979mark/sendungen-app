package de.mmc.sendungen

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo

/**
 * Mitteilungen und alle Freigaben, die Android für zuverlässige Arbeit im Hintergrund braucht:
 * Mitteilungen erlauben, Akku-Optimierung ausnehmen, Abruf als Vordergrunddienst.
 */
object Hinweise {
    private const val KANAL_PAKETE = "pakete"
    private const val KANAL_ABRUF = "abruf"
    private const val ID_ABRUF = 4711
    private const val ID_SAMMEL = 4712
    const val ANFRAGE_MITTEILUNG = 31

    fun kanaeleAnlegen(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(KANAL_PAKETE, "Pakete", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Neue Pakete, Zustellung heute, zugestellt, abholbereit"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(KANAL_ABRUF, "Abruf im Hintergrund", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Kurzer Hinweis, solange die App die Postfächer prüft"
                setShowBadge(false)
            }
        )
    }

    // ---------- Freigaben prüfen ----------

    fun mitteilungenErlaubt(c: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(c).areNotificationsEnabled()
    }

    fun akkuFrei(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(c.packageName)
    }

    fun mitteilungenAnfragen(a: Activity) {
        Einstellungen.prefs(a).edit().putBoolean("frage_mitteilung", true).apply()
        if (Build.VERSION.SDK_INT >= 33 &&
            a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !Einstellungen.prefs(a).getBoolean("mitteilung_abgelehnt", false)
        ) {
            a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), ANFRAGE_MITTEILUNG)
        } else {
            // Schon einmal abgelehnt oder älteres Android: direkt zu den Mitteilungs-Einstellungen
            oeffne(a, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName)
            })
        }
    }

    @SuppressLint("BatteryLife")
    fun akkuAnfragen(a: Activity) {
        Einstellungen.prefs(a).edit().putBoolean("frage_akku", true).apply()
        if (Build.VERSION.SDK_INT < 23) return
        val direkt = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}"))
        if (!oeffne(a, direkt)) oeffne(a, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** App-Infoseite, dort finden sich herstellerabhängig "Akku", "Autostart" oder "Im Hintergrund aktiv". */
    fun appInfo(a: Activity) {
        oeffne(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
    }

    private fun oeffne(a: Activity, i: Intent): Boolean = try {
        a.startActivity(i)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /**
     * Beim Start der App: fehlende Freigaben der Reihe nach erfragen, jede nur einmal von selbst.
     * Danach lassen sie sich jederzeit in den Einstellungen unter "Hintergrund" nachholen.
     */
    fun beimStartPruefen(a: Activity, p: Palette) {
        val prefs = Einstellungen.prefs(a)
        if (!mitteilungenErlaubt(a) && !prefs.getBoolean("frage_mitteilung", false)) {
            a.dialogBauer(p)
                .setTitle("Mitteilungen erlauben?")
                .setMessage("Die App meldet sich, wenn ein neues Paket unterwegs ist, heute zugestellt wird, abholbereit liegt oder angekommen ist.")
                .setPositiveButton("Weiter") { _, _ -> mitteilungenAnfragen(a) }
                .setNegativeButton("Später") { _, _ -> prefs.edit().putBoolean("frage_mitteilung", true).apply() }
                .show()
            return
        }
        if (!akkuFrei(a) && !prefs.getBoolean("frage_akku", false)) {
            a.dialogBauer(p)
                .setTitle("Im Hintergrund arbeiten lassen?")
                .setMessage("Damit Android die App beim Stromsparen nicht anhält, gleich bei der Akku-Frage \"Zulassen\" tippen. Die App prüft trotzdem nur alle 30 Minuten kurz die Postfächer.")
                .setPositiveButton("Weiter") { _, _ -> akkuAnfragen(a) }
                .setNegativeButton("Später") { _, _ -> prefs.edit().putBoolean("frage_akku", true).apply() }
                .show()
        }
    }

    // ---------- Hinweis während des Abrufs (Vordergrunddienst) ----------

    fun abrufInfo(c: Context): ForegroundInfo {
        kanaeleAnlegen(c)
        val n = NotificationCompat.Builder(c, KANAL_ABRUF)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Sendungen")
            .setContentText("Postfächer werden geprüft …")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(ID_ABRUF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(ID_ABRUF, n)
        }
    }

    // ---------- Paket-Mitteilungen ----------

    private fun schluessel(s: Sendung) = "${s.anbieter}|${s.nummer}"

    /** Stand vor dem Abruf: Paket -> Status. */
    fun vorher(db: SendungDb): Map<String, Int> = db.zusammengefasst().associate { schluessel(it) to it.status }

    /** Vergleicht mit dem Stand nach dem Abruf und meldet, was sich Wichtiges getan hat. */
    fun melden(c: Context, vorher: Map<String, Int>, db: SendungDb) {
        if (!Einstellungen.prefs(c).getBoolean("mitteilungen", true)) return
        if (!mitteilungenErlaubt(c)) return
        val meldungen = mutableListOf<Pair<Sendung, String>>()
        for (s in db.zusammengefasst()) {
            if (Einstellungen.istAusgeblendet(c, s)) continue
            val alt = vorher[schluessel(s)]
            val text = when {
                alt == null && s.status == Status.ZUGESTELLT -> null // altes, längst erledigtes Paket
                alt == null && s.status == Status.STORNIERT -> null
                alt == null -> "Neues Paket: ${s.kurzStatus()}"
                alt == s.status -> null
                s.status == Status.HEUTE -> "Kommt heute" + (if (s.zeitfenster.isNotBlank()) ", ${s.zeitfenster}" else "")
                s.status == Status.MORGEN -> "Kommt morgen"
                s.status == Status.ABHOLBEREIT -> "Liegt zur Abholung bereit"
                s.status == Status.ZUGESTELLT -> "Wurde zugestellt"
                else -> null
            } ?: continue
            meldungen += s to text
        }
        if (meldungen.isEmpty()) return
        kanaeleAnlegen(c)
        val nm = NotificationManagerCompat.from(c)
        val oeffnen = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try {
            if (meldungen.size > 4) {
                // Nach "Alle Mails neu einlesen" nicht 30 Einzelmeldungen schicken
                val stil = NotificationCompat.InboxStyle()
                meldungen.take(6).forEach { (s, t) -> stil.addLine("${s.anzeigeName()}: $t") }
                if (meldungen.size > 6) stil.setSummaryText("und ${meldungen.size - 6} weitere")
                nm.notify(
                    ID_SAMMEL,
                    NotificationCompat.Builder(c, KANAL_PAKETE)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("${meldungen.size} Pakete mit Neuigkeiten")
                        .setContentText("Antippen öffnet die Übersicht")
                        .setStyle(stil)
                        .setContentIntent(oeffnen)
                        .setAutoCancel(true)
                        .build()
                )
            } else {
                for ((s, t) in meldungen) {
                    nm.notify(
                        schluessel(s).hashCode(),
                        NotificationCompat.Builder(c, KANAL_PAKETE)
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setContentTitle(s.anzeigeName())
                            .setContentText("$t (${s.anbieter})")
                            .setContentIntent(oeffnen)
                            .setAutoCancel(true)
                            .build()
                    )
                }
            }
        } catch (e: SecurityException) {
            Protokoll.schreib("Mitteilung nicht erlaubt: ${e.message}")
        }
    }

    /** Probe-Mitteilung aus den Einstellungen. */
    fun probe(c: Context) {
        kanaeleAnlegen(c)
        try {
            NotificationManagerCompat.from(c).notify(
                ID_SAMMEL + 1,
                NotificationCompat.Builder(c, KANAL_PAKETE)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Sendungen")
                    .setContentText("Probe: Mitteilungen funktionieren.")
                    .setAutoCancel(true)
                    .build()
            )
        } catch (_: SecurityException) {
        }
    }
}
