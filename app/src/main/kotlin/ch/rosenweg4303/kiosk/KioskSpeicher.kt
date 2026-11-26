// SPDX-License-Identifier: GPL-3.0-or-later
package ch.rosenweg4303.kiosk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import kotlin.math.min

/**
 * Einstellungen des Kiosks: Diashow-App, PIN und die Verbindung zu Rosenweg (/stationen).
 *
 * Die PIN liegt nur als Hash (PBKDF2-HMAC-SHA256 mit zufaelligem Salz) im privaten Speicher der
 * App. Einzige Ausnahme: eine am Fernseher geaenderte PIN, die noch nicht an /stationen gemeldet
 * ist (kein Netz). Sie wartet verschluesselt mit einem Schluessel aus dem Android-Keystore, der
 * das Geraet nicht verlassen kann, und wird nach dem Melden geloescht.
 *
 * Fehlversuche zaehlen auch ueber einen Neustart der App hinweg.
 */
class KioskSpeicher(c: Context) {
    private val p = c.applicationContext.getSharedPreferences("kiosk", Context.MODE_PRIVATE)

    // ---- Diashow-App ---------------------------------------------------------------------------

    var zielPaket: String
        get() = p.getString(PAKET, null)?.takeIf { it.isNotBlank() } ?: STANDARD_PAKET
        set(v) { p.edit().putString(PAKET, v.trim()).apply() }

    /** Wann die Diashow-App zuletzt gewaehlt wurde (am Geraet oder auf /stationen), ISO. */
    val diashowStand: String? get() = p.getString(PAKET_STAND, null)

    /** Am Fernseher gewaehlt: gilt sofort, wird an /stationen gemeldet. */
    fun diashowAmGeraet(paket: String) {
        val jetzt = Instant.now().toString()
        p.edit().putString(PAKET, paket.trim()).putString(PAKET_STAND, jetzt)
            .putString(AUS_DIASHOW, paket.trim()).putString(AUS_DIASHOW_AM, jetzt).apply()
    }

    /** Von /stationen gewaehlt (neuer als der Stand hier). */
    fun diashowVomServer(paket: String, stand: String?) {
        p.edit().putString(PAKET, paket).putString(PAKET_STAND, stand ?: Instant.now().toString())
            .remove(AUS_DIASHOW).remove(AUS_DIASHOW_AM).apply()
    }

    /** Noch nicht gemeldete Wahl am Geraet: (paket, am). */
    val diashowAusstehend: Pair<String, String>?
        get() {
            val paket = p.getString(AUS_DIASHOW, null) ?: return null
            return paket to (p.getString(AUS_DIASHOW_AM, null) ?: return null)
        }

    fun diashowGemeldet(paket: String) {
        if (p.getString(AUS_DIASHOW, null) == paket) p.edit().remove(AUS_DIASHOW).remove(AUS_DIASHOW_AM).apply()
    }

    // ---- PIN -------------------------------------------------------------------------------------

    val pinGesetzt: Boolean get() = p.contains(PIN_HASH)

    /** Wann die PIN zuletzt gesetzt wurde (ISO), null bei einer PIN von vor Version 1.1. */
    val pinStand: String? get() = p.getString(PIN_STAND, null)

