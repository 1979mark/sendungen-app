package de.mmc.sendungen

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.FolderClosedException
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.StoreClosedException
import javax.mail.UIDFolder
import javax.mail.search.AndTerm
import javax.mail.search.BodyTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.SearchTerm
import javax.mail.search.SubjectTerm

data class Konto(
    val name: String,
    val server: String,
    val port: Int,
    val user: String,
    val passwort: String
)

data class Sendung(
    val konto: String,
    val anbieter: String,
    val nummer: String,
    val datum: String,
    val absender: String,
    val betreff: String,
    val status: Int = Status.ERFASST,
    val statusZeit: String = "",
    val versender: String = "",
    val erwartet: String = "",
    val zeitfenster: String = "",
    val liveText: String = "",
    val liveZeit: String = ""
) {
    fun link(): String = Anbieter.link(anbieter, nummer)
    val istAmazon: Boolean get() = anbieter == "Amazon"
}

data class Ereignis(val zeit: String, val text: String)

private data class Muster(val regex: Regex, val kontext: Boolean, val link: String)

/** Erkennung der Sendungsnummern. */
object Anbieter {
    private val muster = linkedMapOf(
        "UPS" to Muster(
            Regex("\\b1Z[0-9A-Z]{16}\\b"), false,
            "https://www.ups.com/track?loc=de_DE&tracknum="
        ),
        "DHL" to Muster(
            Regex("\\b(?:JJD\\d{18,}|\\d{20})\\b"), true,
            "https://www.dhl.de/de/privatkunden/pakete-empfangen/verfolgen.html?piececode="
        ),
        "DPD" to Muster(
            Regex("\\b\\d{14}\\b"), true,
            "https://tracking.dpd.de/parcelstatus?locale=de_DE&query="
        ),
        "GLS" to Muster(
            Regex("\\b\\d{11}\\b"), true,
            "https://gls-group.eu/DE/de/paketverfolgung?match="
        ),
        "Hermes" to Muster(
            Regex("\\b\\d{16}\\b"), true,
            "https://www.myhermes.de/empfangen/sendungsverfolgung/#/?orderNo="
        )
    )

    private val woerter = listOf("sendungs", "paket", "track", "verfolg", "parcel", "zustell")

    fun finden(text: String): Set<Pair<String, String>> {
        val fund = mutableSetOf<Pair<String, String>>()
        for ((name, m) in muster) {
            for (treffer in m.regex.findAll(text)) {
                if (m.kontext) {
                    val start = maxOf(0, treffer.range.first - 120)
                    val ende = minOf(text.length, treffer.range.last + 121)
                    val fenster = text.substring(start, ende).lowercase(Locale.GERMAN)
                    if (woerter.none { fenster.contains(it) }) continue
                }
                fund.add(Pair(name, treffer.value))
            }
        }
        return fund
    }

    fun link(anbieter: String, nummer: String): String =
        if (anbieter == "Amazon") "https://www.amazon.de/gp/css/order-history"
        else muster[anbieter]?.link.orEmpty() + nummer

    private val amazonBetreff = Regex(
        "^(bestellt|versendet|in zustellung|zugestellt|geliefert)\\s*:\\s*(.+)$",
        RegexOption.IGNORE_CASE
    )

    /** Liefert bei Amazon-Mails wie "Versendet: „Artikel...“" den Artikelnamen. */
    fun amazonArtikel(betreff: String): String? {
        val m = amazonBetreff.find(betreff.trim()) ?: return null
        var name = m.groupValues[2].trim().trimStart('„', '"', '“', '”', '»', '«', '\'')
        for (ende in listOf("...", "…", "“", "\"", "”")) {
            val i = name.indexOf(ende)
            if (i > 0) name = name.substring(0, i)
        }
        name = name.trim()
        return if (name.length >= 3) name.take(60) else null
    }
}

