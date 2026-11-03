package de.mmc.sendungen

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import java.util.Locale

class SettingsActivity : Activity() {

    private enum class Art { TEXT, EMAIL, PASSWORT, ZAHL }

    private class Feld(
        val schluessel: String,
        val titel: String,
        val wert: String,
        val platzhalter: String,
        val art: Art
    )

    private val felder = mutableMapOf<String, EditText>()
    private var zeileMitteilung: android.widget.TextView? = null
    private var zeileAkku: android.widget.TextView? = null

    override fun onResume() {
        super.onResume()
        freigabenZeigen()
    }

    private fun freigabenZeigen() {
        zeileMitteilung?.let { zustand(it, Hinweise.mitteilungenErlaubt(this)) }
        zeileAkku?.let { zustand(it, Hinweise.akkuFrei(this)) }
    }

    private fun zustand(t: android.widget.TextView, ok: Boolean) {
        t.text = if (ok) "Erlaubt ✓" else "Einrichten ›"
        t.setTextColor(if (ok) p.gruen else p.akzent)
    }

    /** Eine antippbare Zeile in einer Karte; gibt das Feld rechts für den Zustand zurück. */
    private fun freigabeZeile(karte: LinearLayout, titel: String, aktion: () -> Unit): android.widget.TextView {
        if (karte.childCount > 0) {
            karte.addView(View(this).apply { setBackgroundColor(p.trenner) },
                LinearLayout.LayoutParams(voll, 1).apply { leftMargin = dp(16) })
        }
        val zeile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(16), 0, dp(16), 0)
            isClickable = true
            isFocusable = true
            background = tippbar(0x00000000, 12, p.trenner)
            setOnClickListener { aktion() }
        }
        zeile.addView(text(titel, 17f, p.text), LinearLayout.LayoutParams(0, passend, 1f))
        val rechts = text("", 15f, p.akzent)
        zeile.addView(rechts)
        karte.addView(zeile, LinearLayout.LayoutParams(voll, passend))
        return rechts
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == Hinweise.ANFRAGE_MITTEILUNG &&
            grantResults.firstOrNull() != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Einstellungen.prefs(this).edit().putBoolean("mitteilung_abgelehnt", true).apply()
        }
        freigabenZeigen()
    }
    private lateinit var p: Palette
    private val voll = ViewGroup.LayoutParams.MATCH_PARENT
    private val passend = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        fensterFarben(p)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }

        // Zurück-Knopf und großer Titel
        box.addView(textknopf("‹ Sendungen", p) { finish() }.apply {
            setPadding(dp(4), 0, dp(12), 0)
            contentDescription = "Zurück zu den Sendungen"
        }, LinearLayout.LayoutParams(passend, dp(48)))
        box.addView(text("Einstellungen", 34f, p.text, SCHRIFT_TITEL),
            LinearLayout.LayoutParams(voll, passend).apply { bottomMargin = dp(4) })

        val hinweise = listOf(
            "Den IMAP-Server findest du im KAS von All-Inkl unter E-Mail. Als Passwort das Postfach-Passwort eintragen.",
            "Hier gehört ein App-Passwort von Google hin (16 Zeichen), nicht dein normales Passwort.",
            "Hier gehört ein App-Passwort von Google hin (16 Zeichen), nicht dein normales Passwort."
        )

        for ((i, k) in Einstellungen.konten(this).withIndex()) {
            abschnitt(
                box, k.name,
                listOf(
                    Feld("server$i", "Server", k.server, "imap.beispiel.de", Art.TEXT),
                    Feld("user$i", "E-Mail", k.user, "name@beispiel.de", Art.EMAIL),
                    Feld("passwort$i", "Passwort", k.passwort, "erforderlich", Art.PASSWORT)
                ),
                hinweise.getOrElse(i) { "" }
            )
        }

        // Live-Abfrage bei den Paketdiensten
        val pr = Einstellungen.prefs(this)
        abschnitt(
            box, "Live-Status DHL",
            listOf(
                Feld("dhl_schluessel", "API-Schlüssel", pr.getString("dhl_schluessel", "") ?: "", "von developer.dhl.com", Art.PASSWORT),
                Feld("plz", "Postleitzahl", pr.getString("plz", "") ?: "", "für genauere Angaben", Art.ZAHL)
            ),
            "Kostenlos auf developer.dhl.com: App anlegen, \"Shipment Tracking - Unified\" hinzufügen, den Consumer Key hier eintragen."
        )
        abschnitt(
            box, "Live-Status UPS",
            listOf(
                Feld("ups_id", "Client-ID", pr.getString("ups_id", "") ?: "", "von developer.ups.com", Art.PASSWORT),
                Feld("ups_geheim", "Client-Secret", pr.getString("ups_geheim", "") ?: "", "von developer.ups.com", Art.PASSWORT)
            ),
            "Auf developer.ups.com mit dem UPS-Konto anmelden, eine App mit der Tracking-API anlegen und beide Werte hier eintragen. DPD und GLS fragt die App ohne Schlüssel ab."
        )

        // Automatischer Abgleich im Hintergrund
        box.addView(text("AUTOMATIK", 13f, p.text2).apply { setPadding(dp(16), dp(24), dp(16), dp(6)) })
        val autoKarte = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(16), 0, dp(12), 0)
            background = rund(p.karte, 12)
        }
        autoKarte.addView(text("Alle 30 Minuten prüfen", 17f, p.text), LinearLayout.LayoutParams(0, passend, 1f))
        val schalter = Switch(this).apply {
            isChecked = Hintergrund.istAn(this@SettingsActivity)
            contentDescription = "Automatisch alle 30 Minuten prüfen"
            setOnCheckedChangeListener { _, an ->
                Einstellungen.prefs(this@SettingsActivity).edit().putBoolean("automatisch", an).apply()
                Hintergrund.planen(this@SettingsActivity)
            }
        }
        autoKarte.addView(schalter)
        box.addView(autoKarte, LinearLayout.LayoutParams(voll, passend))
        box.addView(text("Die App schaut im Hintergrund nach neuen Mails und aktualisiert Liste und Widget. Android kann das bei wenig Akku etwas verzögern.", 13f, p.text2).apply {
            setPadding(dp(16), dp(6), dp(16), 0)
        }, LinearLayout.LayoutParams(voll, passend))

        // Freigaben für Mitteilungen und Hintergrund
        box.addView(text("HINTERGRUND UND MITTEILUNGEN", 13f, p.text2).apply { setPadding(dp(16), dp(24), dp(16), dp(6)) })
        val freigabeKarte = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rund(p.karte, 12)
        }
        zeileMitteilung = freigabeZeile(freigabeKarte, "Mitteilungen") { Hinweise.mitteilungenAnfragen(this) }
        zeileAkku = freigabeZeile(freigabeKarte, "Akku nicht einschränken") {
            if (Hinweise.akkuFrei(this)) Hinweise.appInfo(this) else Hinweise.akkuAnfragen(this)
        }
        freigabeZeile(freigabeKarte, "App-Info öffnen (Akku, Autostart)") { Hinweise.appInfo(this) }.text = "›"
        freigabeZeile(freigabeKarte, "Probe-Mitteilung senden") {
            Hinweise.probe(this)
            if (!Hinweise.mitteilungenErlaubt(this)) Toast.makeText(this, "Erst Mitteilungen erlauben", Toast.LENGTH_SHORT).show()
        }.text = "›"
        box.addView(freigabeKarte, LinearLayout.LayoutParams(voll, passend))
        box.addView(text("Mitteilungen kommen bei neuen Paketen, bei \"kommt heute\" oder \"morgen\", bei Abholbereit und bei Zustellung. Bei Samsung zusätzlich: Einstellungen, Akku, Hintergrund-Nutzungslimits, \"Nie schlafende Apps\" und Sendungen hinzufügen. Bei Xiaomi: in der App-Info \"Autostart\" einschalten.", 13f, p.text2).apply {
            setPadding(dp(16), dp(6), dp(16), 0)
        }, LinearLayout.LayoutParams(voll, passend))

        abschnitt(
            box, "Suche",
            listOf(Feld("tage", "Tage zurück", Einstellungen.tage(this).toString(), "60", Art.ZAHL)),
            "So viele Tage zurück werden die Postfächer beim Aktualisieren durchsucht."
        )

        box.addView(hauptknopf("Speichern", p) { speichern() },
            LinearLayout.LayoutParams(voll, dp(52)).apply { topMargin = dp(28) })

        box.addView(textknopf("Protokoll anzeigen", p) { zeigeProtokoll(p) }.apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(voll, dp(48)).apply { topMargin = dp(12) })

        box.addView(textknopf("Alle Mails neu einlesen", p) {
            dialogBauer(p)
                .setTitle("Alles neu einlesen?")
                .setMessage("Die App vergisst, bis wohin sie gelesen hat, und liest beim nächsten Aktualisieren die letzten Tage noch einmal ein. Das dauert länger.")
                .setPositiveButton("Neu einlesen") { _, _ ->
                    Einstellungen.standZuruecksetzen(this)
                    Toast.makeText(this, "Wird beim nächsten Aktualisieren neu eingelesen", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(voll, dp(48)))

        // Versionsnummer ganz unten
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        box.addView(textknopf("Nach Updates suchen", p) {
            Thread {
                val neu = try {
                    Updater.pruefen(this)
                } catch (_: Exception) {
                    null
                }
                runOnUiThread {
                    if (neu != null) {
                        Toast.makeText(this, "Version ${neu.name} ist da. Auf der Startseite oben auf \"Jetzt aktualisieren\" tippen.", Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        Toast.makeText(this, "Du hast die neueste Version", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(voll, dp(48)))

        box.addView(text("Sendungen, Version $version", 13f, p.text2).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }, LinearLayout.LayoutParams(voll, passend))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.hintergrund)
            isFillViewport = true
            addView(box)
        })
    }

    /** Gruppierte Karte mit Überschrift, Zeilen und Hinweistext darunter. */
    private fun abschnitt(box: LinearLayout, ueberschrift: String, zeilen: List<Feld>, fuss: String) {
        box.addView(text(ueberschrift.uppercase(Locale.GERMAN), 13f, p.text2).apply {
            setPadding(dp(16), dp(24), dp(16), dp(6))
        })

        val karte = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rund(p.karte, 12)
        }

        zeilen.forEachIndexed { index, f ->
            if (index > 0) {
                karte.addView(View(this).apply { setBackgroundColor(p.trenner) },
                    LinearLayout.LayoutParams(voll, maxOf(1, dp(1) / 2)).apply { marginStart = dp(16) })
            }
            val zeile = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(16), 0, dp(16), 0)
            }
            zeile.addView(text(f.titel, 17f, p.text), LinearLayout.LayoutParams(dp(112), passend))

            val eingabe = EditText(this).apply {
                inputType = when (f.art) {
                    Art.TEXT -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    Art.EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    Art.PASSWORT -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    Art.ZAHL -> InputType.TYPE_CLASS_NUMBER
                }
                setText(f.wert)
                hint = f.platzhalter
                textSize = 17f
                setTextColor(p.text)
                setHintTextColor(p.text2)
                background = null
                setPadding(0, dp(12), 0, dp(12))
                contentDescription = "${f.titel} für $ueberschrift"
            }
            felder[f.schluessel] = eingabe
            zeile.addView(eingabe, LinearLayout.LayoutParams(0, passend, 1f))
            karte.addView(zeile, LinearLayout.LayoutParams(voll, passend))
        }
        box.addView(karte, LinearLayout.LayoutParams(voll, passend))

        if (fuss.isNotBlank()) {
            box.addView(text(fuss, 13f, p.text2).apply { setPadding(dp(16), dp(6), dp(16), 0) },
                LinearLayout.LayoutParams(voll, passend))
        }
    }

    private fun speichern() {
        val editor = Einstellungen.prefs(this).edit()
        for ((schluessel, eingabe) in felder) {
            val wert = eingabe.text.toString().trim()
            if (schluessel == "tage") {
                editor.putInt("tage", wert.toIntOrNull()?.coerceIn(1, 365) ?: 60)
            } else {
                editor.putString(schluessel, wert)
            }
        }
        editor.apply()
        Toast.makeText(this, "Gespeichert", Toast.LENGTH_SHORT).show()
        finish()
    }
}