    /**
     * Erster Start seit dem Einschalten? Nur dann (und nach dem Boot-Empfaenger) direkt die
     * Diashow; jeder spaetere Start (Home-Taste, auch nachdem Android den Kiosk beendet hat,
     * z. B. nach einem Update) fuehrt zum PIN-Schirm. Merkt sich den Start beim Aufruf.
     */
    fun ersterStartSeitBoot(c: Context): Boolean {
        val zaehler = try {
            android.provider.Settings.Global.getInt(c.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
        } catch (e: Exception) { -1 }
        // Rueckfall ohne Boot-Zaehler: Einschaltzeit auf die Minute
        val boot = if (zaehler >= 0) "z$zaehler"
            else "t" + (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 60_000
        if (p.getString(BOOT, null) == boot) return false
        p.edit().putString(BOOT, boot).apply()
        return true
    }

    /** PIN am Geraet setzen (adb oder Kiosk-Einstellungen). */
    fun pinSetzen(pin: String, stand: String = Instant.now().toString()) {
        require(pinGueltig(pin)) { "PIN: 4 bis 8 Ziffern" }
        val salz = ByteArray(16).also { SecureRandom().nextBytes(it) }
        p.edit()
            .putString(PIN_SALZ, hex(salz))
            .putInt(PIN_RUNDEN, RUNDEN)
            .putString(PIN_HASH, hex(hash(pin, salz, RUNDEN)))
            .putString(PIN_STAND, stand)
            .putInt(FEHL, 0)
            .putLong(GESPERRT_BIS, 0)
            .commit()
    }

    /** PIN von /stationen: nur ihr Hash kommt an. */
    fun pinHashUebernehmen(hashHex: String, salzHex: String, runden: Int, stand: String) {
        require(hashHex.matches(Regex("^[0-9a-f]{64}$")) && salzHex.matches(Regex("^([0-9a-f]{2}){8,64}$")) && runden in 1_000..10_000_000)
        p.edit()
            .putString(PIN_SALZ, salzHex)
            .putInt(PIN_RUNDEN, runden)
            .putString(PIN_HASH, hashHex)
            .putString(PIN_STAND, stand)
            .putInt(FEHL, 0)
            .putLong(GESPERRT_BIS, 0)
            .remove(AUS_PIN)
            .commit()
    }

    sealed class Ergebnis {
        object Richtig : Ergebnis()
        object KeinePin : Ergebnis()
        /** wartenMs > 0: ab jetzt gesperrt */
        data class Falsch(val versuche: Int, val wartenMs: Long) : Ergebnis()
        data class Gesperrt(val restMs: Long) : Ergebnis()
    }

    /** Restliche Wartezeit nach zu vielen Fehlversuchen (0 = frei). */
    fun sperreRestMs(jetzt: Long = System.currentTimeMillis()): Long {
        val bis = p.getLong(GESPERRT_BIS, 0)
        // Uhr zurueckgestellt: hoechstens die laengste Sperre
        return if (bis <= jetzt) 0 else min(bis - jetzt, MAX_SPERRE_MS)
    }

    fun pruefen(pin: String): Ergebnis {
        val hashHex = p.getString(PIN_HASH, null) ?: return Ergebnis.KeinePin
        val rest = sperreRestMs()
        if (rest > 0) return Ergebnis.Gesperrt(rest)
        val salz = unhex(p.getString(PIN_SALZ, "")!!)
        val runden = p.getInt(PIN_RUNDEN, RUNDEN)
        if (pinGueltig(pin) && MessageDigest.isEqual(hash(pin, salz, runden), unhex(hashHex))) {
            p.edit().putInt(FEHL, 0).putLong(GESPERRT_BIS, 0).apply()
            return Ergebnis.Richtig
        }
        val fehl = p.getInt(FEHL, 0) + 1
        val warten = wartezeit(fehl)
        p.edit().putInt(FEHL, fehl).putLong(GESPERRT_BIS, if (warten > 0) System.currentTimeMillis() + warten else 0).apply()
        return Ergebnis.Falsch(fehl, warten)
    }

    // ---- PIN, die noch an /stationen gemeldet werden muss -----------------------------------------

    /** grund: "geaendert" (Kiosk-Einstellungen) oder "entsperrt" (/stationen kennt sie noch nicht). */
    fun pinZumMelden(pin: String, am: String, grund: String) {
        val wert = try { verschluesseln(pin) } catch (e: Exception) { null } ?: return
        p.edit().putString(AUS_PIN, wert).putString(AUS_PIN_AM, am).putString(AUS_PIN_GRUND, grund).apply()
    }

    data class AusstehendePin(val pin: String, val am: String, val grund: String)

    val pinAusstehend: AusstehendePin?
        get() {
            val wert = p.getString(AUS_PIN, null) ?: return null
            val pin = try { entschluesseln(wert) } catch (e: Exception) { pinGemeldet(); return null }
            return AusstehendePin(pin, p.getString(AUS_PIN_AM, null) ?: Instant.now().toString(), p.getString(AUS_PIN_GRUND, null) ?: "geaendert")
        }

    fun pinGemeldet() { p.edit().remove(AUS_PIN).remove(AUS_PIN_AM).remove(AUS_PIN_GRUND).apply() }

    // ---- Verbindung zu Rosenweg (/stationen) -------------------------------------------------------

    /** Adresse der API; aendern nur per adb (Test gegen eine lokale Attrappe). */
    var apiBasis: String
        get() = p.getString(API, null)?.takeIf { it.isNotBlank() } ?: STANDARD_API
        set(v) { p.edit().putString(API, v.trim().trimEnd('/')).apply() }

    val stationId: String? get() = p.getString(STATION_ID, null)
    val stationToken: String? get() = p.getString(STATION_TOKEN, null)
    val gekoppelt: Boolean get() = stationId != null && stationToken != null
    var stationName: String?
        get() = p.getString(STATION_NAME, null)
        set(v) { p.edit().putString(STATION_NAME, v).apply() }

    fun gekoppeltMit(id: String, token: String, name: String?) {
        p.edit().putString(STATION_ID, id).putString(STATION_TOKEN, token).putString(STATION_NAME, name)
            .remove(KOPPEL_TOKEN).commit()
    }

    /** Entfernt oder abgemeldet: Identitaet vergessen. PIN und Diashow-App bleiben. */
    fun kopplungVergessen() {
        p.edit().remove(STATION_ID).remove(STATION_TOKEN).remove(STATION_NAME).remove(KOPPEL_TOKEN)
            .remove(GESPERRT).remove(SPERR_GRUND).remove(PIN_BEKANNT).remove(ZULETZT_GEMELDET)
            .remove(AUS_DIASHOW).remove(AUS_DIASHOW_AM).remove(NEUSTART_ERLEDIGT).commit()
        pinGemeldet()
    }

    /** Token fuer die laufende Kopplung — erzeugt hier, der Server sieht nur seinen sha256. */
    fun kopplungsToken(): String {
        p.getString(KOPPEL_TOKEN, null)?.let { return it }
        val t = "rw_station_" + hex(ByteArray(32).also { SecureRandom().nextBytes(it) })
        p.edit().putString(KOPPEL_TOKEN, t).commit()
        return t
    }

    fun kopplungsTokenVerwerfen() { p.edit().remove(KOPPEL_TOKEN).apply() }

    /** Gesperrt auf /stationen: bleibt auch ohne Netz und nach dem Neustart gesperrt. */
    val gesperrt: Boolean get() = p.getBoolean(GESPERRT, false)
    val sperrGrund: String? get() = p.getString(SPERR_GRUND, null)
    fun sperreSetzen(gesperrt: Boolean, grund: String?) {
        p.edit().putBoolean(GESPERRT, gesperrt).putString(SPERR_GRUND, if (gesperrt) grund else null).apply()
    }

    /** Kennt /stationen die gueltige PIN? Sonst meldet der Kiosk sie beim naechsten Entsperren. */
    var pinAmServerBekannt: Boolean
        get() = p.getBoolean(PIN_BEKANNT, false)
        set(v) { p.edit().putBoolean(PIN_BEKANNT, v).apply() }

    /** Der zuletzt ausgefuehrte Diashow-Neustart (Zeitstempel vom Server). */
    var neustartErledigt: String?
        get() = p.getString(NEUSTART_ERLEDIGT, null)
        set(v) { p.edit().putString(NEUSTART_ERLEDIGT, v).apply() }

    var zuletztGemeldet: Long
        get() = p.getLong(ZULETZT_GEMELDET, 0)
        set(v) { p.edit().putLong(ZULETZT_GEMELDET, v).apply() }

    companion object {
        const val STANDARD_PAKET = "sk.mimac.slideshow"
        const val STANDARD_API = "https://www.rosenweg4303.ch"
        private const val PAKET = "ziel_paket"
        private const val PAKET_STAND = "ziel_paket_stand"
        private const val PIN_HASH = "pin_hash"
        private const val PIN_SALZ = "pin_salz"
        private const val PIN_RUNDEN = "pin_runden"
        private const val PIN_STAND = "pin_stand"
        private const val FEHL = "fehlversuche"
        private const val GESPERRT_BIS = "gesperrt_bis"
        private const val BOOT = "diashow_boot"
        private const val API = "api_basis"
        private const val STATION_ID = "station_id"
        private const val STATION_TOKEN = "station_token"
        private const val STATION_NAME = "station_name"
        private const val KOPPEL_TOKEN = "kopplung_token"
        private const val GESPERRT = "station_gesperrt"
        private const val SPERR_GRUND = "station_sperr_grund"
        private const val PIN_BEKANNT = "pin_am_server_bekannt"
        private const val NEUSTART_ERLEDIGT = "neustart_erledigt"
        private const val ZULETZT_GEMELDET = "zuletzt_gemeldet"
        private const val AUS_PIN = "ausstehend_pin"
        private const val AUS_PIN_AM = "ausstehend_pin_am"
        private const val AUS_PIN_GRUND = "ausstehend_pin_grund"
        private const val AUS_DIASHOW = "ausstehend_diashow"
        private const val AUS_DIASHOW_AM = "ausstehend_diashow_am"
        private const val RUNDEN = 60_000
        const val FREIE_VERSUCHE = 3
        private const val MAX_SPERRE_MS = 15 * 60_000L
        private const val KEYSTORE_ALIAS = "kiosk-pin-ausstehend"

        fun pinGueltig(pin: String) = pin.length in 4..8 && pin.all { it in '0'..'9' }

        /** Ab dem 3. Fehlversuch: 30 s, dann jeweils doppelt so lang, hoechstens 15 min. */
        fun wartezeit(fehl: Int): Long {
            if (fehl < FREIE_VERSUCHE) return 0
            val n = min(fehl - FREIE_VERSUCHE, 10)
            return min(30_000L shl n, MAX_SPERRE_MS)
        }

        private fun hash(pin: String, salz: ByteArray, runden: Int): ByteArray {
            val spec = PBEKeySpec(pin.toCharArray(), salz, runden, 256)
            return try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        private fun schluessel(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
            val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            g.init(KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            return g.generateKey()
        }

        private fun verschluesseln(text: String): String {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, schluessel())
            val daten = c.doFinal(text.toByteArray())
            return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(daten, Base64.NO_WRAP)
        }

        private fun entschluesseln(wert: String): String {
            val (iv, daten) = wert.split(":").let { it[0] to it[1] }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, schluessel(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            return String(c.doFinal(Base64.decode(daten, Base64.NO_WRAP)))
        }

        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

        /** Neuer als? Beide ISO-8601; ohne alten Stand immer. */
        fun neuer(neu: String?, alt: String?): Boolean {
            val n = try { neu?.let { Instant.parse(it) } } catch (e: Exception) { null } ?: return false
            val a = try { alt?.let { Instant.parse(it) } } catch (e: Exception) { null } ?: return true
            return n.isAfter(a)
        }
    }
}
