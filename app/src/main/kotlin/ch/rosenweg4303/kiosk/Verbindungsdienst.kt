// SPDX-License-Identifier: GPL-3.0-or-later
package ch.rosenweg4303.kiosk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * Haelt die Lebenszeichen am Laufen, solange der Kiosk mit /stationen verbunden ist.
 *
 * Ohne ihn friert Android 14 den Prozess ein, sobald er nicht mehr die Startseite haelt — etwa
 * nach einem Update, bis jemand Home drueckt (die Diashow laeuft ja tagelang vorn). Ein
 * Vordergrunddienst ist davon ausgenommen. Auf Google TV ist seine Benachrichtigung nicht zu sehen.
 */
class Verbindungsdienst : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(KANAL, "Verbindung zu Rosenweg", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, KANAL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Rosenweg Kiosk")
            .setContentText("Verbunden mit /stationen")
            .setOngoing(true)
            .build()
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            Log.w("RosenwegKiosk", "Vordergrunddienst: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        Verbindung.starten(this)
        return START_STICKY
    }

    companion object {
        private const val KANAL = "verbindung"

        /** Nur wenn verbunden. Wo Android den Start nicht erlaubt (Hintergrund), still nichts. */
        fun starten(c: Context) {
            if (!KioskSpeicher(c).gekoppelt) return
            try {
                c.startForegroundService(Intent(c, Verbindungsdienst::class.java))
            } catch (e: Exception) {
                Log.i("RosenwegKiosk", "Vordergrunddienst jetzt nicht erlaubt: ${e.javaClass.simpleName}")
            }
        }

        fun beenden(c: Context) { c.stopService(Intent(c, Verbindungsdienst::class.java)) }
    }
}
