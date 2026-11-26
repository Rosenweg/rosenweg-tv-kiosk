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
                s.pinSetzen(pin); meldungen += "PIN gesetzt"
            } else {
                ok = false; meldungen += "PIN ungueltig (4 bis 8 Ziffern)"
            }
        }
        i.getStringExtra("paket")?.let { paket ->
            val da = try { c.packageManager.getPackageInfo(paket, 0); true } catch (e: PackageManager.NameNotFoundException) { false }
            s.zielPaket = paket
            meldungen += "Diashow-App: $paket" + if (da) "" else " (noch nicht installiert)"
        }
        if (meldungen.isEmpty()) meldungen += "nichts zu tun (--es pin … / --es paket …)"
        meldungen += "PIN eingerichtet: " + (if (s.pinGesetzt) "ja" else "nein")
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
