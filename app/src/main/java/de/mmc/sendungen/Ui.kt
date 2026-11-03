package de.mmc.sendungen

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Gestaltung nach den Grundsätzen der Human Interface Guidelines:
 * ruhige Grautöne, eine Akzentfarbe für alles Bedienbare,
 * abgerundete Karten, große Titel, Tippflächen ab 48 dp, hell und dunkel.
 */
class Palette(
    val hintergrund: Int,
    val karte: Int,
    val text: Int,
    val text2: Int,
    val trenner: Int,
    val akzent: Int,
    val rot: Int,
    val gruen: Int,
    val orange: Int,
    val lila: Int,
    val dunkel: Boolean
)

fun Context.palette(): Palette {
    val dunkel = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    return if (dunkel) {
        Palette(
            hintergrund = 0xFF000000.toInt(),
            karte = 0xFF1C1C1E.toInt(),
            text = 0xFFFFFFFF.toInt(),
            text2 = 0xFF98989F.toInt(),
            trenner = 0xFF38383A.toInt(),
            akzent = 0xFF0A84FF.toInt(),
            rot = 0xFFFF453A.toInt(),
            gruen = 0xFF30D158.toInt(),
            orange = 0xFFFF9F0A.toInt(),
            lila = 0xFFBF5AF2.toInt(),
            dunkel = true
        )
    } else {
        Palette(
            hintergrund = 0xFFF2F2F7.toInt(),
            karte = 0xFFFFFFFF.toInt(),
            text = 0xFF000000.toInt(),
            text2 = 0xFF6C6C70.toInt(),
            trenner = 0xFFC6C6C8.toInt(),
            akzent = 0xFF007AFF.toInt(),
            rot = 0xFFD70015.toInt(),
            gruen = 0xFF248A3D.toInt(),
            orange = 0xFFC93400.toInt(),
            lila = 0xFF8944AB.toInt(),
            dunkel = false
        )
    }
}

fun Context.dp(wert: Int): Int = (wert * resources.displayMetrics.density).roundToInt()

fun Context.rund(farbe: Int, radiusDp: Int): GradientDrawable =
    GradientDrawable().apply {
        setColor(farbe)
        cornerRadius = dp(radiusDp).toFloat()
    }

/** Hintergrund mit sichtbarer Rückmeldung beim Antippen. */
fun Context.tippbar(farbe: Int, radiusDp: Int, welle: Int): Drawable =
    RippleDrawable(
        ColorStateList.valueOf(welle),
        rund(farbe, radiusDp),
        rund(0xFFFFFFFF.toInt(), radiusDp)
    )

val SCHRIFT_FETT: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
val SCHRIFT_TITEL: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

fun Context.text(
    inhalt: String,
    groesseSp: Float,
    farbe: Int,
    schrift: Typeface = Typeface.DEFAULT
): TextView = TextView(this).apply {
    text = inhalt
    textSize = groesseSp
    setTextColor(farbe)
    typeface = schrift
}

/** Gefüllter Hauptknopf, 52 dp hoch, volle Breite. */
fun Context.hauptknopf(beschriftung: String, p: Palette, aktion: () -> Unit): TextView =
    text(beschriftung, 17f, 0xFFFFFFFF.toInt(), SCHRIFT_FETT).apply {
        gravity = Gravity.CENTER
        minHeight = dp(52)
        background = tippbar(p.akzent, 14, 0x40FFFFFF)
        isClickable = true
        isFocusable = true
        setOnClickListener { aktion() }
    }

/** Textknopf in Akzentfarbe, wie in einer Navigationsleiste. */
fun Context.textknopf(beschriftung: String, p: Palette, aktion: () -> Unit): TextView =
    text(beschriftung, 17f, p.akzent).apply {
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(48)
        setPadding(dp(12), 0, dp(12), 0)
        background = tippbar(0x00000000, 10, p.trenner)
        isClickable = true
        isFocusable = true
        setOnClickListener { aktion() }
    }

/** Statusleiste und Navigationsleiste passend zum Hintergrund färben. */
fun Activity.fensterFarben(p: Palette) {
    window.statusBarColor = p.hintergrund
    window.navigationBarColor = p.hintergrund
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = if (p.dunkel) {
        0
    } else {
        var flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        flags
    }
}

/** Erkennungsfarben der Paketdienste (nur Farbflächen, keine Logos). */
object Anbieterfarbe {
    fun hintergrund(anbieter: String): Int = when (anbieter) {
        "UPS" -> 0xFF5E3A1E.toInt()
        "DHL" -> 0xFFFFCC00.toInt()
        "DPD" -> 0xFFDC0032.toInt()
        "GLS" -> 0xFF0A2896.toInt()
        "Hermes" -> 0xFF0091CD.toInt()
        "Amazon" -> 0xFF232F3E.toInt()
        else -> 0xFF8E8E93.toInt()
    }

    fun schrift(anbieter: String): Int =
        if (anbieter == "DHL") 0xFF3D2B00.toInt() else 0xFFFFFFFF.toInt()

    fun kuerzel(anbieter: String): String =
        if (anbieter.length <= 3) anbieter.uppercase() else anbieter.take(2).uppercase()
}

/** Farbe für den Status einer Sendung. */
fun Palette.statusFarbe(code: Int): Int = when (code) {
    Status.UNTERWEGS -> akzent
    Status.MORGEN -> orange
    Status.HEUTE -> gruen
    Status.ABHOLBEREIT -> lila
    Status.STORNIERT -> rot
    else -> text2
}

/** "heute", "morgen", "am Mi., 05.11." für einen Tag im Format yyyy-MM-dd. */
fun zustellText(tag: String): String {
    if (tag.isBlank()) return ""
    return try {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.GERMANY)
        val ziel = format.parse(tag) ?: return ""
        val heute = format.parse(format.format(java.util.Date())) ?: return ""
        val tage = ((ziel.time - heute.time) / 86_400_000L).toInt()
        when (tage) {
            0 -> "Zustellung heute"
            1 -> "Zustellung morgen"
            in 2..6 -> "Zustellung am " + java.text.SimpleDateFormat("EE, dd.MM.", java.util.Locale.GERMANY).format(ziel)
            else -> if (tage < 0) "" else "Zustellung am " + java.text.SimpleDateFormat("dd.MM.", java.util.Locale.GERMANY).format(ziel)
        }
    } catch (e: Exception) {
        ""
    }
}

/** Kurzer Statustext für Karte und Widget. */
fun Sendung.kurzStatus(): String {
    val zustellung = zustellText(erwartet)
    val fenster = if (zeitfenster.isNotBlank()) zeitfenster.replace(" bis ", " und ").let { "zwischen $it" } else ""
    return when {
        status == Status.ZUGESTELLT -> "Zugestellt"
        (status == Status.HEUTE || zustellung == "Zustellung heute") && fenster.isNotEmpty() -> "Heute $fenster"
        zustellung == "Zustellung morgen" && fenster.isNotEmpty() -> "Morgen $fenster"
        status == Status.HEUTE || zustellung == "Zustellung heute" -> "Heute in Zustellung"
        zustellung.isNotEmpty() && status != Status.ABHOLBEREIT -> zustellung
        else -> Status.text(status)
    }
}

fun Sendung.anzeigeName(): String = when {
    istAmazon -> nummer
    versender.isNotBlank() && !versender.equals(anbieter, ignoreCase = true) -> versender
    else -> "$anbieter-Paket"
}
