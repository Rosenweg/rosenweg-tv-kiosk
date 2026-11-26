// SPDX-License-Identifier: GPL-3.0-or-later
package ch.rosenweg4303.kiosk

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Startseite des Kiosk-Fernsehers.
 *
 *  - Start (Einschalten, Startseite, App-Symbol): sofort die Diashow-App.
 *  - Zurueck aus der Diashow oder Home-Taste (von ueberall): PIN-Schirm; 30 s ohne jede Eingabe
 *    wieder Diashow. Home verwirft eine Freigabe immer.
 *  - Richtige PIN: Apps, Android-Einstellungen, Kiosk-Einstellungen, "Diashow starten";
 *    nach 10 min ohne Bedienung wieder Diashow.
 *  - Gekoppelt mit /stationen und dort gesperrt: nur der Sperrhinweis, keine Diashow, kein Menue
 *    (bleibt auch ohne Netz, bis /stationen entsperrt).
 *  - Noch keine PIN und nicht gekoppelt: statt des PIN-Schirms der Kopplungscode.
 *
 * Alles in einer Activity mit wechselnden Ansichten, damit die Zeitgrenzen an einer Stelle liegen.
 */
class KioskActivity : Activity() {

    private enum class Ansicht { LEER, PIN, APPS, EINSTELLUNGEN, PIN_AENDERN, PAKET_WAEHLEN, KOPPELN, TRENNEN, GESPERRT }

    private lateinit var speicher: KioskSpeicher
    private val h = Handler(Looper.getMainLooper())

    /** Was zu sehen ist; das Lebenszeichen meldet es an /stationen. */
    private var ansicht = Ansicht.LEER
        set(v) {
            if (field == Ansicht.KOPPELN && v != Ansicht.KOPPELN) Verbindung.kopplungBeenden()
            field = v
            Verbindung.ansicht = when (v) {
                Ansicht.LEER -> "leer"
                Ansicht.PIN, Ansicht.PIN_AENDERN -> "pin"
                Ansicht.APPS -> "apps"
                Ansicht.EINSTELLUNGEN, Ansicht.PAKET_WAEHLEN, Ansicht.TRENNEN -> "einstellungen"
                Ansicht.KOPPELN -> "koppeln"
                Ansicht.GESPERRT -> "gesperrt"
            }
        }
    private var entsperrt = false
    /** Die PIN, mit der gerade entsperrt wurde — nur im Speicher, nur solange entsperrt. */
    private var entsperrPin: String? = null
    /** Diashow gestartet; kommen wir wieder nach vorn, hat jemand sie verlassen. */
    private var diashowUnterwegs = false
        set(v) { field = v; Verbindung.diashowGestartet = v }
    private var sofortDiashow = false
    private var diashowNeu = false
    /** Home-Taste gedrueckt: immer zum PIN-Schirm, nie direkt ins Menue. */
    private var homeGedrueckt = false
    private var vordergrund = false
        set(v) { field = v; Verbindung.kioskVorn = v }
    private var letzteBedienung = 0L

    // PIN-Eingabe (PIN-Schirm und "PIN aendern")
    private val eingabe = StringBuilder()
    private var neuePinErste: String? = null
    private var pinTitel: TextView? = null
    private var pinAnzeige: TextView? = null
    private var pinMeldung: TextView? = null
    private var okKnopf: View? = null

