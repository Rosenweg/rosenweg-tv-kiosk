// SPDX-License-Identifier: GPL-3.0-or-later
package ch.rosenweg4303.kiosk

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Bausteine der Oberflaeche: gross, kontrastreich, mit deutlichem Fokus fuer die Fernbedienung. */
object Farben {
    const val ROT = 0xFFD32F2F.toInt()
    const val FLAECHE = 0xFF3A2A2A.toInt()
    const val TEXT = Color.WHITE
    const val GRAU = 0xFFBDB2B2.toInt()
    const val WARNUNG = 0xFFFFC107.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** Hintergrund mit Fokus-Zustand: fokussiert rot mit weissem Rand, sonst dunkle Flaeche. */
fun Context.fokusHintergrund(radiusDp: Int = 14): Drawable {
    fun form(farbe: Int, rand: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(farbe)
        if (rand > 0) setStroke(dp(rand), Color.WHITE)
    }
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), form(Farben.ROT, 4))
        addState(intArrayOf(android.R.attr.state_pressed), form(Farben.ROT, 0))
        addState(intArrayOf(), form(Farben.FLAECHE, 0))
    }
}

fun Context.text(s: CharSequence, sp: Float, farbe: Int = Farben.TEXT, fett: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(farbe)
        if (fett) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.knopf(s: CharSequence, sp: Float = 26f, klick: () -> Unit): Button = Button(this).apply {
    text = s
    isAllCaps = false
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(Farben.TEXT)
    background = fokusHintergrund()
    stateListAnimator = null
    isFocusable = true
    setOnClickListener { klick() }
    fokusVergroessern(1.06f)
}

fun View.fokusVergroessern(faktor: Float) {
    setOnFocusChangeListener { v, f ->
        v.animate().scaleX(if (f) faktor else 1f).scaleY(if (f) faktor else 1f).setDuration(120).start()
    }
}

/** Kachel fuer eine App: Banner (16:9) oder Symbol, darunter der Name. */
fun Context.kachel(titel: CharSequence, bild: Drawable?, istBanner: Boolean, breiteDp: Int, klick: () -> Unit): View =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = fokusHintergrund(12)
        isFocusable = true
        isClickable = true
        setPadding(dp(8), dp(8), dp(8), dp(6))
        val bildHoehe = (breiteDp - 16) * 9 / 16
        addView(ImageView(context).apply {
            setImageDrawable(bild)
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (!istBanner) setPadding(dp(18), dp(10), dp(18), dp(10))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(bildHoehe)))
        addView(text(titel, 18f).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setOnClickListener { klick() }
        fokusVergroessern(1.08f)
    }
