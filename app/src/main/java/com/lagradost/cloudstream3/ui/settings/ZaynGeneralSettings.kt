// Zayn Play — General settings screen.
//
// FRESH BUILD (delete-first rebuild, v1.0.9): replaces the deleted
// SettingsGeneralScreen.kt AND the deleted old SettingsGeneral.kt fragment.
// The old XML preference screen (settings_general.xml) and the old
// fragment are gone. The functional logic that lived in the old fragment
// (CustomSite, Add/Remove site, pickDownloadPath, the 11-language list)
// was moved here first, unchanged, so nothing is lost. Rows, order,
// settings keys and handlers are identical to the old screen; only the
// rendering is new (ZaynSettingsUi) with the preview's tile colours.

package com.lagradost.cloudstream3.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.integerArrayResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.core.content.edit
import androidx.core.os.ConfigurationCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.allProviders
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.UnsafeSSL
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.databinding.AddRemoveSitesBinding
import com.lagradost.cloudstream3.databinding.AddSiteInputBinding
import com.lagradost.cloudstream3.insecureApp
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.network.initClient
import com.lagradost.cloudstream3.ui.settings.SettingsFragmentScreen.launchSafe
import com.lagradost.cloudstream3.ui.settings.SettingsProvidersScreen.toStringRes
import com.lagradost.cloudstream3.utils.BatteryOptimizationChecker.isAppRestricted
import com.lagradost.cloudstream3.utils.BatteryOptimizationChecker.showRequestIgnoreBatteryOptDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showMultiDialog
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.SubtitleHelper.fromTagToLanguageName
import com.lagradost.cloudstream3.utils.SubtitleHelper.getNameNextToFlagEmoji
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.USER_PROVIDER_API
import com.lagradost.cloudstream4.AppSettings
import com.lagradost.cloudstream4.compose.ActionDialog
import com.lagradost.cloudstream4.compose.PHONE
import com.lagradost.cloudstream4.compose.isLayout
import com.lagradost.cloudstream4.rememberAppSettings
import com.lagradost.cloudstream4.theme.CloudStreamPreviewTheme
import com.lagradost.safefile.SafeFile
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.SearchableSettings
import com.mihon.presentation.settings.collectAsState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

// Change local language settings in the app.
fun getCurrentLocale(context: Context): String {
    val conf = context.resources.configuration
    return ConfigurationCompat.getLocales(conf).get(0)?.toLanguageTag() ?: "en"
}

/**
 * List of app supported languages.
 * Language code shall be a IETF BCP 47 conformant tag.
 */
val appLanguages = arrayListOf(
    /* begin language list */
    Pair("English", "en"),
    Pair("বাংলা", "bn"),
    Pair("हिन्दी", "hi"),
    Pair("मराठी", "mr"),
    Pair("తెలుగు", "te"),
    Pair("தமிழ்", "ta"),
    Pair("ગુજરાતી", "gu"),
    Pair("اردو", "ur"),
    Pair("ಕನ್ನಡ", "kn"),
    Pair("ଓଡ଼ିଆ", "or"),
    Pair("മലയാളം", "ml"),
    /* end language list */
) // Zayn Play order: English, Bengali, Hindi first, then the other major Indian languages

fun Pair<String, String>.nameNextToFlagEmoji(): String {
    // fallback to [A][A] -> [?] question mak flag
    val flag = SubtitleHelper.getFlagFromIso(this.second) ?: "\ud83c\udde6\ud83c\udde6"

    return "$flag\u00a0${this.first}" // \u00a0 non-breaking space
}

fun Fragment.pickDownloadPath(uri: Uri?, path: String?) {
    if (uri == null) return

    val context = context ?: CloudStreamApp.context ?: return
    val visual = path ?: uri.toString()
    PreferenceManager.getDefaultSharedPreferences(context).edit {
        putString(getString(R.string.download_path_key), uri.toString())
        putString(context.getString(R.string.download_path_key_visual), visual)
    }
}

