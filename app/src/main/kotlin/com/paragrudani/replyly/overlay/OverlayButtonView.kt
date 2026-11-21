package com.paragrudani.replyly.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.widget.ImageView
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import com.paragrudani.replyly.R
import com.paragrudani.replyly.ui.theme.Elevation
import com.paragrudani.replyly.ui.theme.dynamicColorSetting
import com.paragrudani.replyly.ui.theme.replylyColorScheme

/**
 * The floating button: the Replyly mark on a round fill, coloured from the Replyly theme
 * (`primary` fill, `onPrimary` mark), so it follows dark mode and the dynamic-colour setting.
 * A View, not Compose: the button window has no lifecycle owners and must stay cheap.
 */
internal fun createOverlayButton(
    context: Context,
    onTap: () -> Unit,
): ImageView =
    ImageView(context).apply {
        setImageResource(R.drawable.overlay_button_icon)
        setBackgroundResource(R.drawable.overlay_button_background)
        scaleType = ImageView.ScaleType.CENTER
        elevation = Elevation.Floating.value * resources.displayMetrics.density
        contentDescription = context.getString(R.string.overlay_button_description)
        setOnClickListener { onTap() }
        applyColors(overlayButtonScheme(context))
    }

/** The scheme for the button right now: system dark mode plus the user's colour setting. */
internal fun overlayButtonScheme(
    context: Context,
    dynamicColor: Boolean = context.dynamicColorSetting(),
): ColorScheme {
    val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return replylyColorScheme(
        context,
        darkTheme = night == Configuration.UI_MODE_NIGHT_YES,
        dynamicColor = dynamicColor,
    )
}

internal fun ImageView.applyColors(scheme: ColorScheme) {
    backgroundTintList = ColorStateList.valueOf(scheme.primary.toArgb())
    imageTintList = ColorStateList.valueOf(scheme.onPrimary.toArgb())
}
