package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.DataStoreHelper

/**
 * (v0.2.10) Minimal restore of upstream's CS3IPlayer — companion
 * only. The PrimeVideo provider calls
 * CS3IPlayer.Companion.get/setPreferredAudioTrackLanguage() to
 * remember the user's preferred audio language.
 *
 * The full player (upstream's CS3IPlayer : IPlayer, ExoPlayer,
 * track selection) is NOT restored: Zayn Play has no in-app player
 * implementing IPlayer, and faking one would be dishonest. Only the
 * persisted preference is real: it is stored in the host DataStore
 * under upstream's own key, exactly as upstream's getter/setter do.
 * The class itself is a plain class here (upstream extends IPlayer,
 * which is not vendored) — no plugin instantiates it or calls any
 * instance member.
 */
class CS3IPlayer {
    companion object {
        private const val PREFERRED_AUDIO_LANGUAGE_KEY = "preferred_audio_language"

        /**
         * Setting this variable is permanent across app sessions.
         **/
        var preferredAudioTrackLanguage: String? = null
            get() {
                return field ?: CloudStreamApp.getKey<String>(
                    "${DataStoreHelper.currentAccount}/$PREFERRED_AUDIO_LANGUAGE_KEY",
                    field
                )?.also {
                    field = it
                }
            }
            set(value) {
                CloudStreamApp.setKey(
                    "${DataStoreHelper.currentAccount}/$PREFERRED_AUDIO_LANGUAGE_KEY",
                    value
                )
                field = value
            }
    }
}