/** Zustand einer Sendung, aus dem Betreff der Mails abgeleitet. */
object Status {
    const val ERFASST = 0
    const val BESTELLT = 1
    const val UNTERWEGS = 2
    const val MORGEN = 3
    const val HEUTE = 4
    const val ABHOLBEREIT = 5
    const val ZUGESTELLT = 6
    const val STORNIERT = 7

    fun text(code: Int): String = when (code) {
        BESTELLT -> "Bestellt"
        UNTERWEGS -> "Unterwegs"
        MORGEN -> "Zustellung morgen"
        HEUTE -> "Heute in Zustellung"
        ABHOLBEREIT -> "Liegt zur Abholung bereit"
        ZUGESTELLT -> "Zugestellt"
        STORNIERT -> "Storniert"
        else -> "Erfasst"
    }

    private fun enthaelt(b: String, vararg w: String) = w.any { b.contains(it) }

    fun erkennen(betreff: String): Int? {
        val b = betreff.lowercase(Locale.GERMAN)
        return when {
            enthaelt(b, "storniert") -> STORNIERT
            enthaelt(b, "zugestellt", "geliefert", "delivered", "abgegeben", "nebenan") &&
                !enthaelt(b, "wird zugestellt", "wird geliefert", "terminiert") -> ZUGESTELLT
            enthaelt(b, "abholbereit", "zur abholung", "abholbenachrichtigung", "liegt bereit", "abholen") -> ABHOLBEREIT
            enthaelt(b, "heute", "in zustellung", "gleich zugestellt", "out for delivery", "wird gleich") -> HEUTE
            b.contains("morgen") -> MORGEN
            enthaelt(b, "versendet", "versandt", "verschickt", "unterwegs", "auf dem weg", "shipped",
                "versandbestätigung", "versandbenachrichtigung", "versand") -> UNTERWEGS
            enthaelt(b, "bestellt", "bestellbestätigung", "bestellung") -> BESTELLT
            else -> null
        }
    }

    private val tagFormat get() = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY)
    private val datumImText = Regex(
        "(voraussichtlich|zustellung|lieferung|zugestellt|geliefert|ankunft)[^\\n]{0,50}?(\\d{1,2})\\.(\\d{1,2})\\.(\\d{2,4})?",
        RegexOption.IGNORE_CASE
    )

    private val fensterImText = Regex(
        "(\\d{1,2})[:.](\\d{2})\\s*(?:uhr)?\\s*(?:-|–|bis|und)\\s*(\\d{1,2})[:.](\\d{2})",
        RegexOption.IGNORE_CASE
    )
    private val fensterWoerter = listOf("zustell", "lieferung", "ankunft", "zwischen", "heute", "voraussichtlich", "kommt", "liefer")

    /** Zeitfenster wie "10:00 bis 14:00 Uhr" aus dem Mailtext oder null. */
    fun zeitfenster(text: String): String? {
        for (m in fensterImText.findAll(text)) {
            val start = maxOf(0, m.range.first - 100)
            val umfeld = text.substring(start, m.range.first).lowercase(Locale.GERMAN)
            if (fensterWoerter.none { umfeld.contains(it) }) continue
            val (h1, m1, h2, m2) = m.destructured
            val a = h1.toInt()
            val b = h2.toInt()
            if (a !in 5..22 || b !in 6..23 || b <= a) continue
            return "%02d:%s bis %02d:%s Uhr".format(a, m1, b, m2)
        }
        return null
    }

    /** Erwarteter Zustelltag als "yyyy-MM-dd" oder null. */
    fun erwartet(code: Int?, mailZeit: Date, text: String): String? {
        val kal = Calendar.getInstance().apply { time = mailZeit }
        when (code) {
            HEUTE -> return tagFormat.format(kal.time)
            MORGEN -> {
                kal.add(Calendar.DAY_OF_MONTH, 1)
                return tagFormat.format(kal.time)
            }
            ZUGESTELLT, STORNIERT -> return ""
        }
        val m = datumImText.find(text) ?: return null
        val tag = m.groupValues[2].toIntOrNull() ?: return null
        val monat = m.groupValues[3].toIntOrNull() ?: return null
        if (tag !in 1..31 || monat !in 1..12) return null
        var jahr = m.groupValues[4].toIntOrNull() ?: kal.get(Calendar.YEAR)
        if (jahr < 100) jahr += 2000
        val ziel = Calendar.getInstance().apply {
            clear()
            set(jahr, monat - 1, tag)
        }
        // Datum in der Vergangenheit oder weit weg ist meist ein Bestell- oder Rechnungsdatum
        val abstand = (ziel.timeInMillis - mailZeit.time) / 86_400_000L
        if (abstand < -1 || abstand > 30) return null
        return tagFormat.format(ziel.time)
    }
}