object ZaynGeneralSettings : SearchableSettings {
    @Composable
    override fun getTitleRes(): String = stringResource(R.string.category_general)

    @OptIn(ExperimentalSerializationApi::class) // JsonNames is an experimental annotation for now
    @Serializable
    data class CustomSite(
        @JsonProperty("parentClassName") @JsonAlias("parentJavaClass")
        @SerialName("parentClassName") @JsonNames("parentJavaClass")
        val parentClassName: String, // ::class.simpleName
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("url") @SerialName("url") val url: String,
        @JsonProperty("lang") @SerialName("lang") val lang: String,
    )

    fun getCurrent(): MutableList<CustomSite> {
        return getKey<Array<CustomSite>>(USER_PROVIDER_API)?.toMutableList()
            ?: mutableListOf()
    }

    fun showAdd() {
        val providers = allProviders.distinctBy { it::class }.sortedBy { it.name }
        val context = activity
        context?.showDialog(
            providers.map { "${it.name} (${it.mainUrl})" },
            -1,
            context.getString(R.string.add_site_pref),
            true,
            {}) { selection ->
            val provider = providers.getOrNull(selection) ?: return@showDialog

            val binding: AddSiteInputBinding = AddSiteInputBinding.inflate(
                LayoutInflater.from(
                    context
                ), null, false
            )

            val builder =
                AlertDialog.Builder(context, R.style.AlertDialogCustom)
                    .setView(binding.root)

            val dialog = builder.create()
            dialog.show()

            binding.text2.text = provider.name
            binding.applyBtt.setOnClickListener {
                val name = binding.siteNameInput.text?.toString()
                val url = binding.siteUrlInput.text?.toString()
                val lang = binding.siteLangInput.text?.toString()
                val realLang = if (lang.isNullOrBlank()) provider.lang else lang
                val simpleName = provider::class.simpleName
                if (url.isNullOrBlank() || name.isNullOrBlank() || simpleName == null) {
                    showToast(R.string.error_invalid_data, Toast.LENGTH_SHORT)
                    return@setOnClickListener
                }

                val current = getCurrent()
                val newSite = CustomSite(simpleName, name, url, realLang)
                current.add(newSite)
                setKey(USER_PROVIDER_API, current.toTypedArray())
                // reload apis
                MainActivity.afterPluginsLoadedEvent.invoke(false)

                dialog.dismissSafe(activity)
            }
            binding.cancelBtt.setOnClickListener {
                dialog.dismissSafe(activity)
            }
        }
    }

    fun showDelete() {
        val current = getCurrent()
        val context = activity
        context?.showMultiDialog(
            current.map { it.name },
            listOf(),
            context.getString(R.string.remove_site_pref),
            {}) { indexes ->
            current.removeAll(indexes.map { current[it] })
            setKey(USER_PROVIDER_API, current.toTypedArray())
        }
    }

    fun showAddOrDelete() {
        val context = activity

        val binding: AddRemoveSitesBinding = AddRemoveSitesBinding.inflate(
            LayoutInflater.from(
                context
            ), null, false
        )
        val builder =
            AlertDialog.Builder(context ?: return, R.style.AlertDialogCustom)
                .setView(binding.root)

        val dialog = builder.create()
        dialog.show()

        binding.addSite.setOnClickListener {
            showAdd()
            dialog.dismissSafe(activity)
        }
        binding.removeSite.setOnClickListener {
            showDelete()
            dialog.dismissSafe(activity)
        }
    }

