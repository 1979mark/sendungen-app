package de.mmc.sendungen

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var db: SendungDb
    private lateinit var p: Palette
    private lateinit var untertitel: TextView
    private lateinit var meldungsKarte: LinearLayout
    private lateinit var updateKarte: LinearLayout
    private lateinit var liste: LinearLayout
    private lateinit var knopf: TextView
    private lateinit var lader: ProgressBar
    private var laeuft = false

    private val voll = ViewGroup.LayoutParams.MATCH_PARENT
    private val passend = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Protokoll.init(this)
        val bisher = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { faden, fehler ->
            Protokoll.fehler("Absturz", fehler)
            bisher?.uncaughtException(faden, fehler)
        }
        db = SendungDb(this)
        Hintergrund.planen(this)
        p = palette()
        fensterFarben(p)
        val rand = dp(16)

        // Logo klein oben links (im Dunkelmodus auf heller Fläche, damit die Schrift lesbar bleibt)
        val logo = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.logo_mmc)
            adjustViewBounds = true
            contentDescription = "MMC-Werbung"
            if (p.dunkel) {
                background = rund(0xFFFFFFFF.toInt(), 8)
                setPadding(dp(6), dp(3), dp(6), dp(3))
            }
        }
        val logoZeile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(rand, dp(12), rand, 0)
            addView(logo, LinearLayout.LayoutParams(passend, dp(30)))
        }

        // Kopfzeile: großer Titel links, Einstellungen rechts
        val kopf = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(rand, dp(8), dp(4), 0)
        }
        kopf.addView(text("Sendungen", 34f, p.text, SCHRIFT_TITEL), LinearLayout.LayoutParams(0, passend, 1f))
        kopf.addView(textknopf("Einstellungen", p) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }.apply { contentDescription = "Einstellungen öffnen" })

        untertitel = text("", 15f, p.text2).apply { setPadding(rand, 0, rand, dp(12)) }

        meldungsKarte = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rund(p.karte, 14)
            setPadding(rand, dp(12), rand, dp(12))
            visibility = View.GONE
        }
        liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        updateKarte = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rund(p.karte, 14)
            setPadding(rand, dp(12), rand, dp(8))
            visibility = View.GONE
        }

        val inhalt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(rand, 0, rand, dp(16))
            addView(updateKarte, LinearLayout.LayoutParams(voll, passend).apply { bottomMargin = dp(8) })
            addView(meldungsKarte, LinearLayout.LayoutParams(voll, passend).apply { bottomMargin = dp(8) })
            addView(liste, LinearLayout.LayoutParams(voll, passend))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(inhalt)
        }

        lader = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(p.akzent)
            visibility = View.GONE
        }
        knopf = hauptknopf("Aktualisieren", p) { aktualisieren() }
        val unten = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(rand, dp(8), rand, dp(16))
            addView(lader, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(8)
            })
            addView(knopf, LinearLayout.LayoutParams(voll, dp(52)))
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.hintergrund)
            addView(logoZeile, LinearLayout.LayoutParams(voll, passend))
            addView(kopf, LinearLayout.LayoutParams(voll, passend))
            addView(untertitel, LinearLayout.LayoutParams(voll, passend))
            addView(scroll, LinearLayout.LayoutParams(voll, 0, 1f))
            addView(unten, LinearLayout.LayoutParams(voll, passend))
        })
    }

    override fun onResume() {
        super.onResume()
        if (!laeuft) laden()
        nachUpdateSehen()
    }

    // ---------- Update über GitHub ----------

    private var updateGeprueft = 0L

    private fun nachUpdateSehen() {
        if (System.currentTimeMillis() - updateGeprueft < 10 * 60 * 1000) return
        updateGeprueft = System.currentTimeMillis()
        Thread {
            val neu = try {
                Updater.pruefen(this)
            } catch (_: Exception) {
                null
            }
            runOnUiThread { if (neu != null) zeigeUpdate(neu) else updateKarte.visibility = View.GONE }
        }.start()
    }

    private fun zeigeUpdate(neu: NeueVersion) {
        updateKarte.removeAllViews()
        updateKarte.addView(text("UPDATE VERFÜGBAR", 13f, p.text2).apply { setPadding(0, 0, 0, dp(4)) })
        updateKarte.addView(text("Version ${neu.name} ist da", 17f, p.text, SCHRIFT_FETT))
        if (neu.hinweis.isNotBlank()) {
            updateKarte.addView(text(neu.hinweis, 15f, p.text2).apply { setPadding(0, dp(2), 0, 0) })
        }
        val status = text("", 15f, p.text2)
        updateKarte.addView(status)
        updateKarte.addView(textknopf("Jetzt aktualisieren", p) {
            if (!Updater.installierenErlaubt(this)) {
                Toast.makeText(this, "Bitte \"Aus dieser Quelle zulassen\" einschalten und zurückkehren", Toast.LENGTH_LONG).show()
                return@textknopf
            }
            status.text = "Wird geladen …"
            Thread {
                try {
                    val datei = Updater.herunterladen(this, neu) { prozent ->
                        runOnUiThread { status.text = "Wird geladen … $prozent %" }
                    }
                    runOnUiThread {
                        status.text = "Fertig geladen, Installation startet"
                        Updater.installieren(this, datei)
                    }
                } catch (e: Exception) {
                    Protokoll.fehler("Update", e)
                    runOnUiThread { status.text = "Download fehlgeschlagen, später noch einmal versuchen" }
                }
            }.start()
        }.apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(passend, dp(44)))
        updateKarte.visibility = View.VISIBLE
    }

    // ---------- Liste ----------

    private fun laden() {
        val unterwegs = db.aufgeteilt().first
        liste.removeAllViews()
        if (unterwegs.isEmpty()) {
            liste.addView(leererZustand(), LinearLayout.LayoutParams(voll, passend))
        } else {
            abschnitt("UNTERWEGS", unterwegs)
        }
        val anzahl = when (unterwegs.size) {
            0 -> "Nichts unterwegs"
            1 -> "1 Sendung unterwegs"
            else -> "${unterwegs.size} Sendungen unterwegs"
        }
        val zuletzt = Einstellungen.prefs(this).getLong("zuletzt", 0L)
        val auto = if (Hintergrund.istAn(this)) ", automatisch alle 30 Minuten" else ""
        untertitel.text = if (zuletzt > 0) "$anzahl, aktualisiert ${zeitText(zuletzt)}$auto" else anzahl
        SendungenWidget.aktualisiereAlle(this)
    }

    private fun abschnitt(titel: String, eintraege: List<Sendung>) {
        liste.addView(text(titel, 13f, p.text2).apply { setPadding(dp(16), dp(16), dp(16), dp(6)) })
        for (s in eintraege) {
            liste.addView(karte(s), LinearLayout.LayoutParams(voll, passend).apply { bottomMargin = dp(10) })
        }
    }

    private fun leererZustand(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(64), dp(24), dp(24))
        addView(text("📦", 56f, p.text).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(voll, passend))
        addView(text("Nichts unterwegs", 22f, p.text, SCHRIFT_TITEL).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(6))
        }, LinearLayout.LayoutParams(voll, passend))
        addView(text(
            "Sobald eine Versandmail eintrifft, erscheint das Paket hier. Zugestellte Pakete werden automatisch ausgeblendet.",
            15f, p.text2
        ).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(voll, passend))
    }

    private fun abzeichen(s: Sendung, groesse: Int): TextView =
        text(Anbieterfarbe.kuerzel(s.anbieter), 13f, Anbieterfarbe.schrift(s.anbieter), SCHRIFT_TITEL).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Anbieterfarbe.hintergrund(s.anbieter))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(groesse), dp(groesse))
        }

    private fun karte(s: Sendung): View {
        val statusText = s.kurzStatus()
        val zeile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(76)
            setPadding(dp(14), dp(12), dp(12), dp(12))
            background = tippbar(p.karte, 14, p.trenner)
            isClickable = true
            isFocusable = true
            contentDescription = "${s.anzeigeName()}, $statusText. Antippen zeigt die Details."
            setOnClickListener { details(s) }
            setOnLongClickListener { optionen(s); true }
        }
        zeile.addView(abzeichen(s, 44))

        val mitte = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        mitte.addView(text(s.anzeigeName(), 17f, p.text, SCHRIFT_FETT).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        mitte.addView(text(statusText, 15f, p.statusFarbe(
            if (statusText.startsWith("Heute")) Status.HEUTE else s.status
        ), SCHRIFT_FETT).apply {
            setPadding(0, dp(2), 0, dp(2))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val unterzeile = if (s.istAmazon) "Amazon-Bestellung · ${s.konto}" else "${s.anbieter} ${s.nummer}"
        mitte.addView(text(unterzeile, 13f, p.text2, if (s.istAmazon) Typeface.DEFAULT else Typeface.MONOSPACE).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        zeile.addView(mitte, LinearLayout.LayoutParams(0, passend, 1f))
        zeile.addView(text("›", 22f, p.trenner).apply {
            setPadding(dp(10), 0, 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        return zeile
    }

    // ---------- Detailansicht ----------

    private fun details(s: Sendung) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        val kopf = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        kopf.addView(abzeichen(s, 52))
        val kopfText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        kopfText.addView(text(s.anzeigeName(), 20f, p.text, SCHRIFT_TITEL).apply { maxLines = 2 })
        val statusText = s.kurzStatus()
        kopfText.addView(text(statusText, 16f, p.statusFarbe(
            if (statusText.startsWith("Heute")) Status.HEUTE else s.status
        ), SCHRIFT_FETT))
        kopf.addView(kopfText, LinearLayout.LayoutParams(0, passend, 1f))
        box.addView(kopf)

        // Eckdaten als gruppierte Karte
        val eckdaten = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rund(if (p.dunkel) 0xFF2C2C2E.toInt() else p.hintergrund, 12)
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        fun zeile(titel: String, wert: String) {
            if (wert.isBlank()) return
            val z = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, dp(8))
            }
            z.addView(text(titel, 15f, p.text2), LinearLayout.LayoutParams(dp(120), passend))
            z.addView(text(wert, 15f, p.text).apply { setTextIsSelectable(true) }, LinearLayout.LayoutParams(0, passend, 1f))
            eckdaten.addView(z)
        }
        zeile("Versender", if (s.istAmazon) "Amazon" else s.versender)
        zeile("Paketdienst", if (s.istAmazon) "Amazon (keine Sendungsnummer in der Mail)" else s.anbieter)
        if (!s.istAmazon) zeile("Sendungsnummer", s.nummer)
        if (s.istAmazon) zeile("Artikel", s.nummer)
        zeile("Zustellung", zustellText(s.erwartet).removePrefix("Zustellung ").ifBlank {
            if (s.status == Status.ZUGESTELLT) "erledigt" else "noch unbekannt"
        })
        if (s.zeitfenster.isNotBlank() && s.status != Status.ZUGESTELLT) zeile("Zeitfenster", s.zeitfenster)
        if (s.liveText.isNotBlank()) zeile("Paketdienst meldet", s.liveText)
        if (s.liveZeit.isNotBlank()) zeile("Zuletzt geprüft", try {
            SimpleDateFormat("dd.MM. 'um' HH:mm 'Uhr'", Locale.GERMANY)
                .format(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.GERMANY).parse(s.liveZeit) ?: Date())
        } catch (_: Exception) {
            s.liveZeit
        })
        zeile("Postfach", s.konto)
        box.addView(eckdaten, LinearLayout.LayoutParams(voll, passend).apply { topMargin = dp(16) })

        // Verlauf aus den Mails
        box.addView(text("VERLAUF", 13f, p.text2).apply { setPadding(dp(4), dp(18), 0, dp(6)) })
        val verlauf = db.verlauf(s)
        if (verlauf.isEmpty()) {
            box.addView(text("Noch kein Verlauf. Beim nächsten Aktualisieren wird er aufgebaut.", 15f, p.text2))
        }
        val zeitAus = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.GERMANY)
        val zeitEin = SimpleDateFormat("EE, dd.MM. 'um' HH:mm 'Uhr'", Locale.GERMANY)
        verlauf.forEachIndexed { index, e ->
            val punktFarbe = if (index == 0) p.statusFarbe(Status.erkennen(e.text) ?: s.status) else p.trenner
            val z = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }
            z.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(punktFarbe)
                }
            }, LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                topMargin = dp(6)
                marginEnd = dp(12)
            })
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(text(e.text, 15f, if (index == 0) p.text else p.text2))
            val wann = try {
                zeitEin.format(zeitAus.parse(e.zeit) ?: Date())
            } catch (_: Exception) {
                e.zeit
            }
            t.addView(text(wann, 13f, p.text2))
            z.addView(t, LinearLayout.LayoutParams(0, passend, 1f))
            box.addView(z)
        }

        val bauer = dialog()
            .setView(ScrollView(this).apply {
                setBackgroundColor(p.karte)
                addView(box)
            })
            .setPositiveButton("Fertig", null)
            .setNeutralButton(if (s.istAmazon) "Bei Amazon" else "Beim Paketdienst") { _, _ -> oeffnen(s) }
        if (!s.istAmazon) {
            bauer.setNegativeButton("Nummer kopieren") { _, _ -> kopieren(s) }
        }
        bauer.show()
    }

    // ---------- Aktionen ----------

    private fun oeffnen(s: Sendung) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.link())))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Kein Browser gefunden", Toast.LENGTH_SHORT).show()
        }
    }

    private fun kopieren(s: Sendung) {
        val ablage = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ablage.setPrimaryClip(ClipData.newPlainText("Sendungsnummer", s.nummer))
        Toast.makeText(this, "Nummer kopiert", Toast.LENGTH_SHORT).show()
    }

    private fun dialog(): AlertDialog.Builder = dialogBauer(p)

    private fun optionen(s: Sendung) {
        dialog()
            .setTitle(s.anzeigeName())
            .setItems(arrayOf("Details anzeigen", "Beim Paketdienst öffnen", "Nummer kopieren", "Aus der Liste entfernen")) { _, welche ->
                when (welche) {
                    0 -> details(s)
                    1 -> oeffnen(s)
                    2 -> kopieren(s)
                    3 -> entfernenFragen(s)
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun entfernenFragen(s: Sendung) {
        dialog()
            .setTitle("Sendung entfernen?")
            .setMessage("\"${s.anzeigeName()}\" wird ausgeblendet und taucht auch beim nächsten Aktualisieren nicht wieder auf.")
            .setPositiveButton("Entfernen") { _, _ ->
                db.entfernen(s)
                laden()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun aktualisieren() {
        if (laeuft) return
        if (Abgleich.laeuft()) {
            Toast.makeText(this, "Läuft gerade im Hintergrund, gleich noch einmal versuchen", Toast.LENGTH_SHORT).show()
            return
        }
        laeuft = true
        knopf.text = "Wird aktualisiert …"
        knopf.alpha = 0.6f
        lader.visibility = View.VISIBLE
        meldungsKarte.visibility = View.GONE
        // Bildschirm bleibt an, damit das Handy die Verbindung nicht kappt
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val melde: (String) -> Unit = { zeile -> runOnUiThread { untertitel.text = zeile } }

        Thread {
            val ergebnisse = Abgleich.ausfuehren(this, "von Hand", melde)
            runOnUiThread {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                laeuft = false
                knopf.text = "Aktualisieren"
                knopf.alpha = 1f
                lader.visibility = View.GONE
                if (ergebnisse != null) zeigeErgebnis(ergebnisse)
                laden()
            }
        }.start()
    }

    private fun zeigeErgebnis(ergebnisse: List<Ergebnis>) {
        meldungsKarte.removeAllViews()
        meldungsKarte.addView(text("LETZTE AKTUALISIERUNG", 13f, p.text2).apply { setPadding(0, 0, 0, dp(6)) })
        for ((name, status, zustand) in ergebnisse) {
            val farbe = when (zustand) {
                1 -> p.gruen
                2 -> p.rot
                else -> p.text2
            }
            val zeile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            zeile.addView(text(name, 15f, p.text, SCHRIFT_FETT))
            zeile.addView(text(status, 15f, farbe))
            meldungsKarte.addView(zeile, LinearLayout.LayoutParams(voll, passend))
        }
        meldungsKarte.addView(textknopf("Protokoll anzeigen", p) { zeigeProtokoll(p) }.apply {
            setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(passend, dp(44)))
        meldungsKarte.visibility = View.VISIBLE
    }

    // ---------- Hilfen ----------

    private fun zeitText(millis: Long): String =
        SimpleDateFormat("dd.MM. 'um' HH:mm 'Uhr'", Locale.GERMANY).format(Date(millis))
}
