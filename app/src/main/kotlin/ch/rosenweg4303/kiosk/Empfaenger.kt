// SPDX-License-Identifier: GPL-3.0-or-later
package ch.rosenweg4303.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Einrichtung per adb (nur die adb-Shell bzw. das System haben android.permission.DUMP):
 *
 *   adb shell am broadcast -a ch.rosenweg4303.kiosk.EINRICHTEN -n ch.rosenweg4303.kiosk/.AdbEinrichtung \
 *       -f 0x20 --es pin 123456 [--es paket sk.mimac.slideshow]
 *
 * Fuer Tests gegen eine lokale Attrappe der API (adb reverse tcp:18443 tcp:18443):
 *   --es api http://127.0.0.1:18443   (Klartext nur fuer 127.0.0.1/localhost, sonst https://)
 *   --es api standard                 zurueck auf www.rosenweg4303.ch
 *   --ez kopplung_vergessen true      Kopplung mit /stationen lokal vergessen
 *
 * Antwort steht in der Ausgabe von am broadcast (data="…"). Die PIN wird nicht protokolliert.
 */
class AdbEinrichtung : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != AKTION) return
        val s = KioskSpeicher(c)
        val meldungen = mutableListOf<String>()
        var ok = true
        i.getStringExtra("pin")?.let { pin ->
            if (KioskSpeicher.pinGueltig(pin)) {
                val stand = java.time.Instant.now().toString()
                s.pinSetzen(pin, stand); meldungen += "PIN gesetzt"
                // Gekoppelt: an /stationen melden wie eine am Fernseher geaenderte PIN
                if (s.gekoppelt) { s.pinZumMelden(pin, stand, "geaendert"); Verbindung.jetzt() }
            } else {
                ok = false; meldungen += "PIN ungueltig (4 bis 8 Ziffern)"
            }
        }
        i.getStringExtra("paket")?.let { paket ->
            val da = try { c.packageManager.getPackageInfo(paket, 0); true } catch (e: PackageManager.NameNotFoundException) { false }
            s.diashowAmGeraet(paket)
            if (s.gekoppelt) Verbindung.jetzt()
            meldungen += "Diashow-App: $paket" + if (da) "" else " (noch nicht installiert)"
        }
        i.getStringExtra("api")?.let { api ->
            val a = api.trim().trimEnd('/')
            when {
                a == "standard" -> { s.apiBasis = KioskSpeicher.STANDARD_API; meldungen += "API: ${s.apiBasis}" }
                a.matches(Regex("^https://[A-Za-z0-9.-]+(:[0-9]+)?$")) ||
                    a.matches(Regex("^http://(127\\.0\\.0\\.1|localhost)(:[0-9]+)?$")) -> { s.apiBasis = a; meldungen += "API: $a" }
                else -> { ok = false; meldungen += "API ungueltig (https://… oder http://127.0.0.1:PORT)" }
            }
        }
        if (i.getBooleanExtra("kopplung_vergessen", false)) { s.kopplungVergessen(); Verbindungsdienst.beenden(c); meldungen += "Kopplung vergessen" }
        if (meldungen.isEmpty()) meldungen += "nichts zu tun (--es pin … / --es paket … / --es api …)"
        meldungen += "PIN eingerichtet: " + (if (s.pinGesetzt) "ja" else "nein")
        meldungen += "Rosenweg: " + (if (s.gekoppelt) "verbunden als ${s.stationId}" else "nicht verbunden")
        Log.i(TAG, "Einrichtung per adb: " + meldungen.filterNot { it.startsWith("PIN gesetzt") }.joinToString("; "))
        resultCode = if (ok) 0 else 1
        resultData = meldungen.joinToString("; ")
    }

    companion object {
        const val AKTION = "ch.rosenweg4303.kiosk.EINRICHTEN"
        const val TAG = "RosenwegKiosk"
    }
}

/**
 * Nach dem Einschalten die Diashow starten. Ist der Kiosk die Startseite (HOME), startet Android
 * ihn ohnehin; dann nichts tun. Sonst braucht Android fuer den Start aus dem Hintergrund
 * "ueber anderen Apps einblenden" (appops SYSTEM_ALERT_WINDOW), siehe README.
 */
class StartNachBoot : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        Verbindungsdienst.starten(c)
        val home = c.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        if (home?.activityInfo?.packageName == c.packageName) return
        try {
            c.startActivity(Intent(c, KioskActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(KioskActivity.EXTRA_DIASHOW, true))
        } catch (e: Exception) {
            Log.w(AdbEinrichtung.TAG, "Start nach dem Einschalten: ${e.javaClass.simpleName}")
        }
    }
}

/** Nach einem Update: nur den Prozess starten (KioskApp startet die Verbindung zu Rosenweg). */
class NachUpdate : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_MY_PACKAGE_REPLACED) { Verbindung.starten(c); Verbindungsdienst.starten(c) }
    }
}
