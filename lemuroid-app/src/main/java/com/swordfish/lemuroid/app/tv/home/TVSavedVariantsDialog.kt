package com.swordfish.lemuroid.app.tv.home

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.tv.shared.TVTypography
import com.swordfish.lemuroid.lib.library.db.entity.Game

/**
 * Picker of the saved versions of a group (redesign 3.6), the TV counterpart of the phone's `GameVariantsModal`
 * opened with only the saved ones: one line per version, by file name without extension, as there.
 */
object TVSavedVariantsDialog {
    fun show(
        context: Context,
        title: String,
        variants: List<Game>,
        onVariantSelected: (Game) -> Unit,
    ) {
        // `Builder(context, themeId)`: with a ContextThemeWrapper the Builder ignores the theme (CLAUDE.md, TV).
        val themedContext = ContextThemeWrapper(context, R.style.LemuroidTVDialog)
        val labels = variants.map { fileNameWithoutExtension(it.fileName) }
        val adapter =
            object : ArrayAdapter<String>(themedContext, android.R.layout.select_dialog_item, labels) {
                override fun getView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup,
                ): View {
                    val view = super.getView(position, convertView, parent)
                    TVTypography.apply(view as? TextView, TVTypography.Weight.REGULAR)
                    return view
                }
            }

        val dialog =
            AlertDialog.Builder(context, R.style.LemuroidTVDialog)
                .setCustomTitle(titleView(themedContext, title))
                .setAdapter(adapter) { _, which -> variants.getOrNull(which)?.let(onVariantSelected) }
                .setNegativeButton(R.string.rom_download_dialog_cancel, null)
                .show()

        // Outfit on the button; it only exists after `show()`.
        TVTypography.apply(dialog.getButton(DialogInterface.BUTTON_NEGATIVE), TVTypography.Weight.MEDIUM)
    }

    /** Own title view: the one of the framework has an internal id, out of reach of [TVTypography]. */
    private fun titleView(
        context: Context,
        title: String,
    ): TextView {
        val padding = (TITLE_PADDING_DP * context.resources.displayMetrics.density).toInt()
        return TextView(context).apply {
            text = title
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(context, R.color.leanback_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_TEXT_SP)
            setPadding(padding, padding, padding, padding / 3)
            TVTypography.apply(this, TVTypography.Weight.BOLD)
        }
    }

    private const val TITLE_PADDING_DP = 24
    private const val TITLE_TEXT_SP = 22f

    private fun fileNameWithoutExtension(fileName: String): String {
        val dotIndex = fileName.lastIndexOf('.')
        return if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
    }
}
