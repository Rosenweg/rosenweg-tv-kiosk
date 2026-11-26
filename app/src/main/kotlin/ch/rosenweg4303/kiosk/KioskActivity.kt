// SPDX-License-Identifier: GPL-3.0-or-later
package ch.rosenweg4303.kiosk

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
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
 *  - Zurueck aus der Diashow (oder Home-Taste): PIN-Schirm; ohne Eingabe nach 30 s wieder Diashow.
 *  - Richtige PIN: Apps, Android-Einstellungen, Kiosk-Einstellungen, "Diashow starten";
 *    nach 10 min ohne Bedienung wieder Diashow.
 *
 * Alles in einer Activity mit wechselnden Ansichten, damit die Zeitgrenzen an einer Stelle liegen.
 */
class KioskActivity : Activity() {

    private enum class Ansicht { LEER, PIN, APPS, EINSTELLUNGEN, PIN_AENDERN, PAKET_WAEHLEN }

    private lateinit var speicher: KioskSpeicher
    private val h = Handler(Looper.getMainLooper())

    private var ansicht = Ansicht.LEER
    private var entsperrt = false
    /** Diashow gestartet; kommen wir wieder nach vorn, hat jemand sie verlassen. */
    private var diashowUnterwegs = false
    private var sofortDiashow = false
    private var vordergrund = false
    private var letzteBedienung = 0L

    // PIN-Eingabe (PIN-Schirm und "PIN aendern")
    private val eingabe = StringBuilder()
    private var neuePinErste: String? = null
    private var pinTitel: TextView? = null
    private var pinAnzeige: TextView? = null
    private var pinMeldung: TextView? = null
    private var okKnopf: View? = null

    private val zeitAb = Runnable { if (vordergrund) diashowStarten() }
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
        if (b == null) sofortDiashow = true
        else diashowUnterwegs = b.getBoolean(ZUSTAND_DIASHOW, false)
    }

    override fun onSaveInstanceState(o: Bundle) {
        super.onSaveInstanceState(o)
        o.putBoolean(ZUSTAND_DIASHOW, diashowUnterwegs)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        if (i.getBooleanExtra(EXTRA_DIASHOW, false)) sofortDiashow = true
        // Home-Taste bei entsperrtem Kiosk: zurueck zur App-Liste
        else if (entsperrt && i.hasCategory(Intent.CATEGORY_HOME)) ansicht = Ansicht.APPS
    }

    override fun onResume() {
        super.onResume()
        vordergrund = true
        val jetzt = SystemClock.elapsedRealtime()
        when {
            sofortDiashow -> { sofortDiashow = false; diashowStarten() }
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

    override fun onUserInteraction() {
        super.onUserInteraction()
        letzteBedienung = SystemClock.elapsedRealtime()
        zeitNeu()
    }

    private fun zeitNeu() {
        h.removeCallbacks(zeitAb)
        if (ansicht != Ansicht.LEER) h.postDelayed(zeitAb, if (entsperrt) ENTSPERRT_MS else PIN_MS)
    }

    // ---- Tasten der Fernbedienung ----------------------------------------------------------

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
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
            Ansicht.PIN_AENDERN, Ansicht.PAKET_WAEHLEN -> zeigeEinstellungen()
        }
    }

    private fun zeige(a: Ansicht) = when (a) {
        Ansicht.APPS -> zeigeApps()
        Ansicht.EINSTELLUNGEN -> zeigeEinstellungen()
        Ansicht.PIN_AENDERN -> zeigePinAendern()
        Ansicht.PAKET_WAEHLEN -> zeigePaketWaehlen()
        else -> zeigePin()
    }

    // ---- Diashow -----------------------------------------------------------------------------

    private fun startIntent(paket: String): Intent? =
        packageManager.getLeanbackLaunchIntentForPackage(paket) ?: packageManager.getLaunchIntentForPackage(paket)

    private fun diashowStarten() {
        entsperrt = false
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
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            diashowUnterwegs = true
        } catch (e: ActivityNotFoundException) {
            zeigePin("Diashow lässt sich nicht starten ($paket).")
        } catch (e: SecurityException) {
            zeigePin("Diashow lässt sich nicht starten ($paket).")
        }
    }

    // ---- PIN-Schirm ----------------------------------------------------------------------------

    private fun zeigePin(fehler: String? = null) {
        ansicht = Ansicht.PIN
        entsperrt = false
        eingabe.setLength(0)
        setContentView(pinSchirm("PIN eingeben",
            "Ohne Eingabe geht es nach ${PIN_MS / 1000} s zurück zur Diashow. Zurück-Taste: sofort."))
        when {
            fehler != null -> meldung(fehler, Farben.WARNUNG)
            !speicher.pinGesetzt -> meldung("Noch keine PIN eingerichtet (per adb, siehe Anleitung).", Farben.WARNUNG)
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
            speicher.pinSetzen(neu)
            Toast.makeText(this, "PIN geändert", Toast.LENGTH_LONG).show()
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
            addView(kopf("Apps", "Zurück-Taste: Diashow. Ohne Bedienung nach ${ENTSPERRT_MS / 60_000} min zurück zur Diashow."))
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
        liste("Einstellungen", "Version $version · Zurück-Taste: Apps", listOf(
            knopf("PIN ändern", 24f) { zeigePinAendern() },
            knopf("Diashow-App: ${appLabel(ziel)} ($ziel)", 24f) { zeigePaketWaehlen() },
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
                speicher.zielPaket = p
                Toast.makeText(this, "Diashow-App: $l", Toast.LENGTH_SHORT).show()
                zeigeEinstellungen()
            }
        }
        liste("Diashow-App wählen", "Diese App startet beim Einschalten und nach Ablauf der Zeit. Zurück-Taste: abbrechen.",
            knoepfe, pakete.indexOfFirst { it.first == ziel }.coerceAtLeast(0))
    }

    // ---- Logo hinter der Diashow --------------------------------------------------------------

    private fun logoSchirm(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(ImageView(context).apply { setImageResource(R.drawable.rosenweg_logo) }, LinearLayout.LayoutParams(dp(200), dp(200)))
        addView(text(getString(R.string.app_name), 30f, fett = true).apply { setPadding(0, dp(16), 0, 0) })
    }

    companion object {
        const val EXTRA_DIASHOW = "ch.rosenweg4303.kiosk.DIASHOW"
        private const val ZUSTAND_DIASHOW = "diashow_unterwegs"
        const val PIN_MS = 30_000L
        const val ENTSPERRT_MS = 10 * 60_000L

        private fun dauer(ms: Long): String {
            val s = (ms + 999) / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
