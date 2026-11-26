// SPDX-License-Identifier: GPL-3.0-or-later
package ch.rosenweg4303.kiosk

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.min

/**
 * Einstellungen des Kiosks: Diashow-App und PIN. Die PIN liegt nur als Hash
 * (PBKDF2-HMAC-SHA256 mit zufaelligem Salz) im privaten Speicher der App.
 * Fehlversuche zaehlen auch ueber einen Neustart der App hinweg.
 */
class KioskSpeicher(c: Context) {
    private val p = c.applicationContext.getSharedPreferences("kiosk", Context.MODE_PRIVATE)

    var zielPaket: String
        get() = p.getString(PAKET, null)?.takeIf { it.isNotBlank() } ?: STANDARD_PAKET
        set(v) { p.edit().putString(PAKET, v.trim()).apply() }

    val pinGesetzt: Boolean get() = p.contains(PIN_HASH)

    fun pinSetzen(pin: String) {
        require(pinGueltig(pin)) { "PIN: 4 bis 8 Ziffern" }
        val salz = ByteArray(16).also { SecureRandom().nextBytes(it) }
        p.edit()
            .putString(PIN_SALZ, hex(salz))
            .putInt(PIN_RUNDEN, RUNDEN)
            .putString(PIN_HASH, hex(hash(pin, salz, RUNDEN)))
            .putInt(FEHL, 0)
            .putLong(GESPERRT_BIS, 0)
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

    companion object {
        const val STANDARD_PAKET = "sk.mimac.slideshow"
        private const val PAKET = "ziel_paket"
        private const val PIN_HASH = "pin_hash"
        private const val PIN_SALZ = "pin_salz"
        private const val PIN_RUNDEN = "pin_runden"
        private const val FEHL = "fehlversuche"
        private const val GESPERRT_BIS = "gesperrt_bis"
        private const val RUNDEN = 60_000
        const val FREIE_VERSUCHE = 3
        private const val MAX_SPERRE_MS = 15 * 60_000L

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

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
