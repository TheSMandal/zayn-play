// Zayn Play settings UI kit.
//
// FRESH BUILD (delete-first rebuild, v1.0.9): the inner Settings screens
// (General, Player, Layout, Updates, Accounts) are rendered ONLY by the
// composables in this file. The old Mihon preference widgets
// (Base/Text/Switch/List/MultiSelect/Color/EditTextPreferenceWidget,
// PreferenceScreen, PreferenceItem) are NOT used by these screens any more.
// The Preference *model* (rows, keys, handlers, search index) is unchanged
// behaviour — only the rendering is new, matching the approved preview:
// coloured 34dp icon tiles, 52dp rows with a bottom divider, uppercase
// #ff8a70 group headers, red theme switches/sliders.

package com.lagradost.cloudstream3.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component1
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component2
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.compose.ui.window.DialogProperties
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream4.compose.ColorDialog
import com.lagradost.cloudstream4.compose.MultiSelectDialog
import com.lagradost.cloudstream4.compose.SingleSelectDialog
import com.lagradost.cloudstream4.compose.focusOutline
import com.mihon.material.AppBar
import com.mihon.material.Scaffold
import com.mihon.material.Slider
import com.mihon.presentation.LocalBackPress
import com.mihon.presentation.secondaryItemAlpha
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.SearchableSettings
import com.mihon.presentation.settings.collectAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

// Preview tokens.
private val ZaynGroupTitleColor = Color(0xFFFF8A70)
private val ZaynTileCorner = RoundedCornerShape(10.dp)
private val ZaynRowMinHeight = 52.dp
private val ZaynRowHorizontalPadding = 16.dp
private val ZaynRowTitleSize = 14.5.sp

/**
 * One-line Content() for the fresh inner Settings screens: back handling
 * plus the Zayn scaffold. Each screen passes its own title and its
 * getPreferences() list (which also feeds settings search).
 */
@Composable
fun ZaynSearchableContent(
    title: String,
    // Zayn Play: top-bar actions (e.g. the subtitles screens' undo/reset
    // buttons). Same slot the old Mihon PreferenceScaffold exposed; the five
    // inner Settings screens leave it empty.
    actions: @Composable RowScope.() -> Unit = {},
    itemsProvider: @Composable () -> List<Preference>,
) {
    val handleBack = LocalBackPress.current
    ZaynSettingsScaffold(
        title = title,
        actions = actions,
        onBackPressed = if (handleBack != null) handleBack::invoke else null,
        itemsProvider = itemsProvider,
    )
}

/**
 * Settings-search result row: the same Zayn row as the settings lists.
 * Search results carry no icon, so no tile; tap navigates to the setting.
 */
@Composable
fun ZaynSearchResultRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ZaynRow(
        title = title,
        subtitle = subtitle,
        icon = null,
        iconTileColor = null,
        highlighted = false,
        onClick = onClick,
        trailing = null,
    )
}

@Composable
fun ZaynSettingsScaffold(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    onBackPressed: (() -> Unit)? = null,
    itemsProvider: @Composable () -> List<Preference>,
) {
    val (top, bottom) = remember { FocusRequester.createRefs() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // Zayn Play glass: let the app gradient background show through.
        containerColor = Color.Transparent,
        topBar = {
            AppBar(
                modifier = Modifier.focusRequester(top).focusProperties {
                    down = bottom
                },
                title = title,
                navigateUp = onBackPressed,
                actions = actions,
                scrollBehavior = it,
            )
        },
        content = { contentPadding ->
            ZaynPreferenceScreen(
                modifier = Modifier.focusRequester(bottom).focusProperties {
                    up = top
                },
                items = itemsProvider(),
                contentPadding = contentPadding,
            )
        },
    )
}

