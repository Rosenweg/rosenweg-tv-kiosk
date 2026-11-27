// SPDX-License-Identifier: GPL-3.0-or-later
package ch.rosenweg4303.kiosk

import android.app.AppOpsManager
import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Startet die Verbindung, sobald der Prozess laeuft (als Startseite: beim Einschalten). */
class KioskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Verbindung.starten(this)
    }
}

/**
 * Verbindung zu Rosenweg (/stationen, API /api/stations, Website routes/stations.js).
 *
 *  - Kopplung: Code und QR-Code auf dem Fernseher, Technik bestaetigt auf /stationen. Das Token
 *    erzeugt der Fernseher selbst; die API bekommt nur seinen sha256.
 *  - Lebenszeichen jede Minute: Version, Diashow-App, ob sie vorn laeuft, IP/MAC, Laufzeit.
 *    Die Antwort ist die Konfiguration: Sperre, Diashow-App, PIN-Hash, Diashow-Neustart.
 *  - Am Fernseher geaenderte PIN bzw. Diashow-App wird gemeldet; die neueste Aenderung gewinnt.
 *  - Ohne Netz laeuft alles weiter wie bisher (letzte Konfiguration, lokaler PIN-Hash);
 *    Ausstehendes wird beim naechsten Lebenszeichen nachgeholt.
 */
object Verbindung {
    private const val TAG = "RosenwegKiosk"
    private const val STANDARD_SEKUNDEN = 60L

    private lateinit var app: Context
    private lateinit var speicher: KioskSpeicher
    private val arbeit = Executors.newSingleThreadScheduledExecutor()
    private val haupt = Handler(Looper.getMainLooper())
    private var naechstes: ScheduledFuture<*>? = null
    @Volatile private var takt = STANDARD_SEKUNDEN

    /** Von der Activity gesetzt: was gerade zu sehen ist (fuer das Lebenszeichen). */
    @Volatile var ansicht: String = "leer"
    @Volatile var kioskVorn: Boolean = false
    @Volatile var diashowGestartet: Boolean = false

    /** Die Activity hoert hier mit (Sperre, Kopplung, PIN vom Server). Immer auf dem Hauptfaden. */
    var beobachter: (() -> Unit)? = null

    @Volatile var letzterFehler: String? = null
        private set

    fun starten(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        speicher = KioskSpeicher(app)
        planen(5)
    }

    private fun planen(sekunden: Long) {
        naechstes?.cancel(false)
        naechstes = arbeit.schedule({ runde() }, sekunden, TimeUnit.SECONDS)
    }

    /** Gleich noch eine Runde (auch wenn gerade eine laeuft: dann direkt danach). */
    @Volatile private var nochmal = false

    /** Sofort melden (PIN geaendert, Diashow-App gewaehlt, Neustart quittieren …). */
    fun jetzt() {
        if (!::app.isInitialized) return
        nochmal = true
        planen(0)
    }

    private fun melden() = haupt.post { beobachter?.invoke() }

    private fun runde() {
        nochmal = false
        try {
            if (kopplungLaeuft) kopplungAbfragen()
            if (speicher.gekoppelt) lebenszeichen()
        } catch (e: Exception) {
            Log.w(TAG, "Verbindung: ${e.javaClass.simpleName}: ${e.message}")
            if (kopplungLaeuft) melden()
        } finally {
            planen(if (nochmal) 1 else if (kopplungLaeuft) 5 else takt)
        }
    }

    // ---- HTTP -----------------------------------------------------------------------------------

    private data class Antwort(val code: Int, val json: JSONObject?)

    private fun anfrage(methode: String, pfad: String, koerper: JSONObject? = null, token: String? = null): Antwort {
        val v = URL(speicher.apiBasis + pfad).openConnection() as HttpURLConnection
        try {
            v.requestMethod = methode
            v.connectTimeout = 10_000
            v.readTimeout = 15_000
            v.useCaches = false
            v.setRequestProperty("Accept", "application/json")
            v.setRequestProperty("User-Agent", "RosenwegKiosk/${version().first}")
            token?.let { v.setRequestProperty("Authorization", "Bearer $it") }
            if (koerper != null) {
                v.doOutput = true
                v.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                v.outputStream.use { it.write(koerper.toString().toByteArray()) }
            }
            val code = v.responseCode
            val text = (if (code < 400) v.inputStream else v.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) { null }
            letzterFehler = if (code < 400 || code == 409) null else (json?.optString("error")?.takeIf { it.isNotBlank() } ?: "HTTP $code")
            return Antwort(code, json)
        } catch (e: Exception) {
            letzterFehler = "keine Verbindung (${e.javaClass.simpleName})"
            throw e
        } finally {
            v.disconnect()
        }
    }

