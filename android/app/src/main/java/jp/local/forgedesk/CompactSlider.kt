package jp.local.forgedesk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Material gestures, keyboard controls and accessibility semantics with a low-profile track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompactSlider(value:Float,onValueChange:(Float)->Unit,
                           valueRange:ClosedFloatingPointRange<Float>,steps:Int=0) {
    val active=MaterialTheme.colorScheme.primary
    val inactive=MaterialTheme.colorScheme.outlineVariant
    val fraction=((value-valueRange.start)/(valueRange.endInclusive-valueRange.start)).coerceIn(0f,1f)
    Slider(value=value,onValueChange=onValueChange,valueRange=valueRange,steps=steps,
        modifier=Modifier.fillMaxWidth().height(36.dp),
        thumb={ Box(Modifier.size(15.dp).background(active,CircleShape)) },
        track={
            Box(Modifier.fillMaxWidth().height(3.dp).background(inactive,CircleShape)){
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(active,CircleShape))
            }
        })
}