    @Composable
    override fun Content() {
        ZaynSearchableContent(
            title = getTitleRes(),
            itemsProvider = { getPreferences() },
        )
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val settings = rememberAppSettings()

        // TODO Refactor entirely to use a different file path selector ect like QuickNovel
        val selectFileSelector =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                // It lies, it can be null if file manager quits.
                if (uri == null) return@rememberLauncherForActivityResult
                val context = CloudStreamApp.context ?: return@rememberLauncherForActivityResult

                try {
                    val settings = AppSettings(context)
                    // RW perms for the path
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                    context.contentResolver.takePersistableUriPermission(uri, flags)

                    val filePath = SafeFile.fromUri(context, uri)?.filePath()
                    println("Selected URI path: $uri - Full path: $filePath")

                    // store the actual URI instead of the path due to permissions.
                    // filePath should only be used for cosmetic purposes.
                    val visual = filePath ?: uri.toString()
                    settings.general.downloadPath.set(uri.toString())
                    settings.general.downloadPathVisual.set(visual)
                } catch (t: Throwable) {
                    logError(t)
                }
            }

        val bananas by settings.general.bananas.collectAsState()
        val downloadPathVisual by settings.general.downloadPathVisual.collectAsState()

        var isBatteryShown by remember { mutableStateOf(false) }
        val context = LocalContext.current

        if (isBatteryShown) {
            ActionDialog(
                icon = painterResource(R.drawable.battery_alert_24px),
                title = stringResource(R.string.battery_dialog_title),
                text = stringResource(R.string.battery_dialog_message),
                confirmText = stringResource(R.string.ok),
                dismissText = stringResource(R.string.cancel),
                dismiss = {
                    isBatteryShown = false
                    settings.general.batterOptimization.set(false)
                },
                confirm = {
                    isBatteryShown = false
                    // The og impl never modified it to true?
                    // settings.general.batterOptimization.set(true)
                    context.showRequestIgnoreBatteryOptDialog()
                }
            )
        }

        val default = AllLanguagesName to stringResource(R.string.all_languages_preference)
        val languages = APIHolder.apis.withLock {
            APIHolder.apis.map { api -> api.lang }.distinct()
        }.sortedBy { fromTagToLanguageName(it) ?: it }

