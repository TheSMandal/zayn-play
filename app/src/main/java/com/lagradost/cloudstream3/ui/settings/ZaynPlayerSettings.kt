// Zayn Play — Player settings screen.
//
// FRESH BUILD (delete-first rebuild, v1.0.9): replaces the deleted
// SettingsPlayerScreen.kt AND the deleted old SettingsPlayer.kt fragment.
// The old XML preference screen (settings_player.xml) and the old
// fragment are gone. Rows, order, settings keys and handlers are identical
// to the old screen; only the rendering is new (ZaynSettingsUi) with the
// preview's tile colours.

package com.lagradost.cloudstream3.ui.settings

import android.text.format.Formatter.formatShortFileSize
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.core.content.edit
import com.lagradost.cloudstream3.ui.player.ZaynSmartStreams
import com.lagradost.cloudstream4.compose.SingleSelectDialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.integerArrayResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.ui.player.source_priority.QualityProfileDialog
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.getFolderSize
import com.lagradost.cloudstream3.ui.subtitles.ChromecastSubtitlesFragment
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream4.compose.EMULATOR
import com.lagradost.cloudstream4.compose.PHONE
import com.lagradost.cloudstream4.compose.TV
import com.lagradost.cloudstream4.compose.isLayout
import com.lagradost.cloudstream4.rememberAppSettings
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.SearchableSettings
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.reflect.jvm.jvmName

object ZaynPlayerSettings : SearchableSettings {
    @Composable
    override fun getTitleRes(): String = stringResource(R.string.category_player)

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

        val context = LocalContext.current
        // Zayn Smart-Streams state (real SharedPreferences keys, see
        // ZaynSmartStreams). Hoisted here so the External-player row can
        // appear/disappear when the Player choice changes.
        val zaynPrefs = remember { ZaynSmartStreams.prefs(context) }
        var playerChoice by remember { mutableStateOf(ZaynSmartStreams.playerChoice(zaynPrefs)) }
        var externalPackage by remember { mutableStateOf(ZaynSmartStreams.externalPlayerPackage(zaynPrefs)) }
        val defaultPlayerName = stringResource(R.string.player_settings_play_in_app)
        val players = remember(context) {
            mapOf("" to defaultPlayerName) + VideoClickActionHolder.getPlayers()
                .associate { player ->
                    player.uniqueId() to (player.name.asStringNull(context)
                        ?: player::class.simpleName ?: player::class.jvmName)
                }
        }

        var cacheSize by remember { mutableLongStateOf(0L) }
        var cacheCleared by remember { mutableIntStateOf(0) }
        val cacheDir = LocalContext.current.cacheDir
        LaunchedEffect(cacheCleared) {
            withContext(Dispatchers.IO) {
                cacheSize = getFolderSize(cacheDir)
            }
        }

