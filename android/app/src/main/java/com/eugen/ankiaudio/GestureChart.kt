package com.eugen.ankiaudio

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The visual gesture reference for the eyes-free touch surface: colour-coded
 * rating halves (red top = Again/Hard, green bottom = Good/Easy, mirroring
 * where you actually tap) plus a legend of the whole-screen gestures.
 *
 * Built once here and reused in two places so they never drift apart:
 *  - [TouchStudyActivity] overlays it on demand (three-finger tap) over the dim
 *    study surface, briefly restoring brightness.
 *  - [GestureChartActivity] shows it as a normal, full-brightness screen from
 *    the home screen, for looking the gestures up before a session.
 *
 * A tap anywhere invokes [onDismiss] (dismiss the overlay / finish the screen).
 */
object GestureChart {

    @SuppressLint("SetTextI18n")
    fun build(context: Context, onDismiss: () -> Unit): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        fun line(text: String, size: Float, color: String, top: Int, bottom: Int) =
            TextView(context).apply {
                this.text = text
                textSize = size
                setTextColor(Color.parseColor(color))
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(top), dp(20), dp(bottom))
            }

        // A rating half: heading + single-tap action + double-tap action.
        fun zone(bg: String, heading: String, tap: String, doubleTap: String) =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor(bg))
                addView(TextView(context).apply {
                    text = heading
                    textSize = 12f
                    setTextColor(Color.parseColor("#99FFFFFF"))
                    gravity = Gravity.CENTER
                    letterSpacing = 0.2f
                })
                addView(TextView(context).apply {
                    text = tap
                    textSize = 25f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, 0)
                })
                addView(TextView(context).apply {
                    text = doubleTap
                    textSize = 16f
                    setTextColor(Color.parseColor("#CCFFFFFF"))
                    gravity = Gravity.CENTER
                })
            }

        fun matchWrap() = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )

        fun matchWeight() = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5000000"))
            isClickable = true // consume touches so nothing leaks to the study surface
            setOnClickListener { onDismiss() }

            addView(line("Gestures", 20f, "#FFFFFFFF", top = 18, bottom = 2), matchWrap())
            addView(
                line("When the answer is showing, tap the half you mean",
                    13f, "#AAFFFFFF", top = 0, bottom = 10),
                matchWrap()
            )
            addView(zone("#4A1A1E", "TOP HALF", "Again", "double-tap: Hard"), matchWeight())
            addView(zone("#12331F", "BOTTOM HALF", "Good", "double-tap: Easy"), matchWeight())
            addView(
                line("During the question, a tap anywhere reveals the answer",
                    13f, "#CCFFFFFF", top = 12, bottom = 8),
                matchWrap()
            )
            addView(
                line(
                    "Swipe up — edit card\n" +
                        "Swipe down — replay\n" +
                        "Two fingers — undo\n" +
                        "Long-press — bookmark\n" +
                        "Three fingers — this chart\n" +
                        "Four fingers — stop",
                    15f, "#DDFFFFFF", top = 0, bottom = 8
                ).apply { setLineSpacing(dp(5).toFloat(), 1f) },
                matchWrap()
            )
            addView(line("Tap anywhere to close", 12f, "#88FFFFFF", top = 6, bottom = 18), matchWrap())
        }
    }
}
