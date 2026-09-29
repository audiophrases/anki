package com.eugen.ankiaudio

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The "lights on" face of the touch surface: the card being spoken as readable
 * text, over the two rating halves (tinted once the answer is up, red top =
 * Hard/Again, green bottom = Good/Easy, like the [GestureChart]), with the
 * controls reminder underneath — plus the spoken commands when [voice] is on.
 *
 * Couch mode opens with it; bed and car mode switch to it and back with a
 * three-finger tap (car mode also by saying "bright" / "dark").
 *
 * Nothing in it is clickable, so every tap and swipe still reaches the study
 * surface's gesture handling behind it. Colours follow the phone's light/dark
 * theme; brightness is the user's own (the surface stops forcing it down).
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class LitCardView(context: Context, voice: Boolean) : FrameLayout(context) {

    private class Palette(
        val background: Int, val text: Int, val muted: Int,
        val answer: Int, val again: Int, val good: Int,
    )

    private val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private val palette = if (night) {
        Palette(0xFF121212.toInt(), 0xFFECECEC.toInt(), 0xFF9E9E9E.toInt(),
            0xFF90CAF9.toInt(), 0xFFEF9A9A.toInt(), 0xFFA5D6A7.toInt())
    } else {
        Palette(0xFFFAFAFA.toInt(), 0xFF1B1B1F.toInt(), 0xFF616161.toInt(),
            0xFF1565C0.toInt(), 0xFFC62828.toInt(), 0xFF2E7D32.toInt())
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    private val topZone = View(context)
    private val bottomZone = View(context)
    private val header = label(13f, palette.muted)
    private val topHint = label(15f, palette.again)
    private val bottomHint = label(15f, palette.good)
    private val cardText = TextView(context).apply {
        setTextColor(palette.text)
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.1f)
        // Long cards shrink to fit rather than scroll: a scroll view would eat
        // the swipe-up/down gestures.
        setAutoSizeTextTypeUniformWithConfiguration(13, 34, 1, TypedValue.COMPLEX_UNIT_SP)
    }
    private val footer = label(12.5f, palette.muted).apply { setLineSpacing(dp(3).toFloat(), 1f) }

    init {
        setBackgroundColor(palette.background)

        // The rating halves, split exactly where the surface splits taps.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(topZone, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottomZone, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            fun wrap() = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(header, wrap())
            addView(topHint, wrap())
            addView(cardText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                .apply { setMargins(0, dp(8), 0, dp(8)) })
            addView(bottomHint, wrap())
            addView(footer, wrap().apply { topMargin = dp(10) })
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Full screen with the bars hidden: only the camera cutout needs clearing.
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(20) + cut.left, dp(14) + cut.top, dp(20) + cut.right, dp(14) + cut.bottom)
            insets
        }

        header.typeface = Typeface.DEFAULT_BOLD
        topHint.typeface = Typeface.DEFAULT_BOLD
        bottomHint.typeface = Typeface.DEFAULT_BOLD
        footer.text = buildString {
            if (voice) {
                append("Say: show · repeat · good · easy · hard · again\n")
                append("undo · bookmark · bright · dark · stop\n")
            }
            append("Swipe down: replay · Swipe up: edit card\n")
            append("Two fingers: undo · Long-press: bookmark\n")
            append("Three fingers: lights off · Four fingers: stop")
        }
    }

    private fun label(size: Float, color: Int) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
    }

    /**
     * Shows [card] — its question, plus the answer once [answerShown] — with the
     * tap-zone hints for that phase. With no card (finished / stopped / not
     * loaded yet) the engine's latest [status] fills the card area instead.
     */
    fun show(card: AnkiDroidApi.DueCard?, answerShown: Boolean, deckName: String?, status: String) {
        val phase = when {
            card == null -> null
            answerShown -> "Answer"
            else -> "Question"
        }
        header.text = listOfNotNull(deckName, phase).joinToString("  ·  ")

        val rating = card != null && answerShown
        topZone.setBackgroundColor(if (rating) ColorUtils.setAlphaComponent(palette.again, 0x26) else 0)
        bottomZone.setBackgroundColor(if (rating) ColorUtils.setAlphaComponent(palette.good, 0x26) else 0)
        topHint.visibility = if (rating) View.VISIBLE else View.GONE
        topHint.text = "Top · tap: Hard · double-tap: Again"
        bottomHint.visibility = if (card != null) View.VISIBLE else View.GONE
        bottomHint.text = if (rating) {
            "Bottom · tap: Good · double-tap: Easy"
        } else {
            "Tap anywhere to reveal the answer"
        }
        bottomHint.setTextColor(if (rating) palette.good else palette.muted)

        cardText.text = if (card == null) status else cardBody(card, answerShown)
    }

    /** The question, then (after a gap, in the answer colour) the answer's new lines. */
    private fun cardBody(card: AnkiDroidApi.DueCard, answerShown: Boolean): CharSequence {
        val body = SpannableStringBuilder(card.question.trim())
        if (!answerShown) return body
        // Some templates repeat front-side fields on the back; show them once.
        val seen = card.question.lines().mapTo(HashSet()) { it.trim().lowercase() }
        val answer = card.answer.lines()
            .filter { it.isNotBlank() && it.trim().lowercase() !in seen }
            .joinToString("\n") { it.trim() }
        if (answer.isEmpty()) return body
        if (body.isNotEmpty()) body.append("\n\n")
        val start = body.length
        body.append(answer)
        body.setSpan(ForegroundColorSpan(palette.answer), start, body.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return body
    }
}