fun absenderName(roh: String): String {
    val name = roh.substringBefore("<").trim().trim('"').trim()
    return if (name.isNotEmpty()) name else roh.substringAfter("<").substringBefore(">").trim()
}

private fun istPaketdienst(name: String): Boolean {
    val n = name.lowercase(Locale.GERMAN)
    return listOf("ups", "dhl", "dpd", "gls", "hermes", "deutsche post", "paket").any { n.contains(it) }
}

/** Liest die Postfächer direkt auf dem Handy per IMAP (nur lesend). */
object Postfach {
    private val hinweisWoerter = listOf(
        "Sendungsnummer", "Paketnummer", "Sendungsverfolgung", "Trackingnummer", "Tracking",
        "Paket", "Sendung", "Lieferung", "Zustellung", "zugestellt", "geliefert",
        "versandt", "Versand", "Versendet"
    )
    private val absenderWoerter = listOf("dhl", "ups", "dpd", "gls", "hermes", "amazon")
    private const val MAX_MAILS = 300

    private fun eigenschaften(k: Konto) = Properties().apply {
        this["mail.store.protocol"] = "imaps"
        this["mail.imaps.host"] = k.server
        this["mail.imaps.port"] = k.port.toString()
        this["mail.imaps.ssl.enable"] = "true"
        this["mail.imaps.connectiontimeout"] = "20000"
        this["mail.imaps.timeout"] = "30000"
        this["mail.imaps.writetimeout"] = "20000"
    }

    /** Baut die Verbindung auf, bei Netzproblemen bis zu 3 Versuche. */
    private fun verbinden(session: Session, k: Konto, melde: (String) -> Unit): Store {
        var letzter: Exception? = null
        for (versuch in 1..3) {
            try {
                val store = session.getStore("imaps")
                store.connect(k.server, k.port, k.user, k.passwort)
                return store
            } catch (e: AuthenticationFailedException) {
                throw e
            } catch (e: Exception) {
                letzter = e
                Protokoll.schreib("${k.name}: Verbindung fehlgeschlagen (${e.message}), Versuch $versuch von 3")
                if (versuch < 3) {
                    melde("${k.name}: keine Verbindung, neuer Versuch …")
                    Thread.sleep(4000L * versuch)
                }
            }
        }
        throw letzter ?: IllegalStateException("Keine Verbindung")
    }

    private fun istVerbindungsfehler(e: Throwable): Boolean {
        var t: Throwable? = e
        var tiefe = 0
        while (t != null && tiefe < 6) {
            if (t is FolderClosedException || t is StoreClosedException ||
                t is java.net.SocketException || t is java.net.SocketTimeoutException ||
                t is java.net.UnknownHostException
            ) return true
            val m = t.message ?: ""
            if (m.contains("BYE") || m.contains("connection", ignoreCase = true)) return true
            t = t.cause
            tiefe++
        }
        return false
    }

