package com.lagradost.cloudstream3.ui.settings

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.SubtitleHelper.getNameNextToFlagEmoji
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream4.rememberAppSettings
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.SearchableSettings
import kotlinx.collections.immutable.persistentListOf

// Zayn Play: restored as a real screen (preview Settings > Providers).
// The @Deprecated marker is removed — this is a first-class screen again.
object SettingsProvidersScreen : SearchableSettings {
    @Composable
    override fun getTitleRes(): String = stringResource(R.string.category_providers)

    @Composable
    override fun Content() {
        ZaynSearchableContent(
            title = getTitleRes(),
            itemsProvider = { getPreferences() },
        )
    }

    fun TvType.toStringRes() = when (this) {
        TvType.TvSeries -> R.string.tv_series_singular
        TvType.Anime -> R.string.anime_singular
        TvType.OVA -> R.string.ova_singular
        TvType.AnimeMovie -> R.string.movies_singular
        TvType.Cartoon -> R.string.cartoons_singular
        TvType.Documentary -> R.string.documentaries_singular
        TvType.Movie -> R.string.movies_singular
        TvType.Torrent -> R.string.torrent_singular
        TvType.AsianDrama -> R.string.asian_drama_singular
        TvType.Live -> R.string.live_singular
        TvType.Others -> R.string.other_singular
        TvType.NSFW -> R.string.nsfw_singular
        TvType.Music -> R.string.music_singular
        TvType.AudioBook -> R.string.audio_book_singular
        TvType.CustomMedia -> R.string.custom_media_singular
        TvType.Audio -> R.string.audio_singular
        TvType.Podcast -> R.string.podcast_singular
        TvType.Video -> R.string.video_singular
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val settings = rememberAppSettings()

        val default = AllLanguagesName to stringResource(R.string.all_languages_preference)
        // Zayn Play: the 11 languages in the user's order first (not just
        // installed providers' languages) — providerLanguageTagsInOrder.
        val installedLangs = APIHolder.apis.withLock {
            APIHolder.apis.map { api -> api.lang }.distinct()
        }
        val languages = providerLanguageTagsInOrder(installedLangs)
            .filterNot { it == AllLanguagesName }

        return persistentListOf(
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
        ), Preference.PreferenceItem.MultiSelectListPreference(
            title = stringResource(R.string.preferred_media_settings),
            icon = painterResource(R.drawable.movie_edit_24px),
            iconTileColor = Color(0xFFDB2777),
            preference = settings.provider.preferredMedia,
            entries = TvType.entries.associate {
                    it.ordinal.toString() to stringResource(it.toStringRes())
                }),
            Preference.PreferenceItem.MultiSelectListPreference(
            title = stringResource(R.string.display_subbed_dubbed_settings),
            icon = painterResource(R.drawable.audio_capture_24px),
            iconTileColor = Color(0xFF059669),
            preference = settings.provider.displayDubSub,
            entries = mapOf(
                DubStatus.None.name to stringResource(R.string.none),
                DubStatus.Dubbed.name to stringResource(R.string.app_dubbed_text),
                DubStatus.Subbed.name to stringResource(R.string.app_subbed_text),
            )
        ), Preference.PreferenceItem.CustomPreference(
            // Preview scr-set-providers: "Enable NSFW on supported Extensions".
            // REAL behaviour — MainActivity reads this key on startup into
            // MainAPI.settingsForProvider.enableAdult (MainActivity.kt:1204);
            // no settings screen exposed it until now.
            title = stringResource(R.string.enable_nsfw_on_providers),
            content = {
                val context = LocalContext.current
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val key = stringResource(R.string.enable_nsfw_on_providers_key)
                var enabled by remember { mutableStateOf(prefs.getBoolean(key, false)) }
                ZaynSettingsRow(
                    title = stringResource(R.string.enable_nsfw_on_providers),
                    subtitle = stringResource(R.string.apply_on_restart),
                    icon = painterResource(R.drawable.ic_baseline_remove_red_eye_24),
                    iconTileColor = Color(0xFF7C3AED),
                    onClick = {
                        val newValue = !enabled
                        enabled = newValue
                        prefs.edit { putBoolean(key, newValue) }
                    },
                    trailing = {
                        Switch(checked = enabled, onCheckedChange = null)
                    },
                )
            }
        ), Preference.PreferenceItem.TextPreference(
            title = stringResource(R.string.test_extensions),
            subtitle = stringResource(R.string.test_extensions_summary),
            icon = painterResource(R.drawable.baseline_network_ping_24),
            iconTileColor = Color(0xFFD97706),
            onClick = {
                activity?.navigate(R.id.navigation_test_providers)
            }))
    }
}