package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import me.rerere.ai.util.AutoTrimConfig
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clean
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.formatBytes
import me.rerere.ui.components.ToggleSurface
import kotlin.math.abs
import kotlin.math.roundToInt

/** 保留最近步数的可选档位，最左端 0 表示关闭 */
private val keepStepsTicks = listOf(0, 4, 8, 12, 16, 20, 24, 32, 40)

/** 最小释放体积的可选档位（KB），最左端 0 表示关闭 */
private val minFreeKbTicks = listOf(0, 16, 32, 48, 64, 96, 128, 192, 256, 384, 512)

/** 上下文水位的可选档位（K token），最左端 0 表示关闭 */
private val contextKTicks = listOf(0, 32, 64, 96, 128, 160, 200, 256, 320, 400, 512, 768, 1024)

@Composable
fun AutoTrimButton(
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    config: AutoTrimConfig,
    onUpdateConfig: (AutoTrimConfig) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        AutoTrimPicker(
            config = config,
            onDismissRequest = { showPicker = false },
            onUpdateConfig = onUpdateConfig,
        )
    }

    ToggleSurface(
        checked = config.isEnabled,
        onClick = { showPicker = true },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(HugeIcons.Clean, null)
            }
            if (!onlyIcon) Text(stringResource(R.string.auto_trim_title))
        }
    }
}

/**
 * 自动裁剪设置：三个滑块分别对应保留步数、最小释放体积和上下文水位。
 *
 * 任一滑块划到最左端（值为 0）整体就不触发，与 [AutoTrimConfig.isEnabled] 一致。
 */
@Composable
fun AutoTrimPicker(
    config: AutoTrimConfig,
    onDismissRequest: () -> Unit = {},
    onUpdateConfig: (AutoTrimConfig) -> Unit,
) {
    val keepIndex = keepStepsTicks.indexOfNearest(config.keepRecentSteps)
    val minFreeIndex = minFreeKbTicks.indexOfNearest((config.minFreeBytes / 1024L).toInt())
    val contextIndex = contextKTicks.indexOfNearest(config.contextTokens / 1024)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PickerHeader(
                title = stringResource(R.string.auto_trim_title),
                hint = stringResource(R.string.auto_trim_hint),
                hero = {
                    AutoTrimHero(
                        enabled = config.isEnabled,
                        modifier = Modifier.size(64.dp),
                    )
                },
            )

            TrimSlider(
                title = stringResource(R.string.auto_trim_keep_steps),
                desc = stringResource(R.string.auto_trim_keep_steps_desc),
                ticks = keepStepsTicks,
                index = keepIndex,
                label = { value ->
                    if (value == 0) stringResource(R.string.auto_trim_off)
                    else stringResource(R.string.auto_trim_keep_steps_value, value)
                },
                onSelect = { value -> onUpdateConfig(config.copy(keepRecentSteps = value)) },
            )

            TrimSlider(
                title = stringResource(R.string.auto_trim_min_free),
                desc = stringResource(R.string.auto_trim_min_free_desc),
                ticks = minFreeKbTicks,
                index = minFreeIndex,
                label = { value ->
                    if (value == 0) stringResource(R.string.auto_trim_off)
                    else (value * 1024L).formatBytes()
                },
                onSelect = { value -> onUpdateConfig(config.copy(minFreeBytes = value * 1024L)) },
            )

            TrimSlider(
                title = stringResource(R.string.auto_trim_context),
                desc = stringResource(R.string.auto_trim_context_desc),
                ticks = contextKTicks,
                index = contextIndex,
                label = { value ->
                    if (value == 0) stringResource(R.string.auto_trim_off)
                    else stringResource(R.string.auto_trim_context_value, value)
                },
                onSelect = { value -> onUpdateConfig(config.copy(contextTokens = value * 1024)) },
            )
        }
    }
}

/** 一个带档位吸附的滑块，风格与思考深度选择器一致 */
@Composable
private fun TrimSlider(
    title: String,
    desc: String,
    ticks: List<Int>,
    index: Int,
    label: @Composable (value: Int) -> String,
    onSelect: (value: Int) -> Unit,
) {
    val sliderState = remember(ticks) {
        SliderState(value = index.toFloat(), trackRange = 0f..ticks.lastIndex.toFloat(), steps = ticks.size - 2)
    }
    val interactionSource = remember { MutableInteractionSource() }

    // 外部值变化（比如切换助手）时同步滑块位置
    LaunchedEffect(index) {
        sliderState.value = index.toFloat()
    }

    val selectedTick = ticks[sliderState.value.roundToInt().coerceIn(0, ticks.lastIndex)]

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = label(selectedTick),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            state = sliderState,
            onValueChange = { sliderState.value = it },
            onValueChangeFinished = {
                val snapped = sliderState.value.roundToInt().coerceIn(0, ticks.lastIndex)
                sliderState.value = snapped.toFloat()
                onSelect(ticks[snapped])
            },
            modifier = Modifier.fillMaxWidth(),
            interactionSource = interactionSource,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    isVertical = false,
                    thumbSize = DpSize(4.dp, 32.dp),
                )
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    trackCornerSize = 12.dp,
                    modifier = Modifier.height(24.dp),
                )
            },
        )
    }
}

@Composable
private fun AutoTrimHero(enabled: Boolean, modifier: Modifier = Modifier) {
    PickerHero(
        shapes = listOf(MaterialShapes.Circle, MaterialShapes.Cookie9Sided),
        index = if (enabled) 1 else 0,
        icon = HugeIcons.Clean,
        containerColor = if (enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (enabled) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier,
    )
}

/** 找到最接近的档位下标；旧数据里的值不在档位表上时落到最近的档位 */
private fun List<Int>.indexOfNearest(value: Int): Int {
    val exact = indexOf(value)
    if (exact >= 0) return exact
    var best = 0
    var bestDistance = Int.MAX_VALUE
    forEachIndexed { i, tick ->
        val distance = abs(tick - value)
        if (distance < bestDistance) {
            best = i
            bestDistance = distance
        }
    }
    return best
}