    fun abrufen(k: Konto, tage: Int, db: SendungDb, melde: (String) -> Unit = {}): Int {
        val session = Session.getInstance(eigenschaften(k))
        melde("${k.name}: Verbindung wird aufgebaut")
        Protokoll.schreib("${k.name}: verbinde mit ${k.server}:${k.port} als ${k.user}")
        var store = verbinden(session, k, melde)
        Protokoll.schreib("${k.name}: angemeldet")

        var neu = 0
        try {
            var ordner = store.getFolder("INBOX").apply { open(Folder.READ_ONLY) }
            Protokoll.schreib("${k.name}: Posteingang enthält ${ordner.messageCount} Mails")

            // Stand vom letzten Abruf
            val prefs = Einstellungen.prefs(db.ctx)
            val schluessel = "stand_${k.name}"
            val uf = ordner as UIDFolder
            val validity = uf.uidValidity
            val letzteUid = if (prefs.getLong("${schluessel}_validity", -1L) == validity) {
                prefs.getLong("${schluessel}_uid", 0L)
            } else {
                0L
            }
            val uidNext = try {
                (ordner as? com.sun.mail.imap.IMAPFolder)?.uidNext ?: -1L
            } catch (_: Exception) {
                -1L
            }

            var uids: List<Long>
            if (letzteUid > 0) {
                // Folgeabruf: keine Suche, nur die seitdem eingegangenen Mails holen
                melde("${k.name}: neue Mails werden geholt")
                uids = uf.getMessagesByUID(letzteUid + 1, UIDFolder.LASTUID)
                    .map { uf.getUID(it) }
                    .filter { it > letzteUid }
                    .sorted()
                Protokoll.schreib("${k.name}: ${uids.size} neue Mails seit dem letzten Abruf")
            } else {
                // Erster Abruf: der Server sucht passende Mails der letzten Tage
                melde("${k.name}: erster Abruf, Mails werden gesucht (kann 1 bis 2 Minuten dauern)")
                val gmail = if (k.server.contains("gmail", ignoreCase = true)) {
                    try {
                        gmailSuche(ordner, tage)
                    } catch (e: Exception) {
                        Protokoll.schreib("${k.name}: Gmail-Schnellsuche ging nicht (${e.message}), nutze normale Suche")
                        null
                    }
                } else {
                    null
                }
                uids = (gmail ?: normaleSuche(ordner, tage, k).map { uf.getUID(it) }).sorted()
                Protokoll.schreib("${k.name}: ${uids.size} passende Mails der letzten $tage Tage")
            }
            if (uids.size > MAX_MAILS) {
                uids = uids.takeLast(MAX_MAILS)
                Protokoll.schreib("${k.name}: nur die neuesten $MAX_MAILS werden gelesen")
            }
            var allesGelesen = true

            var i = 0
            var wiederholungen = 0
            while (i < uids.size) {
                melde("${k.name}: Mail ${i + 1} von ${uids.size}")
                try {
                    val msg = (ordner as UIDFolder).getMessageByUID(uids[i])
                    if (msg != null) neu += verarbeiten(k, msg, db)
                    i++
                } catch (e: Exception) {
                    if (istVerbindungsfehler(e) && wiederholungen < 5) {
                        wiederholungen++
                        Protokoll.schreib("${k.name}: Verbindung bei Mail ${i + 1} unterbrochen (${e.message}), verbinde neu ($wiederholungen von 5)")
                        melde("${k.name}: Verbindung unterbrochen, verbinde neu …")
                        try {
                            store.close()
                        } catch (_: Exception) {
                        }
                        Thread.sleep(3000L * wiederholungen)
                        store = verbinden(session, k, melde)
                        ordner = store.getFolder("INBOX").apply { open(Folder.READ_ONLY) }
                    } else {
                        if (istVerbindungsfehler(e)) allesGelesen = false
                        Protokoll.fehler("${k.name}: Mail ${i + 1}", e)
                        i++
                    }
                }
            }

            if (allesGelesen) {
                val neuerStand = maxOf(letzteUid, uids.maxOrNull() ?: 0L, if (uidNext > 0) uidNext - 1 else 0L)
                prefs.edit()
                    .putLong("${schluessel}_validity", validity)
                    .putLong("${schluessel}_uid", neuerStand)
                    .apply()
                Protokoll.schreib("${k.name}: Stand gemerkt, nächstes Mal geht es ab hier weiter")
            } else {
                Protokoll.schreib("${k.name}: nicht alle Mails gelesen, Stand bleibt beim letzten Abruf")
            }
            try {
                ordner.close(false)
            } catch (_: Exception) {
            }
        } finally {
            try {
                store.close()
            } catch (_: Exception) {
            }
        }
        Protokoll.schreib("${k.name}: fertig, $neu neue Sendungen")
        return neu
    }

