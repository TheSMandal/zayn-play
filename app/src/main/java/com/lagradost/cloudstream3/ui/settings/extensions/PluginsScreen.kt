package com.lagradost.cloudstream3.ui.settings.extensions

import android.app.Activity
import android.text.format.Formatter.formatShortFileSize
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Observer
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.PROVIDER_STATUS_DOWN
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.PluginWrapper
import com.lagradost.cloudstream3.ui.settings.SettingsFragmentScreen
import com.lagradost.cloudstream3.ui.settings.SettingsProvidersScreen.toStringRes
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.SubtitleHelper.getNameNextToFlagEmoji
import com.lagradost.cloudstream4.compose.MultiSelectDialog
import com.lagradost.cloudstream4.compose.Screen
import com.lagradost.cloudstream4.compose.WhiteFilterChip
import com.lagradost.cloudstream4.compose.WhiteIconButton
import com.mihon.material.AppBar
import com.mihon.material.Scaffold
import com.mihon.material.padding
import com.mihon.presentation.LocalBackPress

/**
 * Zayn Play plugins screen (Compose) — replaces the old PluginsFragment +
 * fragment_plugins.xml / repository_item.xml UI. Same behaviour, Zayn design:
 * rounded cards, red download/delete action, provider icon tiles.
 *
 * The repository to show comes from PluginsFragment2's navigation arguments,
 * set on [args] before the view is created.
 */
object PluginsScreen : Screen {

    /** Set by PluginsFragment2.onCreate from its navigation arguments. */
    var args: PluginsArgs? = null

    data class PluginsArgs(val repositoryData: RepositoryData, val isLocal: Boolean)

    @Composable
    override fun Content() {
        val currentArgs = args
        val goBack = LocalBackPress.current
        if (currentArgs == null) {
            // No repository was passed, same as the old fragment: go back.
            LaunchedEffect(Unit) { goBack?.invoke() }
            return
        }

        val context = LocalContext.current
        val activity = context as? Activity
        val viewModel: PluginsViewModel = viewModel()

        var pluginsUpdate by remember { mutableStateOf<PluginViewDataUpdate>(false to emptyList()) }
        var hasLoaded by remember { mutableStateOf(false) }
        var loadStarted by remember { mutableStateOf(false) }

        DisposableEffect(viewModel) {
            val observer = Observer<PluginViewDataUpdate> { update ->
                pluginsUpdate = update ?: (false to emptyList())
                if (loadStarted) hasLoaded = true
            }
            viewModel.filteredPlugins.observeForever(observer)
            onDispose { viewModel.filteredPlugins.removeObserver(observer) }
        }

        // Same init as the old fragment: reset filters, default the language
        // filter from the provider-language setting, then load the list.
        LaunchedEffect(Unit) {
            viewModel.tvTypes.clear()
            val providerLangs = context.getApiProviderLangSettings().toList()
            viewModel.selectedLanguages =
                if (providerLangs.contains(AllLanguagesName)) listOf()
                else mutableListOf("none") + providerLangs
            loadStarted = true
            if (currentArgs.isLocal) {
                viewModel.updatePluginListLocal()
            } else {
                viewModel.updatePluginList(context, listOf(currentArgs.repositoryData))
            }
        }

        val textState = rememberTextFieldState()
        var prevQuery by remember { mutableStateOf("") }
        LaunchedEffect(textState.text) {
            val query = textState.text.toString()
            if (query.isNotEmpty() || prevQuery.isNotEmpty()) {
                viewModel.search(query)
                prevQuery = query
            }
        }

        val listState = rememberLazyListState()
        LaunchedEffect(pluginsUpdate) {
            if (pluginsUpdate.first) listState.scrollToItem(0)
        }

        var selectedTvTypes by remember { mutableStateOf(setOf<String>()) }
        var showLangDialog by remember { mutableStateOf(false) }

        if (showLangDialog) {
            val languagesTagName = viewModel.pluginLanguages
                .map { langTag ->
                    Pair(langTag, getNameNextToFlagEmoji(langTag) ?: langTag)
                }
                .sortedBy { it.second.substringAfter("\u00a0").lowercase() }
                .toMutableList()
            // "none" is the special code for unknown/missing language, first.
            if (languagesTagName.remove(Pair("none", "none"))) {
                languagesTagName.add(0, Pair("none", stringResource(R.string.no_data)))
            }
            MultiSelectDialog(
                title = stringResource(R.string.provider_lang_settings),
                entries = languagesTagName.toMap(),
                properties = DialogProperties(usePlatformDefaultWidth = true),
                confirmText = stringResource(R.string.ok),
                confirm = { selection ->
                    viewModel.selectedLanguages = selection.toList()
                    viewModel.updateFilteredPlugins()
                    showLangDialog = false
                },
                dismissText = stringResource(R.string.cancel),
                dismiss = { showLangDialog = false },
                selectedKeys = viewModel.selectedLanguages.toSet(),
            )
        }

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                AppBar(
                    title = currentArgs.repositoryData.name,
                    navigateUp = goBack,
                    actions = {
                        if (!currentArgs.isLocal) {
                            WhiteIconButton(onClick = { showLangDialog = true }) {
                                Icon(
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    painter = painterResource(R.drawable.plugin_lang),
                                    contentDescription = stringResource(R.string.extension_language)
                                )
                            }
                        }
                        // Download-all is debug-only, same as the old fragment.
                        if (BuildConfig.DEBUG && !currentArgs.isLocal) {
                            WhiteIconButton(onClick = {
                                activity?.let {
                                    PluginsViewModel.downloadAll(
                                        it,
                                        currentArgs.repositoryData,
                                        viewModel
                                    )
                                }
                            }) {
                                Icon(
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    painter = painterResource(R.drawable.netflix_download),
                                    contentDescription = stringResource(R.string.download_all)
                                )
                            }
                        }
                    },
                    scrollBehavior = it,
                )
            },
            content = { contentPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                ) {
                    Spacer(modifier = Modifier.height(MaterialTheme.padding.small))
                    SettingsFragmentScreen.SettingsSearch(textState)

                    if (!currentArgs.isLocal) {
                        Spacer(modifier = Modifier.height(MaterialTheme.padding.small))
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(TvType.entries.toList()) { type ->
                                val selected = selectedTvTypes.contains(type.name)
                                WhiteFilterChip(
                                    selected = selected,
                                    label = stringResource(type.toStringRes()),
                                    modifier = Modifier.padding(
                                        horizontal = MaterialTheme.padding.extraSmall
                                    ),
                                    onClick = {
                                        viewModel.tvTypes.apply {
                                            if (selected) remove(type.name) else add(type.name)
                                        }
                                        selectedTvTypes =
                                            if (selected) selectedTvTypes - type.name
                                            else selectedTvTypes + type.name
                                        viewModel.updateFilteredPlugins()
                                    },
                                )
                            }
                        }
                    }