        return persistentListOf(
            // Preview scr-set-player order: Update watch progress +
            // Preferred video player ungrouped at the top, then Playback &
            // Streams, Layout, Subtitles, Player features, Gestures, Cache,
            // Android TV.
            Preference.PreferenceItem.SwitchPreference(
                preference = settings.player.episodeSync,
                title = stringResource(R.string.episode_sync_settings),
                subtitle = stringResource(R.string.episode_sync_settings_des),
                icon = painterResource(R.drawable.autorenew_24px),
                iconTileColor = Color(0xFF2563EB),
            ),
            Preference.PreferenceItem.ListPreference(
                title = stringResource(R.string.player_pref),
                icon = painterResource(R.drawable.play_arrow_24px),
                iconTileColor = Color(0xFFE8261C),
                preference = settings.player.defaultPlayer,
                entries = players,
            ),
            Preference.PreferenceGroup(
                title = stringResource(R.string.zayn_smart_streams_category),
                preferenceItems = buildList {
                    add(Preference.PreferenceItem.CustomPreference(
                        title = stringResource(R.string.zayn_stream_mode_title),
                        content = {
                            ZaynSmartStreamListRow(
                                title = stringResource(R.string.zayn_stream_mode_title),
                                initialValue = ZaynSmartStreams.streamMode(zaynPrefs),
                                entries = stringArrayResource(R.array.zayn_stream_mode_values).zip(
                                    stringArrayResource(R.array.zayn_stream_mode_names)
                                ).toMap(),
                                icon = painterResource(R.drawable.play_arrow_24px),
                                iconTileColor = Color(0xFF2563EB),
                                onValueChanged = { newValue ->
                                    zaynPrefs.edit { putString(ZaynSmartStreams.KEY_STREAM_MODE, newValue) }
                                },
                            )
                        }
                    ))
                    add(Preference.PreferenceItem.CustomPreference(
                        title = stringResource(R.string.zayn_pref_resolution_title),
                        content = {
                            ZaynSmartStreamListRow(
                                title = stringResource(R.string.zayn_pref_resolution_title),
                                initialValue = zaynPrefs.getString(ZaynSmartStreams.KEY_PREF_RESOLUTION, "auto") ?: "auto",
                                entries = stringArrayResource(R.array.zayn_pref_resolution_values).zip(
                                    stringArrayResource(R.array.zayn_pref_resolution_names)
                                ).toMap(),
                                icon = painterResource(R.drawable.high_res_24px),
                                iconTileColor = Color(0xFF7C3AED),
                                onValueChanged = { newValue ->
                                    zaynPrefs.edit { putString(ZaynSmartStreams.KEY_PREF_RESOLUTION, newValue) }
                                },
                            )
                        }
                    ))
                    add(Preference.PreferenceItem.CustomPreference(
                        title = stringResource(R.string.zayn_audio_primary_title),
                        content = {
                            ZaynSmartStreamListRow(
                                title = stringResource(R.string.zayn_audio_primary_title),
                                initialValue = ZaynSmartStreams.audioPrimary(zaynPrefs),
                                entries = appLanguages.associate { (name, code) ->
                                    code to (name to code).nameNextToFlagEmoji()
                                },
                                icon = painterResource(R.drawable.audio_capture_24px),
                                iconTileColor = Color(0xFF059669),
                                onValueChanged = { newValue ->
                                    zaynPrefs.edit { putString(ZaynSmartStreams.KEY_AUDIO_PRIMARY, newValue) }
                                },
                            )
                        }
                    ))
                    add(Preference.PreferenceItem.CustomPreference(
                        title = stringResource(R.string.zayn_audio_secondary_title),
                        content = {
                            ZaynSmartStreamListRow(
                                title = stringResource(R.string.zayn_audio_secondary_title),
                                initialValue = ZaynSmartStreams.audioSecondary(zaynPrefs),
                                entries = mapOf("" to stringResource(R.string.none)) +
                                    appLanguages.associate { (name, code) ->
                                        code to (name to code).nameNextToFlagEmoji()
                                    },
                                icon = painterResource(R.drawable.subtitles_gear_24px),
                                iconTileColor = Color(0xFF0891B2),
                                onValueChanged = { newValue ->
                                    zaynPrefs.edit { putString(ZaynSmartStreams.KEY_AUDIO_SECONDARY, newValue) }
                                },
                            )
                        }
                    ))
                    add(Preference.PreferenceItem.CustomPreference(
                        title = stringResource(R.string.zayn_player_choice_title),
                        content = {
                            ZaynSmartStreamListRow(
                                title = stringResource(R.string.zayn_player_choice_title),
                                initialValue = playerChoice,
                                entries = stringArrayResource(R.array.zayn_player_choice_values).zip(
                                    stringArrayResource(R.array.zayn_player_choice_names)
                                ).toMap(),
                                icon = painterResource(R.drawable.label_24px),
                                iconTileColor = Color(0xFFE8261C),
                                onValueChanged = { newValue ->
                                    ZaynSmartStreams.applyPlayerChoice(context, newValue, externalPackage)
                                    playerChoice = newValue
                                },
                            )
                        }
                    ))
                    if (playerChoice == "external") {
                        add(Preference.PreferenceItem.CustomPreference(
                            title = stringResource(R.string.zayn_external_player_title),
                            content = {
                                ZaynSmartStreamListRow(
                                    title = stringResource(R.string.zayn_external_player_title),
                                    initialValue = externalPackage,
                                    entries = ZaynSmartStreams.EXTERNAL_PLAYER_OPTIONS.associate { (name, pkg) ->
                                        pkg to name
                                    },
                                    icon = painterResource(R.drawable.movie_info_24px),
                                    iconTileColor = Color(0xFFD97706),
                                    onValueChanged = { newValue ->
                                        ZaynSmartStreams.applyPlayerChoice(context, "external", newValue)
                                        externalPackage = newValue
                                    },
                                )
                            }
                        ))
                    }
                    add(Preference.PreferenceItem.CustomPreference(
                        title = stringResource(R.string.zayn_stream_timeout_title),
                        content = {
                            ZaynSmartStreamListRow(
                                title = stringResource(R.string.zayn_stream_timeout_title),
                                initialValue = zaynPrefs.getString(ZaynSmartStreams.KEY_STREAM_TIMEOUT, "10") ?: "10",
                                entries = stringArrayResource(R.array.zayn_stream_timeout_values).zip(
                                    stringArrayResource(R.array.zayn_stream_timeout_names)
                                ).toMap(),
                                icon = painterResource(R.drawable.history_toggle_off_24px),
                                iconTileColor = Color(0xFF475569),
                                onValueChanged = { newValue ->
                                    zaynPrefs.edit { putString(ZaynSmartStreams.KEY_STREAM_TIMEOUT, newValue) }
                                },
                            )
                        }
                    ))
                    add(Preference.PreferenceItem.CustomPreference(
                        title = stringResource(R.string.zayn_reuse_last_link_title),
                        content = {
                            var reuse by remember { mutableStateOf(ZaynSmartStreams.reuseLastLink(zaynPrefs)) }
                            ZaynSettingsRow(
                                title = stringResource(R.string.zayn_reuse_last_link_title),
                                subtitle = stringResource(R.string.zayn_reuse_last_link_summary),
                                icon = painterResource(R.drawable.autorenew_24px),
                                iconTileColor = Color(0xFF16A34A),
                                onClick = {
                                    val newValue = !reuse
                                    reuse = newValue
                                    zaynPrefs.edit { putString(ZaynSmartStreams.KEY_REUSE_LAST_LINK, newValue.toString()) }
                                },
                                trailing = {
                                    Switch(checked = reuse, onCheckedChange = null)
                                },
                            )
                        }
                    ))
                }.toPersistentList(),
            ),
            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_player_layout),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = settings.player.limitPlayerTitle,
                        title = stringResource(R.string.limit_title),
                        icon = painterResource(R.drawable.match_word_24px),
                        iconTileColor = Color(0xFF7C3AED),
                        entries = integerArrayResource(R.array.limit_title_pref_values).zip(
                            stringArrayResource(R.array.limit_title_pref_names)
                        ).toMap()
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        enabled = isLayout(PHONE or EMULATOR),
                        preference = settings.player.hidePlayerControlNames,
                        icon = painterResource(R.drawable.visibility_off_24px),
                        iconTileColor = Color(0xFF7C3AED),
                        title = stringResource(R.string.hide_player_control_names),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = settings.player.showName,
                        icon = painterResource(R.drawable.label_24px),
                        iconTileColor = Color(0xFF7C3AED),
                        title = stringResource(R.string.source_name),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = settings.player.showMediaInfo,
                        icon = painterResource(R.drawable.movie_info_24px),
                        iconTileColor = Color(0xFF7C3AED),
                        title = stringResource(R.string.video_info),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = settings.player.showResolution,
                        title = stringResource(R.string.resolution),
                        icon = painterResource(R.drawable.high_res_24px),
                        iconTileColor = Color(0xFF2563EB),
                    ),
                )
            ),
            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_subtitles),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.player_subtitles_settings),
                        subtitle = stringResource(R.string.player_subtitles_settings_des),
                        icon = painterResource(R.drawable.subtitles_gear_24px),
                        iconTileColor = Color(0xFF0891B2),
                        onClick = {
                            SubtitlesFragment.push(activity, false)
                        }),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.chromecast_subtitles_settings),
                        subtitle = stringResource(R.string.chromecast_subtitles_settings_des),
                        icon = painterResource(R.drawable.cast),
                        iconTileColor = Color(0xFF0891B2),
                        onClick = {
                            ChromecastSubtitlesFragment.push(activity, false)
                        }),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_player_features),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.source_priority),
                        subtitle = stringResource(R.string.source_priority_help),
                        icon = painterResource(R.drawable.ic_baseline_people_24),
                        iconTileColor = Color(0xFF059669),
                        onClick = {
                            ioSafe {
                                val defaultSources = QualityProfileDialog.getAllDefaultSources()
                                val activity = activity ?: return@ioSafe
                                activity.runOnUiThread {
                                    QualityProfileDialog(
                                        activity,
                                        R.style.DialogFullscreenPlayer,
                                        defaultSources,
                                    ).show()
                                }
                            }
                        }),

                    Preference.PreferenceItem.SwitchPreference(
                        preference = settings.player.pipEnabled,
                        title = stringResource(R.string.picture_in_picture),
                        subtitle = stringResource(R.string.picture_in_picture_des),
                        icon = painterResource(R.drawable.pip_24px),
                        iconTileColor = Color(0xFF059669),
                    ),

                    Preference.PreferenceItem.SwitchPreference(
                        preference = settings.player.resizeEnabled,
                        title = stringResource(R.string.player_size_settings),
                        subtitle = stringResource(R.string.player_size_settings_des),
                        icon = painterResource(R.drawable.aspect_ratio_24px),
                        iconTileColor = Color(0xFF059669),
                    ),

                    Preference.PreferenceItem.SwitchPreference(
                        preference = settings.player.speedEnabled,
                        title = stringResource(R.string.eigengraumode_settings),
                        subtitle = stringResource(R.string.speed_setting_summary),
                        icon = painterResource(R.drawable.ic_baseline_speed_24),
                        iconTileColor = Color(0xFF059669),
                    ),

                    Preference.PreferenceItem.SwitchPreference(
                        preference = settings.player.compressorEnabled,
                        title = stringResource(R.string.compressor_setting),
                        subtitle = stringResource(R.string.compressor_setting_summary),
                        icon = painterResource(R.drawable.ic_compressor_24),
                        iconTileColor = Color(0xFF059669),
                    ),

                    Preference.PreferenceItem.SwitchPreference(
                        preference = settings.player.tiktokEnabled,
                        title = stringResource(R.string.speedup_title),
                        subtitle = stringResource(R.string.speedup_summary),
                        icon = painterResource(R.drawable.speedup),
                        iconTileColor = Color(0xFF059669),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = settings.player.autoPlayEnabled,
                        title = stringResource(R.string.autoplay_next_settings),
                        subtitle = stringResource(R.string.autoplay_next_settings_des),
                        icon = painterResource(R.drawable.skip_next_24px),
                        iconTileColor = Color(0xFF059669),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = settings.player.startPaused,
                        title = stringResource(R.string.start_paused_settings),
                        subtitle = stringResource(R.string.start_paused_settings_des),
                        icon = painterResource(R.drawable.pause_24px),
                        iconTileColor = Color(0xFF059669),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = settings.player.skipOpEnabled,
                        title = stringResource(R.string.video_skip_op),
                        subtitle = stringResource(R.string.enable_skip_op_from_database_des),
                        icon = painterResource(R.drawable.keyboard_double_arrow_right_24px),
                        iconTileColor = Color(0xFF059669),
                    ),
                    // Preview order: Rotate, then Auto rotate.
                    Preference.PreferenceItem.SwitchPreference(
                        preference = settings.player.rotateButtonEnabled,
                        title = stringResource(R.string.rotate_video),
                        subtitle = stringResource(R.string.rotate_video_desc),
                        icon = painterResource(R.drawable.screen_rotation),
                        iconTileColor = Color(0xFF059669),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = settings.player.autoRotateEnabled,
                        title = stringResource(R.string.auto_rotate_video),
                        subtitle = stringResource(R.string.auto_rotate_video_desc),
                        icon = painterResource(R.drawable.screen_rotation_alt_24px),
                        iconTileColor = Color(0xFF059669),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = settings.player.previewBarEnabled,
                        enabled = isLayout(PHONE or EMULATOR),
                        title = stringResource(R.string.preview_seekbar),
                        subtitle = stringResource(R.string.preview_seekbar_desc),
                        icon = painterResource(R.drawable.picture_in_picture_center_24px),
                        iconTileColor = Color(0xFF059669),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = settings.player.softwareDecoding,
                        title = stringResource(R.string.software_decoding),
                        subtitle = stringResource(R.string.software_decoding_desc),
                        icon = painterResource(R.drawable.memory_24px),
                        iconTileColor = Color(0xFF059669),
                        entries = integerArrayResource(R.array.software_decoding_switch_values).zip(
                            stringArrayResource(R.array.software_decoding_switch)
                        ).toMap()
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = settings.player.extraBrightnessEnabled,
                        title = stringResource(R.string.extra_brightness_settings),
                        subtitle = stringResource(R.string.extra_brightness_settings_des),
                        icon = painterResource(R.drawable.light_mode_24px),
                        iconTileColor = Color(0xFF059669),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_gestures),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = settings.player.swipeHorizontalEnabled,
                        title = stringResource(R.string.swipe_to_seek_settings),
                        subtitle = stringResource(R.string.swipe_to_seek_settings_des),
                        icon = painterResource(R.drawable.swipe_24px),
                        iconTileColor = Color(0xFFDB2777),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = settings.player.swipeVerticalEnabled,
                        title = stringResource(R.string.swipe_to_change_settings),
                        subtitle = stringResource(R.string.swipe_to_change_settings_des),
                        icon = painterResource(R.drawable.swipe_vertical_24px),
                        iconTileColor = Color(0xFFDB2777),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = settings.player.doubleTapToSeekEnabled,
                        title = stringResource(R.string.double_tap_to_seek_settings),
                        subtitle = stringResource(R.string.double_tap_to_seek_settings_des),
                        icon = painterResource(R.drawable.touch_double_24px),
                        iconTileColor = Color(0xFFDB2777),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = settings.player.doubleTapToPauseEnabled,
                        title = stringResource(R.string.double_tap_to_pause_settings),
                        subtitle = stringResource(R.string.double_tap_to_pause_settings_des),
                        icon = painterResource(R.drawable.touch_double_24px),
                        iconTileColor = Color(0xFFDB2777),
                    ),

                    Preference.PreferenceItem.SliderPreference(
                        preference = settings.player.doubleTapTime,
                        title = stringResource(R.string.double_tap_to_seek_amount_settings),
                        icon = painterResource(R.drawable.go_forward_30),
                        iconTileColor = Color(0xFFDB2777),
                        valueRange = 5..60,
                        //steps = 10,
                    ),
                )
            ),
            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_cache),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = settings.player.bufferDiskMB,
                        title = stringResource(R.string.video_buffer_disk_settings),
                        subtitle = "%s\n" + stringResource(R.string.video_disk_description),
                        icon = painterResource(R.drawable.hard_drive_24px),
                        iconTileColor = Color(0xFF475569),
                        entries = integerArrayResource(R.array.video_buffer_size_values).zip(
                            stringArrayResource(R.array.video_buffer_size_names)
                        ).toMap()
                    ),

                    Preference.PreferenceItem.ListPreference(
                        preference = settings.player.bufferRamMB,
                        title = stringResource(R.string.video_buffer_size_settings),
                        subtitle = "%s\n" + stringResource(R.string.video_ram_description),
                        icon = painterResource(R.drawable.memory_alt_24px),
                        iconTileColor = Color(0xFF475569),
                        entries = integerArrayResource(R.array.video_buffer_size_values).zip(
                            stringArrayResource(R.array.video_buffer_size_names)
                        ).toMap()
                    ),

                    Preference.PreferenceItem.ListPreference(
                        preference = settings.player.bufferTimeSec,
                        title = stringResource(R.string.video_buffer_length_settings),
                        subtitle = "%s\n" + stringResource(R.string.video_ram_description),
                        icon = painterResource(R.drawable.history_toggle_off_24px),
                        iconTileColor = Color(0xFF475569),
                        entries = integerArrayResource(R.array.video_buffer_length_values).zip(
                            stringArrayResource(R.array.video_buffer_length_names)
                        ).toMap()
                    ),

                    Preference.PreferenceItem.TextPreference(
                        icon = painterResource(R.drawable.ic_baseline_delete_outline_24),
                        iconTileColor = Color(0xFFE8261C),
                        title = stringResource(R.string.video_buffer_clear_settings),
                        subtitle = formatShortFileSize(LocalContext.current, cacheSize),
                        onClick = {
                            ioSafe {
                                cacheDir.deleteRecursively()
                                cacheCleared += 1
                            }
                        })
                )
            ),
            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_android_tv),
                enabled = isLayout(TV or EMULATOR),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SliderPreference(
                        preference = settings.player.tvSeekOnTime,
                        title = stringResource(R.string.android_tv_interface_on_seek_settings),
                        subtitle = stringResource(R.string.android_tv_interface_on_seek_settings_summary),
                        icon = painterResource(R.drawable.go_forward_30),
                        iconTileColor = Color(0xFFDB2777),
                        valueRange = 5..60,
                        //steps = 10,
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        preference = settings.player.tvSeekOffTime,
                        title = stringResource(R.string.android_tv_interface_off_seek_settings),
                        subtitle = stringResource(R.string.android_tv_interface_off_seek_settings_summary),
                        icon = painterResource(R.drawable.go_forward_30),
                        iconTileColor = Color(0xFFDB2777),
                        valueRange = 5..60,
                        //steps = 10,
                    ),
                )
            ),
            )
    }
}

/**
 * A Zayn Smart-Streams list row: renders exactly like a built-in list row
 * (ZaynSettingsRow) and opens the shared SingleSelectDialog. The subtitle
 * always shows the current value. The value is written to the real
 * SharedPreferences key via [onValueChanged] — no fake behaviour.
 */
@Composable
private fun ZaynSmartStreamListRow(
    title: String,
    initialValue: String,
    entries: Map<String, String>,
    icon: Painter,
    iconTileColor: Color,
    onValueChanged: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }
    var isDialogShown by remember { mutableStateOf(false) }
    ZaynSettingsRow(
        title = title,
        subtitle = entries[value] ?: value,
        icon = icon,
        iconTileColor = iconTileColor,
        onClick = { isDialogShown = true },
        trailing = null,
    )
    if (isDialogShown) {
        SingleSelectDialog(
            dismiss = { isDialogShown = false },
            title = title,
            entries = entries,
            selectedKey = value,
            confirm = { key ->
                value = key
                onValueChanged(key)
                isDialogShown = false
            },
            confirmText = stringResource(R.string.ok),
            dismissText = stringResource(R.string.cancel),
        )
    }
}
