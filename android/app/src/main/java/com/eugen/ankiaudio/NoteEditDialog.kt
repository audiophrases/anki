package com.eugen.ankiaudio

import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The card editor shared by the in-app study round ("Start study (here)") and
 * the eyes-free touch/car surface: one labelled text box per note field,
 * written back through AnkiDroid, after which the engine's current card is
 * reloaded so the edit is reflected on the next replay.
 *
 * The dialog isn't cancelable by back/outside-tap — the caller (e.g. the touch
 * surface) needs a definite Save/Cancel outcome to restore its own state, so
 * [onClosed] is always called exactly once with whether a save landed.
 */
object NoteEditDialog {

    /**
     * Loads [noteId]'s fields and shows the edit dialog. [onStatus] reports
     * progress/results; [onClosed] runs once the dialog is dismissed, with
     * `true` if the fields were saved.
     */
    fun show(
        context: Context,
        engine: StudyEngine,
        scope: CoroutineScope,
        noteId: Long,
        onStatus: (String) -> Unit,
        onClosed: (saved: Boolean) -> Unit,
    ) {
        scope.launch {
            val fields = withContext(Dispatchers.IO) {
                runCatching { AnkiDroidApi.noteFields(context, noteId) }.getOrNull()
            }
            if (fields == null) {
                onStatus("Couldn't load this card's fields.")
                onClosed(false)
                return@launch
            }
            buildDialog(context, engine, scope, noteId, fields, onStatus, onClosed).show()
        }
    }

    /** One labelled text box per note field; Save writes them back via the provider. */
    private fun buildDialog(
        context: Context,
        engine: StudyEngine,
        scope: CoroutineScope,
        noteId: Long,
        fields: AnkiDroidApi.NoteFields,
        onStatus: (String) -> Unit,
        onClosed: (saved: Boolean) -> Unit,
    ): AlertDialog {
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val inputs = fields.values.mapIndexed { i, value ->
            container.addView(TextView(context).apply {
                text = fields.names.getOrElse(i) { "Field ${i + 1}" }
                textSize = 13f
                setPadding(0, (8 * density).toInt(), 0, 0)
            })
            EditText(context).apply {
                setText(value)
                setSelection(text.length)
                container.addView(this)
            }
        }

        return AlertDialog.Builder(context)
            .setTitle("Edit card")
            .setView(ScrollView(context).apply { addView(container) })
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                val newValues = inputs.map { it.text.toString() }
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            AnkiDroidApi.updateNoteFields(context, noteId, newValues)
                        }.getOrDefault(false)
                    }
                    if (ok) {
                        engine.reloadCurrent()
                        onStatus("Card saved.")
                    } else {
                        onStatus("Saving the edit failed.")
                    }
                    onClosed(ok)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> onClosed(false) }
            .create()
    }
}