                    if (!hasLoaded) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = MaterialTheme.padding.small),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }

                    val plugins = pluginsUpdate.second
                    if (hasLoaded && plugins.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.no_plugins_found_error),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(MaterialTheme.padding.medium)
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(
                                items = plugins,
                                key = {
                                    it.pluginWrapper.plugin.internalName +
                                        it.pluginWrapper.repositoryData.url
                                }
                            ) { item ->
                                PluginRow(
                                    item = item,
                                    isLocal = currentArgs.isLocal,
                                    onAction = { wrapper ->
                                        viewModel.handlePluginAction(
                                            activity,
                                            listOf(currentArgs.repositoryData),
                                            wrapper,
                                            currentArgs.isLocal
                                        )
                                    },
                                    onDetails = { data ->
                                        val appCompatActivity =
                                            context as? AppCompatActivity ?: return@PluginRow
                                        PluginDetailsFragment(data).show(
                                            appCompatActivity.supportFragmentManager,
                                            "PluginDetails"
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            },
        )
    }

    @Composable
    private fun PluginRow(
        item: PluginViewData,
        isLocal: Boolean,
        onAction: (PluginWrapper) -> Unit,
        onDetails: (PluginViewData) -> Unit,
    ) {
        val context = LocalContext.current
        val metadata = item.pluginWrapper.plugin
        val disabled = metadata.status == PROVIDER_STATUS_DOWN
        val name = metadata.name.removeSuffix("Provider")
        val alpha = if (disabled) 0.6f else 1f
        val isNsfw = metadata.tvTypes?.contains(TvType.NSFW.name) == true

        // Gear icon only when downloaded and the loaded plugin exposes settings.
        val plugin: Plugin? = if (item.isDownloaded) {
            (PluginManager.urlPlugins[metadata.url]
                ?: PluginManager.plugins[metadata.url]) as? Plugin
        } else {
            null
        }
        val hasSettings = plugin?.openSettings != null

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.padding.small,
                    vertical = 4.dp
                )
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = !isLocal) { onDetails(item) }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Provider icon tile, red-tinted when no icon is available.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                val iconUrl = metadata.iconUrl
                    ?.replace("%size%", "128")
                    ?.replace("%exact_size%", "128")
                if (iconUrl.isNullOrBlank()) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_extension_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                } else {
                    AsyncImage(
                        model = iconUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        error = painterResource(R.drawable.ic_baseline_extension_24)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .alpha(alpha)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (disabled) {
                            stringResource(R.string.single_plugin_disabled, name)
                        } else {
                            name
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "v${metadata.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    if (isNsfw) {
                        Text(
                            text = stringResource(R.string.is_adult),
                            color = colorResource(R.color.adultColor),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }

                if (!metadata.description.isNullOrBlank()) {
                    Text(
                        text = metadata.description.html().toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                val meta = buildList {
                    if (!metadata.language.isNullOrBlank()) {
                        add(getNameNextToFlagEmoji(metadata.language) ?: metadata.language)
                    }
                    if (metadata.fileSize != null) {
                        add(formatShortFileSize(context, metadata.fileSize))
                    }
                }
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            if (hasSettings) {
                IconButton(onClick = {
                    try {
                        plugin?.openSettings?.invoke(context)
                    } catch (t: Throwable) {
                        Log.e(
                            "PluginsScreen",
                            "Failed to open $name settings: ${Log.getStackTraceString(t)}"
                        )
                    }
                }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_tune_24),
                        contentDescription = stringResource(R.string.title_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Red download / delete action, same meaning as the old row button.
            IconButton(
                onClick = { onAction(item.pluginWrapper) },
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    painter = painterResource(
                        if (item.isDownloaded) {
                            R.drawable.ic_baseline_delete_outline_24
                        } else {
                            R.drawable.netflix_download
                        }
                    ),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