    private fun normaleSuche(ordner: Folder, tage: Int, k: Konto): Array<Message> {
        val seit = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -tage) }.time
        val datumsFilter = ReceivedDateTerm(ComparisonTerm.GE, seit)
        val begriffe: List<SearchTerm> =
            hinweisWoerter.map { BodyTerm(it) } +
                hinweisWoerter.map { SubjectTerm(it) } +
                absenderWoerter.map { FromStringTerm(it) }
        return try {
            ordner.search(AndTerm(datumsFilter, OrTerm(begriffe.toTypedArray())))
        } catch (e: Exception) {
            Protokoll.schreib("${k.name}: Suche auf dem Server ging nicht (${e.message}), suche nur nach Datum")
            ordner.search(datumsFilter)
        }
    }

    /** Gmail-eigene Suche (deutlich schneller als die normale Volltextsuche). */
    private fun gmailSuche(ordner: Folder, tage: Int): List<Long>? {
        val imap = ordner as? com.sun.mail.imap.IMAPFolder ?: return null
        val woerter = hinweisWoerter.map { it.lowercase(Locale.GERMAN) } + absenderWoerter.map { "from:$it" }
        val anfrage = "newer_than:${tage}d (" + woerter.joinToString(" OR ") + ")"
        @Suppress("UNCHECKED_CAST")
        return imap.doCommand { protokoll ->
            val argumente = com.sun.mail.iap.Argument()
            argumente.writeString(anfrage)
            val antworten = protokoll.command("UID SEARCH X-GM-RAW", argumente)
            val ergebnis = mutableListOf<Long>()
            for (r in antworten) {
                if (r is com.sun.mail.imap.protocol.IMAPResponse && r.keyEquals("SEARCH")) {
                    while (true) {
                        val n = r.readLong()
                        if (n <= 0) break
                        ergebnis.add(n)
                    }
                }
            }
            protokoll.notifyResponseHandlers(antworten)
            protokoll.handleResult(antworten[antworten.size - 1])
            ergebnis
        } as List<Long>
    }

    private val zeitFormat get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.GERMANY)

    /** Wertet eine Mail aus und trägt Sendungen und Verlauf ein. Gibt die Zahl neuer Sendungen zurück. */
    private fun verarbeiten(k: Konto, msg: Message, db: SendungDb): Int {
        val absender = msg.from?.firstOrNull()?.toString() ?: ""
        val betreff = msg.subject ?: ""
        val zeitpunkt = msg.receivedDate ?: msg.sentDate ?: Date()
        val zeit = zeitFormat.format(zeitpunkt)
        val text = textVon(msg)
        val gesamt = betreff + "\n" + text

        val status = Status.erkennen(betreff)
        val erwartet = Status.erwartet(status, zeitpunkt, gesamt)
        val fenster = if (status == Status.ZUGESTELLT) "" else Status.zeitfenster(gesamt)
        val versender = absenderName(absender).let {
            if (absender.lowercase(Locale.GERMAN).contains("amazon")) "Amazon" else it
        }

        var neu = 0
        val gefunden = Anbieter.finden(gesamt)
        for ((anbieter, nummer) in gefunden) {
            if (db.verbuchen(k.name, anbieter, nummer, zeit, absender, betreff, status, erwartet, versender, fenster)) neu++
        }
        if (gefunden.isNotEmpty()) {
            Protokoll.schreib("${k.name}: ${gefunden.joinToString { it.first + " " + it.second }}, ${Status.text(status ?: 0)}, \"${betreff.take(60)}\"")
        } else if (absender.lowercase(Locale.GERMAN).contains("amazon")) {
            // Amazon-Pakete ohne Sendungsnummer über den Artikelnamen verfolgen
            val artikel = Anbieter.amazonArtikel(betreff)
            if (artikel != null) {
                if (db.verbuchen(k.name, "Amazon", artikel, zeit, absender, betreff, status, erwartet, "Amazon", fenster)) neu++
                Protokoll.schreib("${k.name}: Amazon \"$artikel\", ${Status.text(status ?: 0)}")
            }
        }
        return neu
    }

    private fun textVon(teil: Part): String {
        return when {
            teil.isMimeType("text/plain") -> teil.content.toString()
            teil.isMimeType("text/html") ->
                (teil.content as? String ?: "")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace("&nbsp;", " ")
            teil.isMimeType("multipart/*") -> {
                val mp = teil.content as Multipart
                (0 until mp.count).joinToString("\n") { textVon(mp.getBodyPart(it)) }
            }
            else -> ""
        }
    }
}