    // ---- Kopplung ---------------------------------------------------------------------------------

    data class Kopplung(val code: String, val link: String, val qr: List<String>, val bisMs: Long)

    @Volatile var kopplung: Kopplung? = null
        private set
    @Volatile var kopplungLaeuft = false
        private set

    /** Kiosk-Einstellungen → "Mit Rosenweg verbinden" (oder erster Start ohne PIN). */
    fun kopplungStarten() {
        if (speicher.gekoppelt) return
        kopplungLaeuft = true
        kopplung = null
        jetzt()
    }

    fun kopplungBeenden() {
        kopplungLaeuft = false
        kopplung = null
    }

    private fun kopplungAbfragen() {
        val token = speicher.kopplungsToken()
        val k = kopplung
        if (k == null || System.currentTimeMillis() > k.bisMs) {
            val koerper = JSONObject()
                .put("token_hash", sha256(token))
                .put("hat_pin", speicher.pinGesetzt)
                .put("hardware", inventar())
            val a = anfrage("POST", "/api/stations/kiosk/kopplung", koerper)
            if (a.code == 409 && a.json?.optString("status") == "gekoppelt") {
                // schon bestaetigt, Antwort ging verloren: unten abholen
            } else if (a.code == 200 && a.json != null) {
                val qr = a.json.optJSONObject("qr")?.optJSONArray("zeilen")
                kopplung = Kopplung(
                    code = a.json.optString("code"),
                    link = a.json.optString("link"),
                    qr = (0 until (qr?.length() ?: 0)).map { qr!!.getString(it) },
                    bisMs = System.currentTimeMillis() + a.json.optLong("gueltig_sekunden", 900) * 1000,
                )
                melden()
                return
            } else {
                melden()
                return
            }
        }
        val a = anfrage("GET", "/api/stations/kiosk/kopplung", token = token)
        when {
            a.code == 200 && a.json?.optString("status") == "gekoppelt" -> {
                val j = a.json
                speicher.gekoppeltMit(j.getString("station_id"), token, j.optString("name").ifBlank { null })
                kopplungLaeuft = false
                kopplung = null
                Log.i(TAG, "Gekoppelt als ${j.getString("station_id")}")
                konfigurationAnwenden(j)
                melden()
            }
            a.code == 404 -> { kopplung = null }
        }
    }

    // ---- Lebenszeichen -------------------------------------------------------------------------------

    private fun lebenszeichen() {
        val token = speicher.stationToken ?: return
        val id = speicher.stationId ?: return

        // Erst die am Geraet geaenderte PIN, damit die Antwort unten schon den neuen Stand traegt.
        speicher.pinAusstehend?.let { aus ->
            val a = anfrage("PUT", "/api/stations/$id/kiosk/pin",
                JSONObject().put("pin", aus.pin).put("geaendert_am", aus.am).put("grund", aus.grund), token)
            if (a.code == 401) { entfernt(); return }
            // 200: gemeldet; 409: auf /stationen ist eine neuere gesetzt, die kommt gleich.
            // 400 (unbrauchbar): nicht ewig wiederholen. 403 (gesperrt): spaeter nochmal.
            if (a.code == 200 || a.code == 409 || a.code == 400) speicher.pinGemeldet()
            if (a.code == 200) speicher.pinAmServerBekannt = true
        }

        val state = zustand()
        speicher.diashowAusstehend?.let { (paket, am) ->
            state.put("diashow_aenderung", JSONObject().put("paket", paket).put("am", am))
        }
        speicher.neustartErledigt?.let { state.put("neustart_erledigt", it) }
        val a = anfrage("POST", "/api/stations/$id/seen", JSONObject().put("state", state), token)
        if (a.code == 401) { entfernt(); return }
        if (a.code != 200 || a.json == null) return
        speicher.zuletztGemeldet = System.currentTimeMillis()
        speicher.diashowAusstehend?.let { (paket, _) -> speicher.diashowGemeldet(paket) }
        konfigurationAnwenden(a.json)
    }

