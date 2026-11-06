package de.mmc.sendungen

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Antwort eines Paketdienstes. */
data class LiveStatus(
    val status: Int?,
    val text: String,
    val erwartet: String?,
    val fenster: String?,
    val ereignisse: List<Ereignis>
)

/**
 * Fragt den aktuellen Stand direkt beim Paketdienst ab.
 * DHL und UPS über die offiziellen Zugänge (Schlüssel in den Einstellungen),
 * DPD und GLS über die öffentlichen Daten ihrer Webseiten (kann sich ändern).
 */
object Live {
    private val tag = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY)
    private val minute = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.GERMANY)

    /** Läuft im Hintergrund nach dem Mail-Abruf. */
    fun aktualisieren(c: Context, db: SendungDb, vonHand: Boolean, melde: (String) -> Unit) {
        val prefs = Einstellungen.prefs(c)
        val dhlSchluessel = prefs.getString("dhl_schluessel", "")?.trim().orEmpty()
        val upsId = prefs.getString("ups_id", "")?.trim().orEmpty()
        val upsGeheim = prefs.getString("ups_geheim", "")?.trim().orEmpty()
        val plz = prefs.getString("plz", "")?.trim().orEmpty()

        // Offene und "vermutlich zugestellte" Pakete prüfen, jede Nummer nur einmal
        val grenze = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date(System.currentTimeMillis() - 5 * 86_400_000L))
        val offen = db.zusammengefasst()
            .filter { it.anbieter in listOf("DHL", "UPS", "DPD", "GLS") }
            .filter { it.status != Status.ZUGESTELLT && it.status != Status.STORNIERT }
            .filter { maxOf(it.statusZeit, it.datum).take(10) >= grenze }
        if (offen.isEmpty()) return
        val jetzt = System.currentTimeMillis()
        // Abstand je Sendung: von Hand 10 Minuten, automatisch 2 Stunden (DHL erlaubt 250 Abfragen am Tag)
        val abstand = if (vonHand) 10 * 60_000L else 2 * 60 * 60_000L
        var upsToken: String? = null
        var letzteDhl = 0L

        for ((i, s) in offen.withIndex()) {
            val zuletzt = try {
                minute.parse(s.liveZeit)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
            // Pakete, die heute kommen sollen oder schon fällig waren, öfter prüfen (alle 30 Minuten)
            val dringend = s.status == Status.HEUTE || s.vermutlichDa() || zustellText(s.erwartet) == "Zustellung heute"
            if (jetzt - zuletzt < (if (dringend && !vonHand) 25 * 60_000L else abstand)) continue
            melde("Paketdienste: ${s.anbieter} ${i + 1} von ${offen.size}")
            try {
                val ergebnis = when (s.anbieter) {
                    "DHL" -> {
                        if (dhlSchluessel.isBlank()) null else {
                            val warten = 5_200L - (System.currentTimeMillis() - letzteDhl)
                            if (warten > 0) Thread.sleep(warten)
                            letzteDhl = System.currentTimeMillis()
                            dhl(s.nummer, dhlSchluessel, plz)
                        }
                    }
                    "UPS" -> {
                        if (upsId.isBlank() || upsGeheim.isBlank()) null else {
                            if (upsToken == null) upsToken = upsAnmelden(upsId, upsGeheim)
                            ups(s.nummer, upsToken!!)
                        }
                    }
                    "DPD" -> dpd(s.nummer)
                    "GLS" -> gls(s.nummer)
                    else -> null
                }
                if (ergebnis != null) {
                    db.liveSetzen(s, ergebnis)
                    Protokoll.schreib("Live ${s.anbieter} ${s.nummer}: ${ergebnis.text}" +
                        (ergebnis.erwartet?.let { ", Zustellung $it" } ?: "") +
                        (ergebnis.fenster?.let { ", $it" } ?: ""))
                }
            } catch (e: Exception) {
                Protokoll.schreib("Live ${s.anbieter} ${s.nummer}: nicht abrufbar (${e.javaClass.simpleName}: ${e.message?.take(150)})")
            }
        }
    }

    // ---------- Hilfen ----------

    private fun holen(adresse: String, kopf: Map<String, String> = emptyMap(), koerper: String? = null): String {
        val v = URL(adresse).openConnection() as HttpURLConnection
        v.connectTimeout = 15_000
        v.readTimeout = 20_000
        v.setRequestProperty("Accept", "application/json")
        v.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Sendungen-App")
        kopf.forEach { (k, w) -> v.setRequestProperty(k, w) }
        if (koerper != null) {
            v.requestMethod = "POST"
            v.doOutput = true
            v.outputStream.use { it.write(koerper.toByteArray()) }
        }
        try {
            val code = v.responseCode
            val strom = if (code in 200..299) v.inputStream else v.errorStream
            val text = strom?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw IllegalStateException("Code $code ${text.take(120)}")
            return text
        } finally {
            v.disconnect()
        }
    }

    private fun iso(zeit: String): Date? {
        val muster = listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd")
        for (m in muster) {
            try {
                return SimpleDateFormat(m, Locale.GERMANY).parse(zeit.take(25))
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun uhr(d: Date) = SimpleDateFormat("HH:mm", Locale.GERMANY).format(d)

    private fun fensterText(von: Date?, bis: Date?): String? =
        if (von != null && bis != null) "${uhr(von)} bis ${uhr(bis)} Uhr" else null

    // ---------- DHL (offiziell) ----------

    private fun dhl(nummer: String, schluessel: String, plz: String): LiveStatus? {
        var adresse = "https://api-eu.dhl.com/track/shipments?trackingNumber=" +
            URLEncoder.encode(nummer, "UTF-8") + "&language=de"
        if (plz.isNotBlank()) adresse += "&recipientPostalCode=" + URLEncoder.encode(plz, "UTF-8")
        val j = JSONObject(holen(adresse, mapOf("DHL-API-Key" to schluessel)))
        val sh = j.optJSONArray("shipments")?.optJSONObject(0) ?: return null
        val st = sh.optJSONObject("status")
        val code = st?.optString("statusCode").orEmpty()
        val text = st?.optString("description").takeUnless { it.isNullOrBlank() }
            ?: st?.optString("status").orEmpty()
        val status = when (code) {
            "delivered" -> Status.ZUGESTELLT
            "transit" -> if (text.contains("Zustell", true) && text.contains("heute", true)) Status.HEUTE else Status.UNTERWEGS
            "pre-transit" -> Status.BESTELLT
            else -> null
        }
        val etd = iso(sh.optString("estimatedTimeOfDelivery"))
        val rahmen = sh.optJSONObject("estimatedDeliveryTimeFrame")
        val von = rahmen?.let { iso(it.optString("estimatedFrom")) }
        val bis = rahmen?.let { iso(it.optString("estimatedThrough")) }
        val ereignisse = mutableListOf<Ereignis>()
        sh.optJSONArray("events")?.let { liste ->
            for (k in 0 until liste.length()) {
                val e = liste.optJSONObject(k) ?: continue
                val d = iso(e.optString("timestamp")) ?: continue
                val ort = e.optJSONObject("location")?.optJSONObject("address")?.optString("addressLocality").orEmpty()
                val t = e.optString("description").ifBlank { e.optString("status") }
                ereignisse.add(Ereignis(minute.format(d), "DHL: $t" + if (ort.isNotBlank()) " ($ort)" else ""))
            }
        }
        return LiveStatus(status, text.ifBlank { code }, (von ?: etd)?.let { tag.format(it) }, fensterText(von, bis), ereignisse)
    }

    // ---------- UPS (offiziell) ----------

    private fun upsAnmelden(id: String, geheim: String): String {
        val basis = Base64.encodeToString("$id:$geheim".toByteArray(), Base64.NO_WRAP)
        val antwort = holen(
            "https://onlinetools.ups.com/security/v1/oauth/token",
            mapOf("Authorization" to "Basic $basis", "Content-Type" to "application/x-www-form-urlencoded"),
            "grant_type=client_credentials"
        )
        return JSONObject(antwort).getString("access_token")
    }

    private fun ups(nummer: String, token: String): LiveStatus? {
        val j = JSONObject(
            holen(
                "https://onlinetools.ups.com/api/track/v1/details/" + URLEncoder.encode(nummer, "UTF-8") + "?locale=de_DE",
                mapOf(
                    "Authorization" to "Bearer $token",
                    "transId" to UUID.randomUUID().toString().take(32),
                    "transactionSrc" to "sendungen"
                )
            )
        )
        val paket = j.optJSONObject("trackResponse")?.optJSONArray("shipment")?.optJSONObject(0)
            ?.optJSONArray("package")?.optJSONObject(0) ?: return null
        val aktuell = paket.optJSONObject("currentStatus")
        val text = aktuell?.optString("description").orEmpty()
        val aktivitaeten = paket.optJSONArray("activity") ?: JSONArray()
        val art = aktivitaeten.optJSONObject(0)?.optJSONObject("status")?.optString("type").orEmpty()
        val status = when {
            art == "D" || text.contains("zugestellt", true) || text.contains("delivered", true) -> Status.ZUGESTELLT
            text.contains("Zustellung", true) && text.contains("unterwegs", true) -> Status.HEUTE
            art == "I" || art == "P" -> Status.UNTERWEGS
            art == "M" -> Status.BESTELLT
            else -> null
        }
        val lieferTag = paket.optJSONArray("deliveryDate")?.optJSONObject(0)?.optString("date").orEmpty()
        val erwartet = if (lieferTag.length == 8) "${lieferTag.take(4)}-${lieferTag.substring(4, 6)}-${lieferTag.substring(6, 8)}" else null
        val zeit = paket.optJSONObject("deliveryTime")
        val start = zeit?.optString("startTime").orEmpty()
        val ende = zeit?.optString("endTime").orEmpty()
        val fenster = if (start.length >= 4 && ende.length >= 4)
            "${start.take(2)}:${start.substring(2, 4)} bis ${ende.take(2)}:${ende.substring(2, 4)} Uhr" else null
        val ereignisse = mutableListOf<Ereignis>()
        for (k in 0 until aktivitaeten.length()) {
            val a = aktivitaeten.optJSONObject(k) ?: continue
            val d = a.optString("date")
            val t = a.optString("time")
            if (d.length != 8 || t.length < 4) continue
            val ort = a.optJSONObject("location")?.optJSONObject("address")?.optString("city").orEmpty()
            ereignisse.add(Ereignis(
                "${d.take(4)}-${d.substring(4, 6)}-${d.substring(6, 8)} ${t.take(2)}:${t.substring(2, 4)}",
                "UPS: " + a.optJSONObject("status")?.optString("description").orEmpty() + if (ort.isNotBlank()) " ($ort)" else ""
            ))
        }
        return LiveStatus(status, text, erwartet, fenster, ereignisse)
    }

    // ---------- DPD (öffentliche Daten der Webseite) ----------

    private fun dpd(nummer: String): LiveStatus? {
        val j = JSONObject(holen("https://tracking.dpd.de/rest/plc/de_DE/" + URLEncoder.encode(nummer, "UTF-8")))
        val daten = j.optJSONObject("parcellifecycleResponse")?.optJSONObject("parcelLifeCycleData") ?: return null
        val infos = daten.optJSONArray("statusInfo") ?: JSONArray()
        var aktuell: JSONObject? = null
        for (k in 0 until infos.length()) {
            val o = infos.optJSONObject(k) ?: continue
            if (o.optBoolean("isCurrentStatus")) aktuell = o
        }
        val code = aktuell?.optString("status").orEmpty().uppercase(Locale.ROOT)
        val text = aktuell?.optString("label").orEmpty().ifBlank { code }
        val status = when {
            code.contains("DELIVERED") || text.contains("zugestellt", true) -> Status.ZUGESTELLT
            code.contains("COURIER") || code.contains("OUT_FOR") || text.contains("Zustellung", true) -> Status.HEUTE
            code.contains("PICKUP_SHOP") || text.contains("Paketshop", true) -> Status.ABHOLBEREIT
            code.isNotBlank() -> Status.UNTERWEGS
            else -> null
        }
        val ereignisse = mutableListOf<Ereignis>()
        daten.optJSONObject("scanInfo")?.optJSONArray("scan")?.let { scans ->
            for (k in 0 until scans.length()) {
                val sc = scans.optJSONObject(k) ?: continue
                val d = iso(sc.optString("date")) ?: continue
                val t = sc.optJSONObject("scanDescription")?.optJSONArray("content")?.optString(0).orEmpty()
                ereignisse.add(Ereignis(minute.format(d), "DPD: $t"))
            }
        }
        return if (status == null && text.isBlank()) null else LiveStatus(status, text, null, null, ereignisse)
    }

    // ---------- GLS (öffentliche Daten der Webseite) ----------

    private fun gls(nummer: String): LiveStatus? {
        val j = JSONObject(holen("https://gls-group.eu/app/service/open/rest/DE/de/rstt001?match=" + URLEncoder.encode(nummer, "UTF-8")))
        val tu = j.optJSONArray("tuStatus")?.optJSONObject(0) ?: return null
        val balken = tu.optJSONObject("progressBar")
        val code = balken?.optString("statusInfo").orEmpty().uppercase(Locale.ROOT)
        val text = balken?.optString("statusText").orEmpty().ifBlank { code }
        val status = when {
            code.contains("DELIVERED") && !code.contains("NOT") -> Status.ZUGESTELLT
            code.contains("INDELIVERY") -> Status.HEUTE
            code.contains("PARCELSHOP") -> Status.ABHOLBEREIT
            code.contains("PREADVICE") -> Status.BESTELLT
            code.isNotBlank() -> Status.UNTERWEGS
            else -> null
        }
        val ereignisse = mutableListOf<Ereignis>()
        tu.optJSONArray("history")?.let { h ->
            for (k in 0 until h.length()) {
                val e = h.optJSONObject(k) ?: continue
                val d = iso(e.optString("date") + "T" + e.optString("time").take(5)) ?: continue
                val ort = e.optJSONObject("address")?.optString("city").orEmpty()
                ereignisse.add(Ereignis(minute.format(d), "GLS: " + e.optString("evtDscr") + if (ort.isNotBlank()) " ($ort)" else ""))
            }
        }
        return if (status == null && text.isBlank()) null else LiveStatus(status, text, null, null, ereignisse)
    }
}