@Composable
fun ZaynPreferenceScreen(
    items: List<Preference>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state = rememberLazyListState()
    val highlightKey = SearchableSettings.highlightKey
    if (highlightKey != null) {
        LaunchedEffect(Unit) {
            val i = items.findHighlightedIndex(highlightKey)
            if (i >= 0) {
                delay(0.5.seconds)
                state.animateScrollToItem(i)
            }
            SearchableSettings.highlightKey = null
        }
    }

    LazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
    ) {
        items.fastForEachIndexed { i, preference ->
            when (preference) {
                is Preference.PreferenceGroup -> {
                    if (!preference.enabled) return@fastForEachIndexed

                    item {
                        ZaynGroupHeader(title = preference.title)
                    }
                    item {
                        ZaynGroupPanel {
                            preference.preferenceItems.forEachIndexed { idx, item ->
                                ZaynPreferenceItem(
                                    item = item,
                                    highlightKey = highlightKey,
                                    showDivider = idx < preference.preferenceItems.lastIndex,
                                )
                            }
                        }
                    }
                    item {
                        if (i < items.lastIndex) {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }

                is Preference.PreferenceItem<*, *> -> item {
                    ZaynGroupPanel {
                        ZaynPreferenceItem(
                            item = preference,
                            highlightKey = highlightKey,
                            showDivider = false,
                        )
                    }
                }
            }
        }
    }
}

private fun List<Preference>.findHighlightedIndex(highlightKey: String): Int {
    return flatMap {
        if (it is Preference.PreferenceGroup) {
            buildList<String?> {
                add(null) // Header
                addAll(it.preferenceItems.map { groupItem -> groupItem.title })
                add(null) // Spacer
            }
        } else {
            listOf(it.title)
        }
    }.indexOfFirst { it == highlightKey }
}

/** Preview .group-title: uppercase, 12sp bold, letter-spaced, #ff8a70. */
@Composable
private fun ZaynGroupHeader(title: String) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 8.dp),
    ) {
        Text(
            text = title.uppercase(),
            color = ZaynGroupTitleColor,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 12.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            letterSpacing = 0.84.sp,
        )
    }
}

/** Search-result highlight: the row flashes, then settles (as before). */
@Composable
private fun Modifier.zaynHighlightBackground(highlighted: Boolean): Modifier {
    var highlightFlag by remember { mutableStateOf(false) }
    LaunchedEffect(highlighted) {
        if (highlighted) {
            highlightFlag = true
            delay(3.seconds)
            highlightFlag = false
        } else {
            highlightFlag = false
        }
    }
    val highlight by animateColorAsState(
        targetValue = if (highlightFlag) {
            MaterialTheme.colorScheme.onBackground.copy(alpha = .12f)
        } else {
            Color.Transparent
        },
        animationSpec = if (highlightFlag) {
            repeatable(
                iterations = 5,
                animation = tween(durationMillis = 200),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(
                    offsetMillis = 600,
                    offsetType = StartOffsetType.Delay,
                ),
            )
        } else {
            tween(200)
        },
        label = "zayn-highlight",
    )
    return this.background(color = highlight)
}

/** Preview .mi tile: 34dp rounded tile, coloured bg, white 20dp icon. */
@Composable
private fun ZaynIconTile(
    icon: Painter?,
    iconTileColor: Color?,
) {
    if (icon == null) return
    Box(
        modifier = Modifier.padding(end = 13.dp),
    ) {
        if (iconTileColor != null) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(ZaynTileCorner)
                    .background(iconTileColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = icon,
                    modifier = Modifier.size(20.dp),
                    tint = Color.White,
                    contentDescription = null,
                )
            }
        } else {
            Icon(
                painter = icon,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onBackground,
                contentDescription = null,
            )
        }
    }
}

/**
 * Preview .mrow: full-width row, min-height 52dp, coloured icon tile,
 * title 14.5sp + subtitle, trailing widget on the right, 1px divider
 * below every row.
 */