    /** Token wertlos (auf /stationen entfernt): Kopplung vergessen, als Kiosk weiterlaufen. */
    private fun entfernt() {
        Log.i(TAG, "Auf /stationen entfernt — Kopplung vergessen")
        speicher.kopplungVergessen()
        Verbindungsdienst.beenden(app)
        melden()
    }

    private fun konfigurationAnwenden(j: JSONObject) {
        val warGesperrt = speicher.gesperrt
        val gesperrt = j.optBoolean("gesperrt", false)
        speicher.sperreSetzen(gesperrt, j.optString("sperr_grund").takeIf { it.isNotBlank() && it != "null" })
        j.optString("name").takeIf { it.isNotBlank() && it != "null" }?.let { speicher.stationName = it }
        takt = j.optLong("abfrage_sekunden", STANDARD_SEKUNDEN).coerceIn(15, 900)
        speicher.pinAmServerBekannt = j.optBoolean("pin_bekannt", false)

        if (gesperrt) {
            // Auch wenn schon gesperrt: ist der Kiosk nicht vorn (Neustart des Prozesses, Update),
            // holt jedes Lebenszeichen den Sperrhinweis wieder nach vorn.
            if (!warGesperrt || !kioskVorn) aufDenSchirm(KioskActivity.EXTRA_GESPERRT)
            melden()
            return
        }
        var geaendert = warGesperrt

        j.optJSONObject("diashow")?.let { d ->
            val paket = d.optString("paket")
            val stand = d.optString("stand").takeIf { it.isNotBlank() && it != "null" }
            if (paket.isNotBlank() && paket != speicher.zielPaket && KioskSpeicher.neuer(stand, speicher.diashowStand)
                && speicher.diashowAusstehend == null) {
                speicher.diashowVomServer(paket, stand)
                geaendert = true
            }
        }
        j.optJSONObject("pin")?.let { pin ->
            val stand = pin.optString("stand").takeIf { it.isNotBlank() && it != "null" }
            if (stand != null && KioskSpeicher.neuer(stand, speicher.pinStand) && speicher.pinAusstehend == null) {
                try {
                    speicher.pinHashUebernehmen(pin.getString("hash"), pin.getString("salz"), pin.optInt("runden", 60_000), stand)
                    Log.i(TAG, "PIN von /stationen uebernommen")
                    geaendert = true
                } catch (e: Exception) {
                    Log.w(TAG, "PIN von /stationen unbrauchbar: ${e.javaClass.simpleName}")
                }
            }
        }
        val neustart = j.optString("neustart").takeIf { it.isNotBlank() && it != "null" }
        if (neustart != null && neustart != speicher.neustartErledigt) {
            speicher.neustartErledigt = neustart
            aufDenSchirm(KioskActivity.EXTRA_DIASHOW_NEU)
            jetzt()     // gleich quittieren
        }
        if (warGesperrt) aufDenSchirm(KioskActivity.EXTRA_DIASHOW)
        if (geaendert) melden()
    }

