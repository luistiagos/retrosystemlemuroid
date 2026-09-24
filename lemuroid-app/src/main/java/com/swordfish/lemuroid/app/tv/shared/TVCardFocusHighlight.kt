package com.swordfish.lemuroid.app.tv.shared

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import timber.log.Timber
import java.lang.reflect.Method

/**
 * Adds a prominent colored border around [ImageCardView] cards when they receive
 * focus.  The default Leanback focus animation (slight zoom + shadow) is too subtle
 * on low-end TV boxes, making it hard to tell which item is selected.
 */
object TVCardFocusHighlight {

    private const val BORDER_WIDTH_DP = 3

    /**
     * `FrameLayout.setForeground` existe desde a API 1, mas na API 23 subiu para `View` e saiu do
     * `android.jar` de `FrameLayout` — então o compilador resolve `foreground` contra `View` e a
     * chamada direta é `NoSuchMethodError` em Android 5.0/5.1, justamente a TV Box velha para a
     * qual esta borda existe. A reflexão acha o método que o aparelho realmente tem: em Android
     * 5.x o de `FrameLayout`, da API 23 em diante o de `View`, herdado.
     */
    private val legacySetForeground: Method? by lazy {
        runCatching {
            FrameLayout::class.java.getMethod("setForeground", Drawable::class.java)
        }.getOrNull()
    }

    fun setupOnCard(cardView: ImageCardView, onFocusChanged: ((Boolean) -> Unit)? = null) {
        val density = cardView.resources.displayMetrics.density
        val borderPx = (BORDER_WIDTH_DP * density).toInt()
        val colorResId = cardView.resources.getIdentifier("main_color", "color", cardView.context.packageName)
        val focusColor =
            if (colorResId != 0) ContextCompat.getColor(cardView.context, colorResId)
            else Color.parseColor("#FF9800")

        cardView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                val border = GradientDrawable().apply {
                    setStroke(borderPx, focusColor)
                    setColor(0x00000000) // transparent fill
                }
                v.setFocusBorderCompat(border)
            } else {
                v.setFocusBorderCompat(null)
            }

            onFocusChanged?.invoke(hasFocus)
        }
    }

    /**
     * O guard por `SDK_INT` anda com `catch (NoSuchMethodError)` pelo motivo do pitfall 12 do
     * CLAUDE.md: as TV Box baratas anunciam Android 9/11 rodando 7.1 de verdade. Falhar aqui só
     * custa a borda — o realce padrão do Leanback continua —, então nenhum caminho propaga.
     */
    private fun View.setFocusBorderCompat(border: Drawable?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                foreground = border
                return
            } catch (e: NoSuchMethodError) {
                Timber.w(e, "View.setForeground ausente com SDK_INT=${Build.VERSION.SDK_INT}")
            }
        }

        runCatching { legacySetForeground?.invoke(this, border) }
            .onFailure { Timber.w(it, "Não foi possível aplicar a borda de foco") }
    }
}
