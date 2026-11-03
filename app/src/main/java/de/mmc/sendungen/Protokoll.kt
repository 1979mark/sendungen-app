package de.mmc.sendungen

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Einfaches Protokoll in einer Datei der App.
 * Passwörter werden nie hineingeschrieben.
 */
object Protokoll {
    private var datei: File? = null

    fun init(c: Context) {
        if (datei == null) datei = File(c.applicationContext.filesDir, "protokoll.txt")
    }

    @Synchronized
    fun schreib(zeile: String) {
        val f = datei ?: return
        try {
            if (f.exists() && f.length() > 200_000) {
                f.writeText(f.readText().takeLast(100_000))
            }
            val zeit = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY).format(Date())
            f.appendText("$zeit  $zeile\n")
        } catch (_: Exception) {
        }
    }

    fun fehler(wo: String, t: Throwable) {
        schreib("$wo: FEHLER ${t.javaClass.name}: ${t.message}")
        t.stackTrace.take(12).forEach { schreib("    bei $it") }
        var ursache = t.cause
        var tiefe = 0
        while (ursache != null && tiefe < 3) {
            schreib("    Ursache: ${ursache.javaClass.name}: ${ursache.message}")
            ursache = ursache.cause
            tiefe++
        }
    }

    fun lesen(): String = try {
        datei?.takeIf { it.exists() }?.readText() ?: ""
    } catch (_: Exception) {
        ""
    }

    fun leeren() {
        try {
            datei?.writeText("")
        } catch (_: Exception) {
        }
    }
}

fun Activity.dialogBauer(p: Palette): AlertDialog.Builder = AlertDialog.Builder(
    this,
    if (p.dunkel) android.R.style.Theme_Material_Dialog_Alert
    else android.R.style.Theme_Material_Light_Dialog_Alert
)

/** Zeigt das Protokoll an und bietet Teilen (zum Beispiel per WhatsApp oder Mail an Claude). */
fun Activity.zeigeProtokoll(p: Palette) {
    Protokoll.init(this)
    val alles = Protokoll.lesen()
    // Nur die letzten 2 Durchläufe, damit beim Teilen nichts abgeschnitten wird
    val zeilen = alles.lines()
    val starts = zeilen.indices.filter { zeilen[it].contains("Aktualisierung gestartet") }
    val ab = if (starts.size >= 2) starts[starts.size - 2] else 0
    val inhalt = zeilen.drop(ab).joinToString("\n").ifBlank { "Noch keine Einträge. Erst auf Aktualisieren tippen." }
    dialogBauer(p)
        .setTitle("Protokoll")
        .setMessage(inhalt.takeLast(20_000))
        .setPositiveButton("Teilen") { _, _ ->
            val senden = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Sendungen: Protokoll")
                putExtra(Intent.EXTRA_TEXT, inhalt.takeLast(90_000))
            }
            startActivity(Intent.createChooser(senden, "Protokoll teilen"))
        }
        .setNeutralButton("Leeren") { _, _ -> Protokoll.leeren() }
        .setNegativeButton("Schließen", null)
        .show()
}