    /**
     * Leerlauf abgelaufen. Nur wenn der Kiosk wirklich vorn ist und den Fokus hat: liegt eine
     * andere App, ein Systemfenster (Schnelleinstellungen, Benachrichtigung) oder unser
     * Updater darueber, funkt der Kiosk nicht dazwischen und wartet die volle Zeit erneut ab.
     */
    private val zeitAb = Runnable {
        if (vordergrund && hasWindowFocus()) diashowStarten() else zeitNeu()
    }
    private val sperreTakt = object : Runnable {
        override fun run() {
            val rest = speicher.sperreRestMs()
            if (ansicht != Ansicht.PIN) return
            if (rest > 0) {
                meldung("Zu viele Fehlversuche. Bitte warten: ${dauer(rest)}", Farben.WARNUNG)
                h.postDelayed(this, 1000)
            } else {
                meldung("", Farben.GRAU)
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        speicher = KioskSpeicher(this)
        Verbindung.starten(this)
        Verbindung.beobachter = { verbindungGeaendert() }
        Verbindungsdienst.starten(this)
        when {
            b != null -> diashowUnterwegs = b.getBoolean(ZUSTAND_DIASHOW, false)
            // nach dem Einschalten bzw. Start durch den Boot-Empfaenger: Diashow
            speicher.ersterStartSeitBoot(this) || intent.getBooleanExtra(EXTRA_DIASHOW, false) -> sofortDiashow = true
            // spaeter (Home-Taste, App-Symbol, nachdem Android den Kiosk beendet hat): PIN-Schirm
            else -> homeGedrueckt = true
        }
    }

    override fun onDestroy() {
        if (Verbindung.beobachter != null) Verbindung.beobachter = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(o: Bundle) {
        super.onSaveInstanceState(o)
        o.putBoolean(ZUSTAND_DIASHOW, diashowUnterwegs)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        if (i.getBooleanExtra(EXTRA_GESPERRT, false)) { entsperrt = false; sofortDiashow = false }
        else if (i.getBooleanExtra(EXTRA_DIASHOW_NEU, false)) { sofortDiashow = true; diashowNeu = true }
        else if (i.getBooleanExtra(EXTRA_DIASHOW, false)) sofortDiashow = true
        // Home-Taste (von ueberall: Diashow, andere App, Kiosk-Menue): Freigabe verwerfen, PIN verlangen
        else if (i.hasCategory(Intent.CATEGORY_HOME)) { homeGedrueckt = true; entsperrt = false }
        // Auftrag von Verbindung (Sperre, Diashow, Neustart), waehrend der Kiosk schon vorn ist:
        // Android ruft dann nicht immer onResume — also hier erledigen, falls es onResume nicht tat.
        if (i.getBooleanExtra(EXTRA_GESPERRT, false) || i.getBooleanExtra(EXTRA_DIASHOW, false) || i.getBooleanExtra(EXTRA_DIASHOW_NEU, false)) {
            h.post {
                if (!vordergrund) return@post
                when {
                    speicher.gesperrt -> if (ansicht != Ansicht.GESPERRT) zeigeGesperrt()
                    sofortDiashow -> { sofortDiashow = false; val neu = diashowNeu; diashowNeu = false; diashowStarten(neu) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vordergrund = true
        val jetzt = SystemClock.elapsedRealtime()
        // Gesperrt auf /stationen: nichts als der Sperrhinweis, auch nach Home oder Neustart.
        if (speicher.gesperrt) { sofortDiashow = false; homeGedrueckt = false; diashowUnterwegs = false; zeigeGesperrt(); return }
        when {
            sofortDiashow -> { sofortDiashow = false; val neu = diashowNeu; diashowNeu = false; diashowStarten(neu) }
            homeGedrueckt -> {
                homeGedrueckt = false
                diashowUnterwegs = false
                // Auf dem PIN-Schirm bewirkt Home nichts (Eingabe bleibt), sonst PIN-Schirm
                if (ansicht == Ansicht.PIN) zeitNeu() else zeigePin()
            }
            diashowUnterwegs -> { diashowUnterwegs = false; zeigePin() }
            entsperrt && jetzt - letzteBedienung < ENTSPERRT_MS && ansicht != Ansicht.LEER -> {
                letzteBedienung = jetzt
                zeige(ansicht)
            }
            else -> { entsperrt = false; zeigePin() }
        }
    }

    override fun onPause() {
        vordergrund = false
        h.removeCallbacks(zeitAb)
        h.removeCallbacks(sperreTakt)
        super.onPause()
    }

    /** Jede Taste (auch Steuerkreuz/Fokuswechsel) startet die Leerlaufzeit neu. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        letzteBedienung = SystemClock.elapsedRealtime()
        zeitNeu()
    }

    /** Maus, Air-Remote, Joystick: zaehlt ebenfalls als Bedienung. */
    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        onUserInteraction()
        return super.dispatchGenericMotionEvent(e)
    }

    override fun onWindowFocusChanged(fokus: Boolean) {
        super.onWindowFocusChanged(fokus)
        // zurueck aus einem Systemfenster: Zeit beginnt von vorn
        if (fokus && vordergrund) zeitNeu()
    }

    /**
     * Leerlauf neu starten. Die 30 s gelten nur auf dem PIN-Schirm (nach Verlassen der
     * Diashow); hinter der PIN (Apps, Einstellungen, PIN aendern, Diashow-App waehlen) nur
     * die 10 min ohne jede Bedienung. Laeuft eine andere App, ist der Kiosk pausiert und
     * es laeuft gar kein Zaehler (onPause).
     */
    private fun zeitNeu() {
        h.removeCallbacks(zeitAb)
        val ms = when (ansicht) {
            Ansicht.LEER, Ansicht.GESPERRT -> return
            Ansicht.PIN -> PIN_MS
            Ansicht.APPS, Ansicht.EINSTELLUNGEN, Ansicht.PIN_AENDERN, Ansicht.PAKET_WAEHLEN,
            Ansicht.KOPPELN, Ansicht.TRENNEN -> ENTSPERRT_MS
        }
        if (vordergrund) h.postDelayed(zeitAb, ms)
    }

    // ---- Tasten der Fernbedienung ----------------------------------------------------------

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        // Gesperrt: keine Taste bewirkt etwas (Lautstaerke und Ein/Aus bleiben beim System).
        if (ansicht == Ansicht.GESPERRT && code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN
            && code != KeyEvent.KEYCODE_VOLUME_MUTE && code != KeyEvent.KEYCODE_POWER) return true
        val pinEingabe = ansicht == Ansicht.PIN || ansicht == Ansicht.PIN_AENDERN
        when {
            code == KeyEvent.KEYCODE_BACK -> { e.startTracking(); return true }
            // Zifferntasten der Fernbedienung: danach steht der Fokus auf OK (Mitte-Taste bestaetigt)
            pinEingabe && code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { ziffer('0' + (code - KeyEvent.KEYCODE_0)); okKnopf?.requestFocus(); return true }
            pinEingabe && code in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { ziffer('0' + (code - KeyEvent.KEYCODE_NUMPAD_0)); okKnopf?.requestFocus(); return true }
            pinEingabe && code == KeyEvent.KEYCODE_DEL -> { loeschen(); return true }
            pinEingabe && code == KeyEvent.KEYCODE_NUMPAD_ENTER -> { bestaetigen(); return true }
        }
        return super.onKeyDown(code, e)
    }

    override fun onKeyUp(code: Int, e: KeyEvent): Boolean {
        if (ansicht == Ansicht.GESPERRT && code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN
            && code != KeyEvent.KEYCODE_VOLUME_MUTE) return true
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.isTracking && !e.isCanceled) zurueck()
            return true
        }
        return super.onKeyUp(code, e)
    }

    private fun zurueck() {
        when (ansicht) {
            Ansicht.PIN -> if (eingabe.isNotEmpty()) { eingabe.setLength(0); pinZeigen() } else diashowStarten()
            Ansicht.APPS, Ansicht.LEER -> diashowStarten()
            Ansicht.EINSTELLUNGEN -> zeigeApps()
            Ansicht.PIN_AENDERN, Ansicht.PAKET_WAEHLEN, Ansicht.TRENNEN -> zeigeEinstellungen()
            Ansicht.KOPPELN -> if (entsperrt) zeigeEinstellungen() else diashowStarten()
            Ansicht.GESPERRT -> {}
        }
    }

    private fun zeige(a: Ansicht) = when (a) {
        Ansicht.APPS -> zeigeApps()
        Ansicht.EINSTELLUNGEN -> zeigeEinstellungen()
        Ansicht.PIN_AENDERN -> zeigePinAendern()
        Ansicht.PAKET_WAEHLEN -> zeigePaketWaehlen()
        Ansicht.KOPPELN -> zeigeKoppeln()
        Ansicht.TRENNEN -> zeigeTrennen()
        else -> zeigePin()
    }

    // ---- Diashow -----------------------------------------------------------------------------

    private fun startIntent(paket: String): Intent? =
        packageManager.getLeanbackLaunchIntentForPackage(paket) ?: packageManager.getLaunchIntentForPackage(paket)

    /** neu: Diashow-App neu starten (Aufgabe leeren), angefordert auf /stationen. */
    private fun diashowStarten(neu: Boolean = false) {
        if (speicher.gesperrt) { zeigeGesperrt(); return }
        entsperrt = false
        entsperrPin = null
        eingabe.setLength(0)
        val paket = speicher.zielPaket
        val i = startIntent(paket)
        if (i == null) {
            zeigePin("Diashow-App $paket ist nicht installiert.")
            return
        }
        // Hinter der Diashow nur das Logo, nie die App-Liste
        ansicht = Ansicht.LEER
        h.removeCallbacks(zeitAb)
        setContentView(logoSchirm())
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                if (neu) Intent.FLAG_ACTIVITY_CLEAR_TASK else Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            diashowUnterwegs = true
        } catch (e: ActivityNotFoundException) {
            zeigePin("Diashow lässt sich nicht starten ($paket).")
        } catch (e: SecurityException) {
            zeigePin("Diashow lässt sich nicht starten ($paket).")
        }
    }

    // ---- PIN-Schirm ----------------------------------------------------------------------------

    private fun zeigePin(fehler: String? = null) {
        if (speicher.gesperrt) { zeigeGesperrt(); return }
        // Ohne PIN kommt niemand hinein: dann gleich den Weg zeigen, wie der Fernseher eine bekommt.
        if (!speicher.pinGesetzt && !speicher.gekoppelt) { zeigeKoppeln(); return }
        ansicht = Ansicht.PIN
        entsperrt = false
        entsperrPin = null
        eingabe.setLength(0)
        setContentView(pinSchirm("PIN eingeben",
            "Ohne Eingabe geht es nach ${PIN_MS / 1000} s zurück zur Diashow.\nJede Taste startet die Zeit neu. Zurück-Taste: sofort."))
        when {
            fehler != null -> meldung(fehler, Farben.WARNUNG)
            !speicher.pinGesetzt -> meldung("Noch keine PIN — sie kommt von /stationen (Gerät ist verbunden).", Farben.WARNUNG)
        }
        h.removeCallbacks(sperreTakt)
        if (speicher.sperreRestMs() > 0) sperreTakt.run()
        zeitNeu()
    }

    private fun zeigePinAendern() {
        ansicht = Ansicht.PIN_AENDERN
        eingabe.setLength(0)
        neuePinErste = null
        setContentView(pinSchirm("Neue PIN (4 bis 8 Ziffern)", "Zurück-Taste: abbrechen."))
        zeitNeu()
    }

    private fun pinSchirm(titel: String, hinweis: String): View {
        val links = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(ImageView(context).apply { setImageResource(R.drawable.rosenweg_logo) },
                LinearLayout.LayoutParams(dp(220), dp(220)))
            addView(text(getString(R.string.app_name), 34f, fett = true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(8))
            })
            addView(text(hinweis, 18f, Farben.GRAU).apply { gravity = Gravity.CENTER })
        }
        pinTitel = text(titel, 28f, fett = true).apply { gravity = Gravity.CENTER }
        pinAnzeige = text("", 44f).apply { gravity = Gravity.CENTER; letterSpacing = 0.25f }
        pinMeldung = text("", 18f, Farben.GRAU).apply { gravity = Gravity.CENTER; minLines = 2 }
        val feld = GridLayout(this).apply {
            columnCount = 3
            val tasten = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
            for (t in tasten) {
                val k = when (t) {
                    "⌫" -> knopf(t, 30f) { loeschen() }
                    "OK" -> knopf(t, 28f) { bestaetigen() }.also { okKnopf = it }
                    else -> knopf(t, 34f) { ziffer(t[0]) }
                }
                k.setPadding(0, 0, 0, 0)
                k.contentDescription = if (t == "⌫") "Löschen" else t
                addView(k, GridLayout.LayoutParams().apply {
                    width = dp(118); height = dp(62)
                    setMargins(dp(6), dp(5), dp(6), dp(5))
                })
            }
        }
        val rechts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(pinTitel)
            addView(pinAnzeige, LinearLayout.LayoutParams(dp(420), dp(64)))
            addView(pinMeldung, LinearLayout.LayoutParams(dp(440), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(feld)
        }
        pinZeigen()
        val wurzel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(40), dp(16), dp(40), dp(16))
            addView(links, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(rechts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        feld.getChildAt(0).requestFocus()
        return wurzel
    }

    private fun pinZeigen() {
        pinAnzeige?.apply {
            if (eingabe.isEmpty()) { text = "○ ○ ○ ○"; setTextColor(Farben.GRAU) }
            else { text = "●".repeat(eingabe.length).toCharArray().joinToString(" "); setTextColor(Farben.TEXT) }
        }
    }

    private fun meldung(s: String, farbe: Int) {
        pinMeldung?.apply { text = s; setTextColor(farbe) }
    }

    private fun ziffer(z: Char) {
        if (ansicht == Ansicht.PIN && speicher.sperreRestMs() > 0) return
        if (eingabe.length >= 8) return
        eingabe.append(z)
        pinZeigen()
    }

    private fun loeschen() {
        if (eingabe.isNotEmpty()) eingabe.setLength(eingabe.length - 1)
        pinZeigen()
    }

    private fun bestaetigen() {
        if (ansicht == Ansicht.PIN_AENDERN) { neuePinBestaetigen(); return }
        if (ansicht != Ansicht.PIN) return
        if (!speicher.pinGesetzt) {
            meldung("Noch keine PIN eingerichtet (per adb, siehe Anleitung).", Farben.WARNUNG); return
        }
        if (eingabe.isEmpty()) return
        val versuch = eingabe.toString()
        eingabe.setLength(0)
        pinZeigen()
        when (val r = speicher.pruefen(versuch)) {
            KioskSpeicher.Ergebnis.Richtig -> {
                entsperrt = true
                entsperrPin = versuch
                // /stationen kennt die PIN noch nicht (Fernseher von vor der Kopplung): melden.
                if (speicher.gekoppelt && !speicher.pinAmServerBekannt && speicher.pinAusstehend == null) {
                    speicher.pinZumMelden(versuch, speicher.pinStand ?: java.time.Instant.now().toString(), "entsperrt")
                    Verbindung.jetzt()
                }
                letzteBedienung = SystemClock.elapsedRealtime()
                zeigeApps()
            }
            is KioskSpeicher.Ergebnis.Falsch -> {
                if (r.wartenMs > 0) sperreTakt.run()
                else {
                    val noch = KioskSpeicher.FREIE_VERSUCHE - r.versuche
                    meldung("Falsche PIN. " + if (noch == 1) "Noch 1 Versuch ohne Wartezeit." else "Noch $noch Versuche ohne Wartezeit.", Farben.WARNUNG)
                }
            }
            is KioskSpeicher.Ergebnis.Gesperrt -> sperreTakt.run()
            KioskSpeicher.Ergebnis.KeinePin -> meldung("Noch keine PIN eingerichtet.", Farben.WARNUNG)
        }
    }

    private fun neuePinBestaetigen() {
        val neu = eingabe.toString()
        eingabe.setLength(0)
        pinZeigen()
        if (!KioskSpeicher.pinGueltig(neu)) { meldung("Die PIN braucht 4 bis 8 Ziffern.", Farben.WARNUNG); return }
        val erste = neuePinErste
        if (erste == null) {
            neuePinErste = neu
            pinTitel?.text = "Neue PIN wiederholen"
            meldung("", Farben.GRAU)
        } else if (erste == neu) {
            val stand = java.time.Instant.now().toString()
            speicher.pinSetzen(neu, stand)
            entsperrPin = neu
            if (speicher.gekoppelt) {
                // Zurueck an /stationen (HTTPS, Stationstoken); bis dahin nur verschluesselt im Keystore.
                speicher.pinZumMelden(neu, stand, "geaendert")
                Verbindung.jetzt()
            }
            Toast.makeText(this, if (speicher.gekoppelt) "PIN geändert — wird an /stationen gemeldet" else "PIN geändert", Toast.LENGTH_LONG).show()
            zeigeEinstellungen()
        } else {
            neuePinErste = null
            pinTitel?.text = "Neue PIN (4 bis 8 Ziffern)"
            meldung("Die beiden Eingaben stimmen nicht überein. Bitte neu beginnen.", Farben.WARNUNG)
        }
    }

    // ---- Apps --------------------------------------------------------------------------------

    private data class App(val label: String, val info: ActivityInfo)

    /** TV-Apps ohne den Kiosk selbst und ohne die, die schon eine eigene Kachel haben. */
    private fun tvApps(ohne: Set<String>): List<App> {
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return packageManager.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != packageName && it.activityInfo.packageName !in ohne }
            .map { App(it.loadLabel(packageManager).toString(), it.activityInfo) }
            .sortedBy { it.label.lowercase() }
    }

    private fun zeigeApps() {
        ansicht = Ansicht.APPS
        val pm = packageManager
        val spalten = 4
        val breite = 196
        val raster = GridLayout(this).apply { columnCount = spalten }
        fun dazu(v: View) = raster.addView(v, GridLayout.LayoutParams().apply {
            width = dp(breite); setMargins(dp(8), dp(8), dp(8), dp(8))
        })

        val ziel = speicher.zielPaket
        val zielBild = try { pm.getApplicationBanner(ziel) ?: pm.getApplicationIcon(ziel) } catch (e: PackageManager.NameNotFoundException) { null }
        dazu(kachel("Diashow starten", zielBild, zielBild != null, breite) { diashowStarten() })

        val einst = pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo
        dazu(kachel("Einstellungen", einst?.let { it.loadBanner(pm) ?: it.loadIcon(pm) }, einst?.loadBanner(pm) != null, breite) {
            starten(Intent(Settings.ACTION_SETTINGS))
        })
        dazu(kachel("Kiosk-Einstellungen", getDrawable(R.drawable.banner), true, breite) { zeigeEinstellungen() })

        for (a in tvApps(setOfNotNull(ziel, einst?.packageName))) {
            val banner = a.info.loadBanner(pm)
            dazu(kachel(a.label, banner ?: a.info.loadIcon(pm), banner != null, breite) {
                starten(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(a.info.packageName, a.info.name))
            })
        }

        val inhalt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(20), dp(40), dp(24))
            addView(kopf("Apps", "Zurück-Taste: Diashow. Nach ${ENTSPERRT_MS / 60_000} min ohne Bedienung zurück zur Diashow."))
            addView(raster, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; clipChildren = false; addView(inhalt) })
        raster.getChildAt(0).requestFocus()
        zeitNeu()
    }

    private fun starten(i: Intent) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Lässt sich nicht öffnen", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Lässt sich nicht öffnen", Toast.LENGTH_SHORT).show()
        }
    }

    private fun kopf(titel: String, hinweis: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, 0, dp(12))
        addView(ImageView(context).apply { setImageResource(R.drawable.rosenweg_logo) }, LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
            addView(text("${getString(R.string.app_name)} · $titel", 28f, fett = true))
            addView(text(hinweis, 16f, Farben.GRAU))
        })
    }

    // ---- Kiosk-Einstellungen ------------------------------------------------------------------

    private fun appLabel(paket: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(paket, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        "nicht installiert"
    }

    private fun liste(titel: String, hinweis: String, knoepfe: List<View>, fokus: Int = 0) {
        val spalte = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(20), dp(40), dp(24))
            addView(kopf(titel, hinweis))
            for (k in knoepfe) addView(k, LinearLayout.LayoutParams(dp(640), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(80), dp(6), 0, dp(6))
            })
        }
        setContentView(ScrollView(this).apply { clipChildren = false; addView(spalte) })
        knoepfe.getOrNull(fokus)?.requestFocus()
        zeitNeu()
    }

    private fun zeigeEinstellungen() {
        ansicht = Ansicht.EINSTELLUNGEN
        val ziel = speicher.zielPaket
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        val verbindung = if (speicher.gekoppelt) {
            val zuletzt = speicher.zuletztGemeldet
            val wann = if (zuletzt == 0L) "noch nie gemeldet" else "zuletzt gemeldet " + uhrzeit(zuletzt)
            knopf("Rosenweg: verbunden als ${speicher.stationName ?: speicher.stationId} · $wann", 24f) { zeigeTrennen() }
        } else knopf("Mit Rosenweg verbinden", 24f) { zeigeKoppeln() }
        liste("Einstellungen", "Version $version · Zurück-Taste: Apps", listOf(
            knopf("PIN ändern", 24f) { zeigePinAendern() },
            knopf("Diashow-App: ${appLabel(ziel)} ($ziel)", 24f) { zeigePaketWaehlen() },
            verbindung,
            knopf("Nach Updates suchen", 24f) { starten(Intent(this, ch.rosenweg4303.updater.UpdateActivity::class.java)) },
            knopf("Zurück zu den Apps", 24f) { zeigeApps() },
        ))
    }

    private fun zeigePaketWaehlen() {
        ansicht = Ansicht.PAKET_WAEHLEN
        val pm = packageManager
        val ziel = speicher.zielPaket
        val pakete = (pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0) +
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0))
            .map { it.activityInfo.packageName }
            .filter { it != packageName }
            .distinct()
            .map { it to appLabel(it) }
            .sortedBy { it.second.lowercase() }
        val knoepfe = pakete.map { (p, l) ->
            knopf((if (p == ziel) "✓ " else "") + "$l  ($p)", 22f) {
                speicher.diashowAmGeraet(p)
                if (speicher.gekoppelt) Verbindung.jetzt()
                Toast.makeText(this, "Diashow-App: $l", Toast.LENGTH_SHORT).show()
                zeigeEinstellungen()
            }
        }
        liste("Diashow-App wählen", "Diese App startet beim Einschalten und nach Ablauf der Zeit. Zurück-Taste: abbrechen.",
            knoepfe, pakete.indexOfFirst { it.first == ziel }.coerceAtLeast(0))
    }

    // ---- Verbindung zu Rosenweg ----------------------------------------------------------------

    /** Code und QR-Code; Technik bestaetigt auf /stationen → "Fernseher koppeln". */
    private fun zeigeKoppeln() {
        if (speicher.gekoppelt) { zeigeEinstellungen(); return }
        val neu = ansicht != Ansicht.KOPPELN
        ansicht = Ansicht.KOPPELN
        if (neu) Verbindung.kopplungStarten()
        val k = Verbindung.kopplung
        val links = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageResource(R.drawable.rosenweg_logo) }, LinearLayout.LayoutParams(dp(120), dp(120)))
            addView(text("Mit Rosenweg verbinden", 32f, fett = true).apply { setPadding(0, dp(12), 0, dp(12)) })
            addView(text(
                (if (!speicher.pinGesetzt) "Dieser Fernseher hat noch keine PIN. Nach dem Verbinden setzt Technik sie auf /stationen.\n\n" else "") +
                "1. Auf www.rosenweg4303.ch/stationen anmelden (Technik)\n" +
                "2. „Fernseher koppeln“ und diesen Code eingeben — oder den QR-Code mit dem Handy scannen\n" +
                "3. Name, Haus und Standort wählen, „Koppeln“\n\n" +
                "Danach steuert /stationen Diashow-App, PIN und Sperre. Ohne Netz läuft der Kiosk wie gewohnt weiter.",
                19f, Farben.GRAU))
            addView(text("Zurück-Taste: abbrechen", 16f, Farben.GRAU).apply { setPadding(0, dp(16), 0, 0) })
        }
        val rechts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            if (k == null) {
                addView(text(Verbindung.letzterFehler?.let { "Keine Verbindung zu Rosenweg: $it\nNeuer Versuch in wenigen Sekunden …" } ?: "Code wird geholt …",
                    22f, if (Verbindung.letzterFehler != null) Farben.WARNUNG else Farben.GRAU).apply { gravity = Gravity.CENTER })
            } else {
                addView(text(k.code, 56f, fett = true).apply { gravity = Gravity.CENTER; letterSpacing = 0.06f; maxLines = 1 })
                qrBild(k.qr)?.let { bmp ->
                    addView(ImageView(context).apply { setImageBitmap(bmp); setBackgroundColor(Color.WHITE) },
                        LinearLayout.LayoutParams(dp(260), dp(260)).apply { topMargin = dp(12) })
                }
                addView(text("gültig bis " + uhrzeit(k.bisMs) + " · wird von selbst erneuert", 16f, Farben.GRAU).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0)
                })
            }
        }
        // Ein unsichtbarer Fokus-Halter, damit Tasten (und damit die Leerlaufzeit) ankommen
        val halter = View(this).apply { isFocusable = true; isFocusableInTouchMode = true }
        val wurzel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(48), dp(24), dp(48), dp(24))
            addView(links, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f))
            addView(rechts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(halter, LinearLayout.LayoutParams(1, 1))
        }
        setContentView(wurzel)
        halter.requestFocus()
        if (neu) zeitNeu()
    }

    /** QR-Matrix vom Server (Zeilen aus '0'/'1') als Bild, mit Ruhezone, scharf skaliert. */
    private fun qrBild(zeilen: List<String>): Bitmap? {
        val n = zeilen.size
        if (n < 21 || zeilen.any { it.length != n }) return null
        val rand = 4
        val px = 8
        val seite = (n + 2 * rand) * px
        val pixel = IntArray(seite * seite) { Color.WHITE }
        for (y in 0 until n) for (x in 0 until n) if (zeilen[y][x] == '1') {
            for (dy in 0 until px) for (dx in 0 until px) pixel[((y + rand) * px + dy) * seite + (x + rand) * px + dx] = Color.BLACK
        }
        return Bitmap.createBitmap(pixel, seite, seite, Bitmap.Config.ARGB_8888)
    }

    private fun zeigeTrennen() {
        ansicht = Ansicht.TRENNEN
        liste("Von Rosenweg trennen?", "Der Fernseher meldet sich ab und verschwindet von /stationen. PIN und Diashow-App bleiben. Zurück-Taste: abbrechen.", listOf(
            knopf("Nein, verbunden bleiben", 24f) { zeigeEinstellungen() },
            knopf("Ja, trennen", 24f) {
                Verbindung.trennen { ok ->
                    Toast.makeText(this, if (ok) "Getrennt" else "Trennen ging nicht (keine Verbindung?)", Toast.LENGTH_LONG).show()
                    if (ansicht == Ansicht.TRENNEN) zeigeEinstellungen()
                }
            },
        ))
    }

    /** Auf /stationen gesperrt: nur dieser Hinweis. */
    private fun zeigeGesperrt() {
        entsperrt = false
        entsperrPin = null
        eingabe.setLength(0)
        ansicht = Ansicht.GESPERRT
        h.removeCallbacks(zeitAb)
        val grund = speicher.sperrGrund
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            addView(ImageView(context).apply { setImageResource(R.drawable.rosenweg_logo) }, LinearLayout.LayoutParams(dp(180), dp(180)))
            addView(text("Dieser Fernseher ist gesperrt", 36f, fett = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(8)) })
            if (!grund.isNullOrBlank()) addView(text(grund, 24f, Farben.WARNUNG).apply { gravity = Gravity.CENTER })
            addView(text("Bitte bei der Technik der Siedlung Rosenweg melden.", 20f, Farben.GRAU).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) })
        })
    }

    /** Von Verbindung (Hauptfaden): Sperre, Kopplung, PIN oder Diashow-App vom Server. */
    private fun verbindungGeaendert() {
        when {
            speicher.gesperrt -> if (ansicht != Ansicht.GESPERRT && vordergrund) zeigeGesperrt()
            // entsperrt: Verbindung holt die Diashow selbst nach vorn (EXTRA_DIASHOW)
            ansicht == Ansicht.GESPERRT -> {}
            ansicht == Ansicht.KOPPELN && speicher.gekoppelt -> {
                Verbindungsdienst.starten(this)
                Toast.makeText(this, "Verbunden als ${speicher.stationName ?: speicher.stationId}", Toast.LENGTH_LONG).show()
                // Die PIN, mit der gerade entsperrt wurde, gleich melden: dann zeigt /stationen sie sofort.
                val pin = entsperrPin
                if (pin != null && !speicher.pinAmServerBekannt && speicher.pinAusstehend == null) {
                    speicher.pinZumMelden(pin, speicher.pinStand ?: java.time.Instant.now().toString(), "entsperrt")
                    Verbindung.jetzt()
                }
                if (entsperrt) zeigeEinstellungen() else zeigePin()
            }
            ansicht == Ansicht.KOPPELN -> zeigeKoppeln()
        }
    }

    private fun uhrzeit(ms: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY).format(java.util.Date(ms))

    // ---- Logo hinter der Diashow --------------------------------------------------------------

    private fun logoSchirm(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(ImageView(context).apply { setImageResource(R.drawable.rosenweg_logo) }, LinearLayout.LayoutParams(dp(200), dp(200)))
        addView(text(getString(R.string.app_name), 30f, fett = true).apply { setPadding(0, dp(16), 0, 0) })
    }

    companion object {
        const val EXTRA_DIASHOW = "ch.rosenweg4303.kiosk.DIASHOW"
        const val EXTRA_DIASHOW_NEU = "ch.rosenweg4303.kiosk.DIASHOW_NEU"
        const val EXTRA_GESPERRT = "ch.rosenweg4303.kiosk.GESPERRT"
        private const val ZUSTAND_DIASHOW = "diashow_unterwegs"
        const val PIN_MS = 30_000L
        const val ENTSPERRT_MS = 10 * 60_000L

        private fun dauer(ms: Long): String {
            val s = (ms + 999) / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