/** Speicher für Sendungen und ihren Verlauf (lokal auf dem Handy). */
class SendungDb(val ctx: Context) : SQLiteOpenHelper(ctx, "sendungen.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sendungen (" +
                "konto TEXT, anbieter TEXT, nummer TEXT, datum TEXT, " +
                "absender TEXT, betreff TEXT, " +
                "statuscode INTEGER DEFAULT 0, statuszeit TEXT DEFAULT '', " +
                "versender TEXT DEFAULT '', erwartet TEXT DEFAULT '', " +
                "zeitfenster TEXT DEFAULT '', livetext TEXT DEFAULT '', livezeit TEXT DEFAULT '', " +
                "PRIMARY KEY (konto, anbieter, nummer))"
        )
        ereignisTabelle(db)
    }

    private fun ereignisTabelle(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS ereignisse (" +
                "anbieter TEXT, nummer TEXT, zeit TEXT, text TEXT, " +
                "PRIMARY KEY (anbieter, nummer, zeit, text))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, altVersion: Int, neuVersion: Int) {
        if (altVersion < 2) {
            db.execSQL("ALTER TABLE sendungen ADD COLUMN statuscode INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE sendungen ADD COLUMN statuszeit TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE sendungen ADD COLUMN versender TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE sendungen ADD COLUMN erwartet TEXT DEFAULT ''")
            ereignisTabelle(db)
        }
        if (altVersion < 3) {
            db.execSQL("ALTER TABLE sendungen ADD COLUMN zeitfenster TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE sendungen ADD COLUMN livetext TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE sendungen ADD COLUMN livezeit TEXT DEFAULT ''")
        }
    }

    /**
     * Trägt eine Mail zu einer Sendung ein. Neuere Mails bestimmen den Status.
     * Gibt true zurück, wenn die Sendung neu ist.
     */
    @Synchronized
    fun verbuchen(
        konto: String, anbieter: String, nummer: String, zeit: String,
        absender: String, betreff: String, status: Int?, erwartet: String?, versender: String,
        fenster: String? = null
    ): Boolean {
        val probe = Sendung(konto, anbieter, nummer, "", "", "")
        if (Einstellungen.istAusgeblendet(ctx, probe)) return false
        val db = writableDatabase

        db.insertWithOnConflict("ereignisse", null, ContentValues().apply {
            put("anbieter", anbieter)
            put("nummer", nummer)
            put("zeit", zeit)
            put("text", betreff)
        }, SQLiteDatabase.CONFLICT_IGNORE)

        val vorhanden = db.query(
            "sendungen", arrayOf("statuszeit", "versender", "erwartet"),
            "konto = ? AND anbieter = ? AND nummer = ?", arrayOf(konto, anbieter, nummer),
            null, null, null
        ).use { c -> if (c.moveToFirst()) Triple(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "") else null }

        if (vorhanden == null) {
            db.insert("sendungen", null, ContentValues().apply {
                put("konto", konto)
                put("anbieter", anbieter)
                put("nummer", nummer)
                put("datum", zeit.take(10))
                put("absender", absender)
                put("betreff", betreff)
                put("statuscode", status ?: Status.ERFASST)
                put("statuszeit", if (status != null) zeit else "")
                put("versender", versender)
                put("erwartet", erwartet ?: "")
                put("zeitfenster", fenster ?: "")
            })
            return true
        }

        val (statusZeit, alterVersender, altErwartet) = vorhanden
        val werte = ContentValues()
        if (status != null && zeit >= statusZeit) {
            werte.put("statuscode", status)
            werte.put("statuszeit", zeit)
            if (status == Status.ZUGESTELLT || status == Status.STORNIERT) werte.put("erwartet", "")
        }
        if (erwartet != null && erwartet.isNotEmpty() && (status == null || zeit >= statusZeit || altErwartet.isEmpty())) {
            werte.put("erwartet", erwartet)
        }
        if (fenster != null && (status == null || zeit >= statusZeit)) {
            werte.put("zeitfenster", fenster)
        }
        if ((alterVersender.isBlank() || istPaketdienst(alterVersender)) && versender.isNotBlank() && !istPaketdienst(versender)) {
            werte.put("versender", versender)
        }
        if (werte.size() > 0) {
            db.update("sendungen", werte, "konto = ? AND anbieter = ? AND nummer = ?", arrayOf(konto, anbieter, nummer))
        }
        return false
    }

    /** Entfernt eine Sendung und merkt sie sich, damit sie nicht wiederkommt. */
    fun entfernen(s: Sendung) {
        writableDatabase.delete("sendungen", "anbieter = ? AND nummer = ?", arrayOf(s.anbieter, s.nummer))
        Einstellungen.ausblenden(ctx, s)
    }

    fun alle(): List<Sendung> {
        val liste = mutableListOf<Sendung>()
        readableDatabase.query(
            "sendungen",
            arrayOf("konto", "anbieter", "nummer", "datum", "absender", "betreff", "statuscode", "statuszeit", "versender", "erwartet", "zeitfenster", "livetext", "livezeit"),
            null, null, null, null, "statuszeit DESC"
        ).use { c ->
            while (c.moveToNext()) {
                liste.add(
                    Sendung(
                        konto = c.getString(0) ?: "",
                        anbieter = c.getString(1) ?: "",
                        nummer = c.getString(2) ?: "",
                        datum = c.getString(3) ?: "",
                        absender = c.getString(4) ?: "",
                        betreff = c.getString(5) ?: "",
                        status = c.getInt(6),
                        statusZeit = c.getString(7) ?: "",
                        versender = c.getString(8) ?: "",
                        erwartet = c.getString(9) ?: "",
                        zeitfenster = c.getString(10) ?: "",
                        liveText = c.getString(11) ?: "",
                        liveZeit = c.getString(12) ?: ""
                    )
                )
            }
        }
        return liste
    }

    /** Übernimmt den Stand, den der Paketdienst meldet (hat Vorrang vor den Mails). */
    @Synchronized
    fun liveSetzen(s: Sendung, l: LiveStatus) {
        val jetzt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.GERMANY).format(Date())
        val werte = ContentValues().apply {
            put("livetext", l.text)
            put("livezeit", jetzt)
            if (l.status != null) {
                put("statuscode", l.status)
                put("statuszeit", jetzt)
            }
            if (l.status == Status.ZUGESTELLT) {
                put("erwartet", "")
                put("zeitfenster", "")
            } else {
                if (!l.erwartet.isNullOrBlank()) put("erwartet", l.erwartet)
                if (!l.fenster.isNullOrBlank()) put("zeitfenster", l.fenster)
            }
        }
        val db = writableDatabase
        db.update("sendungen", werte, "anbieter = ? AND nummer = ?", arrayOf(s.anbieter, s.nummer))
        for (e in l.ereignisse) {
            db.insertWithOnConflict("ereignisse", null, ContentValues().apply {
                put("anbieter", s.anbieter)
                put("nummer", s.nummer)
                put("zeit", e.zeit)
                put("text", e.text)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    fun verlauf(s: Sendung): List<Ereignis> {
        val liste = mutableListOf<Ereignis>()
        readableDatabase.query(
            "ereignisse", arrayOf("zeit", "text"),
            "anbieter = ? AND nummer = ?", arrayOf(s.anbieter, s.nummer),
            null, null, "zeit DESC"
        ).use { c ->
            while (c.moveToNext()) liste.add(Ereignis(c.getString(0) ?: "", c.getString(1) ?: ""))
        }
        return liste
    }

    /** Unterwegs (nach erwartetem Tag sortiert) und kürzlich zugestellt. */
    fun aufgeteilt(): Pair<List<Sendung>, List<Sendung>> {
        val alle = alle().filter { it.status != Status.STORNIERT }
        val grenze = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(
            Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -14) }.time
        )
        // Ohne Neuigkeit seit 10 Tagen gilt eine Sendung als erledigt
        val veraltet = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(
            Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -10) }.time
        )
        // Die Sendung mit der neuesten Mail steht oben
        val unterwegs = alle.filter { it.status != Status.ZUGESTELLT }
            .filter { maxOf(it.statusZeit, it.datum).take(10) >= veraltet }
            .sortedByDescending { maxOf(it.statusZeit, it.datum) }
        val zugestellt = alle.filter { it.status == Status.ZUGESTELLT && it.statusZeit.take(10) >= grenze }
        return Pair(unterwegs, zugestellt)
    }
}

