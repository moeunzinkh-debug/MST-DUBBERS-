package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.media.BlurStrength
import com.example.ui.theme.DarkCard2
import com.example.ui.theme.DarkFg
import com.example.ui.theme.DarkLine
import com.example.ui.theme.DarkMuted
import com.example.ui.theme.SkyAccent
import kotlin.math.abs

/**
 * The small panel that opens when the blur button is held down: a strength slider from weak to
 * strong, quick Weak / Medium / Strong choices and the on/off switch of the blur box.
 */
@Composable
fun BlurStrengthPanel(
    strength: Float,
    blurOn: Boolean,
    onStrengthChange: (Float) -> Unit,
    onStrengthChangeFinished: () -> Unit,
    onBlurOnChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(280.dp)
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .testTag("blur_strength_panel")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.BlurOn,
                contentDescription = null,
                tint = SkyAccent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Blur strength",
                color = DarkFg,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${BlurStrength.label(strength)} · ${BlurStrength.percent(strength)}%",
                color = SkyAccent,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("blur_strength_value")
            )
        }

        Slider(
            value = BlurStrength.clamp(strength),
            onValueChange = onStrengthChange,
            onValueChangeFinished = onStrengthChangeFinished,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = SkyAccent,
                activeTrackColor = SkyAccent,
                inactiveTrackColor = DarkLine
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("blur_strength_slider")
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Weak", color = DarkMuted, fontSize = 11.sp, lineHeight = 13.sp)
            Spacer(modifier = Modifier.weight(1f))
            Text("Strong", color = DarkMuted, fontSize = 11.sp, lineHeight = 13.sp)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val presets = listOf(
                "Weak" to BlurStrength.WEAK,
                "Medium" to BlurStrength.MEDIUM,
                "Strong" to BlurStrength.STRONG
            )
            // Highlight the preset closest to the current strength.
            val closest = presets.minBy { abs(it.second - strength) }.second
            for ((label, value) in presets) {
                val selected = value == closest
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) SkyAccent.copy(alpha = 0.22f) else DarkCard2)
                        .border(1.dp, if (selected) SkyAccent else DarkLine, RoundedCornerShape(8.dp))
                        .clickable {
                            onStrengthChange(value)
                            onStrengthChangeFinished()
                        }
                        .padding(vertical = 7.dp)
                        .testTag("blur_preset_${label.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (selected) SkyAccent else DarkFg,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Blur box",
                color = DarkFg,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = blurOn,
                onCheckedChange = onBlurOnChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = SkyAccent,
                    uncheckedThumbColor = DarkMuted,
                    uncheckedTrackColor = DarkCard2,
                    uncheckedBorderColor = DarkLine
                ),
                modifier = Modifier
                    .scale(0.8f)
                    .testTag("blur_panel_switch")
            )
        }
        Text(
            text = "Tap the blur icon to turn it on or off. Hold it to open this panel.",
            color = DarkMuted,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
    }
}