@Composable
private fun ZaynRow(
    title: String,
    subtitle: String?,
    icon: Painter?,
    iconTileColor: Color?,
    highlighted: Boolean,
    onClick: (() -> Unit)?,
    trailing: @Composable (() -> Unit)?,
    showDivider: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .zaynHighlightBackground(highlighted)
                .focusOutline()
                .sizeIn(minHeight = ZaynRowMinHeight)
                .then(
                    if (onClick != null) Modifier.clickable { onClick() }
                    else Modifier
                )
                .padding(horizontal = ZaynRowHorizontalPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZaynIconTile(icon = icon, iconTileColor = iconTileColor)
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = title,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2,
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = ZaynRowTitleSize,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.secondaryItemAlpha(),
                        maxLines = 10,
                    )
                }
            }
            if (trailing != null) {
                Box(
                    modifier = Modifier.padding(start = 16.dp),
                    content = { trailing() },
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/**
 * Preview .menu-list: one rounded panel per group — 16dp radius, panel
 * background, 1px outline border, 14dp side margin.
 */
@Composable
private fun ZaynGroupPanel(
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * Public wrapper so CustomPreference rows (e.g. the Smart Streams rows in
 * ZaynPlayerSettings) render exactly like the built-in rows.
 */
@Composable
fun ZaynSettingsRow(
    title: String,
    subtitle: String?,
    icon: Painter?,
    iconTileColor: Color?,
    onClick: (() -> Unit)?,
    trailing: @Composable (() -> Unit)?,
    showDivider: Boolean = true,
) {
    ZaynRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconTileColor = iconTileColor,
        highlighted = false,
        onClick = onClick,
        trailing = trailing,
        showDivider = showDivider,
    )
}

@Composable
private fun ZaynPreferenceItem(
    item: Preference.PreferenceItem<*, *>,
    highlightKey: String?,
    showDivider: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val highlighted = item.title == highlightKey
    AnimatedVisibility(
        visible = item.enabled,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        when (item) {
            is Preference.PreferenceItem.SwitchPreference -> {
                val value by item.preference.collectAsState()
                ZaynRow(
                    title = item.title,
                    subtitle = item.subtitle,
                    icon = item.icon,
                    iconTileColor = item.iconTileColor,
                    highlighted = highlighted,
                    showDivider = showDivider,
                    onClick = {
                        scope.launch {
                            val newValue = !value
                            if (item.onValueChanged(newValue)) {
                                item.preference.set(newValue)
                            }
                        }
                    },
                    trailing = {
                        Switch(
                            checked = value,
                            onCheckedChange = null,
                        )
                    },
                )
            }

            is Preference.PreferenceItem.ColorPreference -> {
                val color = item.preference.collectAsState()
                val derivedColor = Color(color.value)
                var isDialogShown by remember { mutableStateOf(false) }
                ZaynRow(
                    title = item.title,
                    subtitle = item.subtitle,
                    icon = item.icon,
                    iconTileColor = item.iconTileColor,
                    highlighted = highlighted,
                    showDivider = showDivider,
                    onClick = { isDialogShown = true },
                    trailing = {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape,
                                )
                                .background(derivedColor, CircleShape),
                        )
                    },
                )
                if (isDialogShown) {
                    ColorDialog(
                        title = item.title,
                        color = derivedColor,
                        dismiss = { isDialogShown = false },
                        confirm = { newValue ->
                            isDialogShown = false
                            scope.launch {
                                if (item.onValueChanged(newValue.toArgb())) {
                                    item.preference.set(newValue.toArgb())
                                }
                            }
                        },
                    )
                }
            }

            is Preference.PreferenceItem.ListPreference<*> -> {
                @Suppress("UNCHECKED_CAST")
                val listItem = item as Preference.PreferenceItem.ListPreference<Any?>
                val value by listItem.preference.collectAsState()
                var isDialogShown by remember { mutableStateOf(false) }
                ZaynRow(
                    title = item.title,
                    subtitle = listItem.subtitleProvider(value, listItem.entries),
                    icon = item.icon,
                    iconTileColor = item.iconTileColor,
                    highlighted = highlighted,
                    showDivider = showDivider,
                    onClick = { isDialogShown = true },
                    trailing = null,
                )
                if (isDialogShown) {
                    SingleSelectDialog(
                        dismiss = { isDialogShown = false },
                        title = item.title,
                        entries = listItem.entries,
                        selectedKey = value,
                        confirm = { key ->
                            scope.launch {
                                if (listItem.onValueChanged(key)) {
                                    listItem.preference.set(key)
                                }
                            }
                            isDialogShown = false
                        },
                        confirmText = stringResource(R.string.ok),
                        dismissText = stringResource(R.string.cancel),
                        iconProvider = listItem.iconProvider,
                    )
                }
            }

            is Preference.PreferenceItem.MultiSelectListPreference<*> -> {
                @Suppress("UNCHECKED_CAST")
                val multiItem = item as Preference.PreferenceItem.MultiSelectListPreference<Any?>
                val values by multiItem.preference.collectAsState()
                var isDialogShown by remember { mutableStateOf(false) }
                ZaynRow(
                    title = item.title,
                    subtitle = multiItem.subtitleProvider(values, multiItem.entries),
                    icon = item.icon,
                    iconTileColor = item.iconTileColor,
                    highlighted = highlighted,
                    showDivider = showDivider,
                    onClick = { isDialogShown = true },
                    trailing = null,
                )
                if (isDialogShown) {
                    MultiSelectDialog(
                        title = item.title,
                        entries = multiItem.entries,
                        properties = DialogProperties(
                            usePlatformDefaultWidth = true,
                        ),
                        confirmText = stringResource(R.string.ok),
                        confirm = { selection ->
                            scope.launch {
                                if (multiItem.onValueChanged(selection)) {
                                    multiItem.preference.set(selection)
                                }
                            }
                            isDialogShown = false
                        },
                        dismissText = stringResource(R.string.cancel),
                        dismiss = {
                            isDialogShown = false
                        },
                        selectedKeys = values,
                    )
                }
            }

            is Preference.PreferenceItem.TextPreference -> {
                ZaynRow(
                    title = item.title,
                    subtitle = item.subtitle,
                    icon = item.icon,
                    iconTileColor = item.iconTileColor,
                    highlighted = highlighted,
                    showDivider = showDivider,
                    onClick = item.onClick,
                    trailing = item.widget,
                )
            }

            is Preference.PreferenceItem.EditTextPreference -> {
                val values by item.preference.collectAsState()
                var isDialogShown by remember { mutableStateOf(false) }
                ZaynRow(
                    title = item.title,
                    subtitle = item.subtitle?.format(values),
                    icon = item.icon,
                    iconTileColor = item.iconTileColor,
                    highlighted = highlighted,
                    showDivider = showDivider,
                    onClick = { isDialogShown = true },
                    trailing = null,
                )
                if (isDialogShown) {
                    val onDismissRequest = { isDialogShown = false }
                    var textFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
                        mutableStateOf(TextFieldValue(values))
                    }
                    AlertDialog(
                        onDismissRequest = onDismissRequest,
                        title = { Text(text = item.title) },
                        text = {
                            OutlinedTextField(
                                value = textFieldValue,
                                onValueChange = { textFieldValue = it },
                                isError = textFieldValue.text.isBlank(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        properties = DialogProperties(
                            usePlatformDefaultWidth = true,
                        ),
                        confirmButton = {
                            TextButton(
                                enabled = textFieldValue.text != values &&
                                    textFieldValue.text.isNotBlank(),
                                onClick = {
                                    scope.launch {
                                        if (item.onValueChanged(textFieldValue.text)) {
                                            item.preference.set(textFieldValue.text)
                                            onDismissRequest()
                                        }
                                    }
                                },
                            ) {
                                Text(text = stringResource(R.string.ok))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = onDismissRequest) {
                                Text(text = stringResource(R.string.cancel))
                            }
                        },
                    )
                }
            }

            is Preference.PreferenceItem.InfoPreference -> {
                Column(
                    modifier = Modifier
                        .padding(
                            horizontal = ZaynRowHorizontalPadding,
                            vertical = 12.dp,
                        )
                        .secondaryItemAlpha(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            is Preference.PreferenceItem.CustomPreference -> {
                item.content()
            }

            is Preference.PreferenceItem.SliderPreference -> {
                val state by item.preference.collectAsState()
                val haptic = LocalHapticFeedback.current
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zaynHighlightBackground(highlighted)
                        .focusOutline()
                        .padding(
                            horizontal = ZaynRowHorizontalPadding,
                            vertical = 10.dp,
                        ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZaynIconTile(icon = item.icon, iconTileColor = item.iconTileColor)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 2,
                                style = MaterialTheme.typography.titleLarge,
                                fontSize = ZaynRowTitleSize,
                            )
                            val rowSubtitle = item.subtitle
                            if (!rowSubtitle.isNullOrBlank()) {
                                Text(
                                    text = rowSubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.secondaryItemAlpha(),
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .padding(start = 16.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                    RoundedCornerShape(99.dp),
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = item.valueString.takeUnless { it.isNullOrEmpty() }
                                    ?: state.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Slider(
                        value = state,
                        onValueChange = { newValue ->
                            if (newValue == state) return@Slider
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            scope.launch {
                                if (item.onValueChanged(newValue)) {
                                    item.preference.set(newValue)
                                }
                            }
                        },
                        valueRange = item.valueRange,
                        steps = item.steps,
                    )
                }
                if (showDivider) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
