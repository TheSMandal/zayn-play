package com.lagradost.cloudstream3.ui.settings.extensions

import android.os.Bundle
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.lagradost.cloudstream4.compose.Screen

/** Glue code to connect the navigation graph to the Compose plugins screen. */
class PluginsFragment2 : BaseComposeFragment(), Screen by PluginsScreen {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repositoryData = arguments?.getString(PLUGINS_BUNDLE_DATA)?.let { data ->
            tryParseJson<RepositoryData>(data)
        }
        val isLocal = arguments?.getBoolean(PLUGINS_BUNDLE_LOCAL) == true
        PluginsScreen.args = if (repositoryData != null) {
            PluginsScreen.PluginsArgs(repositoryData, isLocal)
        } else {
            null
        }
    }

    companion object {
        const val PLUGINS_BUNDLE_DATA = "data"
        const val PLUGINS_BUNDLE_LOCAL = "isLocal"

        fun newInstance(repositoryData: RepositoryData): Bundle {
            return Bundle().apply {
                putString(PLUGINS_BUNDLE_DATA, repositoryData.toJson())
                putBoolean(PLUGINS_BUNDLE_LOCAL, false)
            }
        }

        fun newLocalInstance(name: String): Bundle {
            return Bundle().apply {
                putString(PLUGINS_BUNDLE_DATA, RepositoryData("", name, "").toJson())
                putBoolean(PLUGINS_BUNDLE_LOCAL, true)
            }
        }
    }
}
