package com.paragrudani.replyly.ui.theme

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.paragrudani.replyly.R
import com.paragrudani.replyly.testing.ScreenshotRule
import com.paragrudani.replyly.testing.ScreenshotTest
import com.paragrudani.replyly.testing.ShotVariant
import com.paragrudani.replyly.testing.captureShot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The visual system at a glance: wordmark, type, colours, components, launcher icon. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotTest.DEVICE)
class BrandScreenshotTest {
    @get:Rule val shots = ScreenshotRule()

    @Test
    fun specimen() = shots.screen("brand/specimen") { Specimen() }

    @Test
    fun launcherIcon() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val px = (108 * activity.resources.displayMetrics.density).toInt()
        val frame =
            LinearLayout(activity).apply {
                // Full icon, then the themed-icon (monochrome) layer as a launcher would tint it.
                addView(
                    ImageView(activity).apply { setImageResource(R.mipmap.ic_launcher) },
                    LinearLayout.LayoutParams(px, px),
                )
                addView(
                    ImageView(activity).apply {
                        setBackgroundColor(Color.rgb(0xDD, 0xE1, 0xFF))
                        setImageResource(R.drawable.ic_launcher_monochrome)
                        imageTintList = ColorStateList.valueOf(Color.rgb(0x0E, 0x1A, 0x5C))
                    },
                    LinearLayout.LayoutParams(px, px),
                )
            }
        activity.setContentView(frame, FrameLayout.LayoutParams(px * 2, px))
        frame.captureShot("brand/launcher_icon", ShotVariant.Light)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Specimen() {
    val type = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(Spacing.XL), verticalArrangement = Arrangement.spacedBy(Spacing.L)) {
        ReplylyWordmark(style = type.headlineMedium)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.XS)) {
            Text("Headline small", style = type.headlineSmall)
            Text("Title medium", style = type.titleMedium)
            Text("Body medium: Haan pakka, kal 6 baje milte hain.", style = type.bodyMedium)
            Text("Body small, for supporting text.", style = type.bodySmall, color = colors.onSurfaceVariant)
            Text("Label medium", style = type.labelMedium, color = colors.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
            listOf(
                colors.primary,
                colors.primaryContainer,
                colors.secondaryContainer,
                colors.tertiaryContainer,
                ReplylyTheme.colors.accent,
            ).forEach { Box(Modifier.size(36.dp).background(it, MaterialTheme.shapes.small)) }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            verticalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            listOf("agree", "decline", "ask").forEach { intent ->
                val pair = ReplylyTheme.colors.forIntent(intent)
                Text(
                    intent,
                    style = type.labelMedium,
                    color = pair.content,
                    modifier =
                        Modifier
                            .background(
                                pair.container,
                                MaterialTheme.shapes.small,
                            ).padding(horizontal = Spacing.S, vertical = Spacing.XS),
                )
            }
            FilterChip(selected = true, onClick = {}, label = { Text("Selected chip") })
        }
        Card(Modifier) {
            Column(Modifier.padding(Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.XS)) {
                Text("Card title", style = type.titleSmall)
                Text("Cards sit flat on a container colour.", style = type.bodySmall)
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            verticalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            Button(onClick = {}) { Text("Save") }
            FilledTonalButton(onClick = {}) { Text("Regenerate") }
            OutlinedButton(onClick = {}) { Text("Cancel") }
            TextButton(onClick = {}) { Text("Skip") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
            Switch(checked = true, onCheckedChange = {})
            Switch(checked = false, onCheckedChange = {})
            Text("Error text", color = colors.error, style = type.bodyMedium)
        }
    }
}