/** Einstellungen werden in der App gespeichert (nur für diese App lesbar). */
object Einstellungen {
    private const val DATEI = "einstellungen"

    val NAMEN = listOf("MMC-Werbung", "Gmail privat", "Gmail 1979")
    // Adressen werden in den Einstellungen eingetragen, nicht im Code
    val USER = listOf("", "", "")
    val SERVER = listOf("", "imap.gmail.com", "imap.gmail.com")

    fun prefs(c: Context) = c.getSharedPreferences(DATEI, Context.MODE_PRIVATE)

    fun konten(c: Context): List<Konto> {
        val p = prefs(c)
        return NAMEN.indices.map { i ->
            Konto(
                name = NAMEN[i],
                server = p.getString("server$i", SERVER[i]) ?: "",
                port = 993,
                user = p.getString("user$i", USER[i]) ?: "",
                passwort = p.getString("passwort$i", "") ?: ""
            )
        }
    }

    fun tage(c: Context): Int = prefs(c).getInt("tage", 60)

    /** Vergisst den Lesestand aller Postfächer, beim nächsten Abruf wird neu eingelesen. */
    fun standZuruecksetzen(c: Context) {
        val p = prefs(c)
        val editor = p.edit()
        p.all.keys.filter { it.startsWith("stand_") }.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun schluessel(s: Sendung) = "${s.anbieter}|${s.nummer}"

    fun istAusgeblendet(c: Context, s: Sendung): Boolean =
        prefs(c).getStringSet("ausgeblendet", emptySet())?.contains(schluessel(s)) == true

    fun ausblenden(c: Context, s: Sendung) {
        val neu = (prefs(c).getStringSet("ausgeblendet", emptySet()) ?: emptySet()).toMutableSet()
        neu.add(schluessel(s))
        prefs(c).edit().putStringSet("ausgeblendet", neu).apply()
    }
}