        return persistentListOf(
            Preference.PreferenceGroup(title = stringResource(R.string.extension_language), preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = settings.general.locale,
                    entries = appLanguages.associate { (name, code) -> (code to (name to code).nameNextToFlagEmoji()) },
                    title = stringResource(R.string.app_language),
                    icon = painterResource(R.drawable.language_korean_latin_24px),
                    iconTileColor = Color(0xFF2563EB),
                    onValueChanged = { value ->
                        settings.general.locale.set(value)
                        activity?.recreate()
                        return@ListPreference false
                    },
                    subtitleProvider = { v, e -> e[v] ?: getCurrentLocale(LocalContext.current) }
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    title = stringResource(R.string.provider_lang_settings),
                    icon = painterResource(R.drawable.plugin_lang),
                    iconTileColor = Color(0xFF2563EB),
                    entries = mapOf(default) + languages.associateWith { lang ->
                        (getNameNextToFlagEmoji(
                            lang
                        ) ?: lang)
                    },
                    preference = settings.provider.extensionLanguages
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    title = stringResource(R.string.preferred_media_settings),
                    icon = painterResource(R.drawable.movie_edit_24px),
                    iconTileColor = Color(0xFFDB2777),
                    preference = settings.provider.preferredMedia,
                    entries = TvType.entries.associate {
                        it.ordinal.toString() to stringResource(it.toStringRes())
                    }
                        // Ok this looks strange af, but we do this to avoid double movie
                        .toPersistentMap().remove(TvType.AnimeMovie.ordinal.toString()), onValueChanged = { diff ->
                        if (diff.contains(TvType.Movie.ordinal.toString())) {
                            settings.provider.preferredMedia.set(diff + TvType.AnimeMovie.ordinal.toString())
                        } else {
                            settings.provider.preferredMedia.set(diff - TvType.AnimeMovie.ordinal.toString())
                        }
                        return@MultiSelectListPreference false
                    }),
            )),

            Preference.PreferenceGroup(
                title = stringResource(R.string.title_downloads),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        icon = painterResource(R.drawable.netflix_download),
                        iconTileColor = Color(0xFF059669),
                        subtitle = downloadPathVisual,
                        title = stringResource(R.string.download_path_pref),
                        onClick = {
                            // This is not a ListPreference because the old selection system is
                            // broken af. This needs to be refactored to QuickNovels download path
                            // system.
                            selectFileSelector.launchSafe(Uri.EMPTY)
                        },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        icon = painterResource(R.drawable.arrow_or_edge_24px),
                        iconTileColor = Color(0xFF0891B2),
                        preference = settings.general.parallelDownloads,
                        valueRange = 1..10,
                        title = stringResource(R.string.parallel_downloads),
                        subtitle = stringResource(R.string.download_parallel_settings_des),
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        icon = painterResource(R.drawable.arrow_and_edge_24px),
                        iconTileColor = Color(0xFF7C3AED),
                        preference = settings.general.concurrentConnections,
                        valueRange = 1..10,
                        title = stringResource(R.string.concurrent_connections),
                        subtitle = stringResource(R.string.concurrent_connections_settings_des),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.battery_dialog_title),
                        icon = painterResource(R.drawable.battery_alert_24px),
                        iconTileColor = Color(0xFF16A34A),
                        enabled = isLayout(PHONE),
                        onClick = {
                            if (isAppRestricted(context)) {
                                isBatteryShown = true
                            } else {
                                showToast(R.string.app_unrestricted_toast)
                            }
                        }
                    ),
                )
            ),

            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_bypass),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.add_site_pref),
                        subtitle = stringResource(R.string.add_site_summary),
                        icon = painterResource(R.drawable.copy_all_24px),
                        iconTileColor = Color(0xFFDB2777),
                        onClick = {
                            // TODO refactor into compose
                            if (getCurrent().isEmpty()) {
                                showAdd()
                            } else {
                                showAddOrDelete()
                            }
                        }
                    ),
                    Preference.PreferenceItem.ListPreference(
                        title = stringResource(R.string.dns_pref),
                        subtitle = stringResource(R.string.dns_pref_summary),
                        icon = painterResource(R.drawable.dns_24px),
                        iconTileColor = Color(0xFF475569),
                        preference = settings.general.dns,
                        entries = integerArrayResource(R.array.dns_pref_values).zip(
                            stringArrayResource(R.array.dns_pref)
                        ).toMap(),
                        onValueChanged = { newValue ->
                            settings.general.dns.set(newValue) // Must set the value before initClient is called
                            (CloudStreamApp.context)?.let { ctx ->
                                app.initClient(ctx, ignoreSSL = false)
                                @OptIn(UnsafeSSL::class)
                                insecureApp.initClient(ctx, ignoreSSL = true)
                            }
                            return@ListPreference true
                        },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        title = stringResource(R.string.jsdelivr_proxy),
                        subtitle = stringResource(R.string.jsdelivr_proxy_summary),
                        icon = painterResource(R.drawable.wifi_proxy_24px),
                        iconTileColor = Color(0xFF334155),
                        preference = settings.general.jsdelivrProxy
                    )
                )
            ),

            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_links),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.website),
                        subtitle = "https://zaynplay.netlify.app/",
                        icon = painterResource(R.drawable.ic_baseline_public_24),
                        iconTileColor = Color(0xFF334155),
                        onClick = {
                            CloudStreamApp.openBrowser("https://zaynplay.netlify.app/")
                        }
                    ),
                )
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(R.string.benene),
                subtitle = if (bananas == 0) {
                    stringResource(R.string.benene_count_text_none)
                } else {
                    stringResource(R.string.benene_count_text, bananas)
                },
                onClick = {
                    settings.general.bananas.set(bananas + 1)
                },
                icon = painterResource(R.drawable.benene),
                iconTileColor = Color(0xFFE1B101),
            ),
            Preference.PreferenceItem.InfoPreference(title = stringResource(R.string.legal_notice_text)),
        )
    }
}


@PreviewLightDark
@Composable
private fun ZaynSettingGeneralPreview() {
    CloudStreamPreviewTheme {
        ZaynGeneralSettings.Content()
    }
}