    /** Kiosk nach vorn holen (er ist die Startseite und darf das aus dem Hintergrund). */
    private fun aufDenSchirm(extra: String) {
        haupt.post {
            try {
                app.startActivity(Intent(app, KioskActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(extra, true))
            } catch (e: Exception) {
                Log.w(TAG, "Kiosk nach vorn: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Von /stationen trennen: abmelden (Token entwerten) und vergessen. */
    fun trennen(fertig: (Boolean) -> Unit) {
        arbeit.execute {
            val ok = try {
                val id = speicher.stationId
                val t = speicher.stationToken
                if (id != null && t != null) anfrage("DELETE", "/api/stations/$id", token = t).code.let { it == 200 || it == 401 } else true
            } catch (e: Exception) { false }
            if (ok) { speicher.kopplungVergessen(); Verbindungsdienst.beenden(app) }
            haupt.post { fertig(ok); beobachter?.invoke() }
        }
    }

    // ---- Was der Fernseher meldet ------------------------------------------------------------------

    private fun version(): Pair<String, Long> = try {
        val pi = app.packageManager.getPackageInfo(app.packageName, 0)
        (pi.versionName ?: "?") to pi.longVersionCode
    } catch (e: Exception) { "?" to 0L }

    private fun inventar(): JSONObject {
        val (v, _) = version()
        return JSONObject()
            .put("hersteller", Build.MANUFACTURER)
            .put("modell", Build.MODEL)
            .put("android", Build.VERSION.RELEASE)
            .put("app_version", v)
            .put("macs", JSONObject(netz().macs as Map<*, *>))
    }

    private data class Netz(val ip: String?, val art: String?, val macs: Map<String, String>)

    /** IPv4 und MAC der aktiven Schnittstelle. Android gibt Apps die MAC nicht immer heraus. */
    private fun netz(): Netz {
        var ip: String? = null
        var art: String? = null
        val macs = mutableMapOf<String, String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                val v4 = ni.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull() ?: continue
                if (ip == null || ni.name.startsWith("eth")) {
                    ip = v4.hostAddress
                    art = if (ni.name.startsWith("eth")) "kabel" else if (ni.name.startsWith("wlan")) "wlan" else null
                }
                ni.hardwareAddress?.takeIf { it.size == 6 }?.let { b ->
                    val m = b.joinToString(":") { "%02x".format(it) }
                    if (m != "02:00:00:00:00:00" && m != "00:00:00:00:00:00") macs[ni.name] = m
                }
            }
        } catch (e: Exception) { /* ohne Netz: leer */ }
        return Netz(ip, art, macs)
    }

    private fun label(paket: String): String? = try {
        app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(paket, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) { null }

    /** Startbare Apps — daraus waehlt /stationen die Diashow-App. */
    private fun apps(): JSONArray {
        val pm = app.packageManager
        val pakete = (pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0) +
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0))
            .map { it.activityInfo.packageName }.filter { it != app.packageName }.distinct()
        val a = JSONArray()
        for (p in pakete.sortedBy { (label(it) ?: it).lowercase() }.take(100)) a.put(JSONObject().put("paket", p).put("name", label(p) ?: p))
        return a
    }

    /**
     * Welche App ist vorn? Genau nur mit "Nutzungsdaten" (per adb:
     * appops set ch.rosenweg4303.kiosk GET_USAGE_STATS allow), sonst geschaetzt aus dem, was
     * der Kiosk selbst gestartet hat.
     */
    private fun vorn(): Pair<String?, String> {
        val ops = app.getSystemService(AppOpsManager::class.java)
        val erlaubt = try {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName) == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) { false }
        if (erlaubt) {
            try {
                val usm = app.getSystemService(UsageStatsManager::class.java)
                val jetzt = System.currentTimeMillis()
                val ev = usm.queryEvents(jetzt - 24 * 3600_000L, jetzt)
                val e = UsageEvents.Event()
                var zuletzt: String? = null
                while (ev.hasNextEvent()) {
                    ev.getNextEvent(e)
                    if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) zuletzt = e.packageName
                }
                if (zuletzt != null) return zuletzt to "nutzungsdaten"
            } catch (e: Exception) { /* unten schaetzen */ }
        }
        return (if (kioskVorn) app.packageName else if (diashowGestartet) speicher.zielPaket else null) to "schaetzung"
    }

    private fun zustand(): JSONObject {
        val (v, code) = version()
        val n = netz()
        val ziel = speicher.zielPaket
        val (vornPaket, quelle) = vorn()
        val diashowVorn = vornPaket == ziel
        return JSONObject()
            .put("app_version", v)
            .put("version_code", code)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("hersteller", Build.MANUFACTURER)
            .put("modell", Build.MODEL)
            .put("diashow_paket", ziel)
            .put("diashow_name", label(ziel) ?: JSONObject.NULL)
            .put("diashow_installiert", label(ziel) != null)
            .put("diashow_vorn", diashowVorn)
            .put("vorn_paket", vornPaket ?: JSONObject.NULL)
            .put("vorn_quelle", quelle)
            .put("ansicht", if (diashowVorn) "diashow" else if (!kioskVorn && vornPaket != null && vornPaket != app.packageName) "andere-app" else ansicht)
            .put("ip", n.ip ?: JSONObject.NULL)
            .put("netz", n.art ?: JSONObject.NULL)
            .put("macs", JSONObject(n.macs as Map<*, *>))
            .put("uptime_seconds", SystemClock.elapsedRealtime() / 1000)
            .put("pin_gesetzt", speicher.pinGesetzt)
            .put("pin_stand", speicher.pinStand ?: JSONObject.NULL)
            .put("gesperrt_lokal", speicher.gesperrt)
            .put("apps", apps())
    }

    private fun sha256(s: String) = KioskSpeicher.hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
}
