// Zayn Play setup — Extension languages / Provider Language (step 4 of 6).
//
// FRESH BUILD (delete-first rebuild, v1.0.9): replaces the deleted
// SetupFragmentProviderLanguage.kt and fragment_setup_provider_languages.xml.
// Same step, same order, same behaviour. The provider-language default is
// en+bn+hi (getApiProviderLangSettings); "All languages" still unticks
// the individuals and vice versa.

package com.lagradost.cloudstream3.ui.setup

import android.util.TypedValue
import android.view.View
import android.widget.AbsListView
import android.widget.ArrayAdapter
import androidx.core.content.edit
import androidx.core.util.forEach
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.databinding.FragmentZaynSetupProviderLanguagesBinding
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.settings.providerLanguageTagsInOrder
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.SubtitleHelper.getNameNextToFlagEmoji
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding

class ZaynSetupProviderLanguageFragment : BaseFragment<FragmentZaynSetupProviderLanguagesBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentZaynSetupProviderLanguagesBinding::inflate)
) {

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(view)
    }

    override fun onBindingCreated(binding: FragmentZaynSetupProviderLanguagesBinding) {
        safe {
            val ctx = context ?: return@safe

            val settingsManager = PreferenceManager.getDefaultSharedPreferences(ctx)

            val subColor = TypedValue().let { tv ->
                ctx.theme.resolveAttribute(R.attr.grayTextColor, tv, true)
                tv.data
            }
            val arrayAdapter =
                ArrayAdapter<CharSequence>(ctx, R.layout.zayn_setup_option_multi)

            val currentLangTags = ctx.getApiProviderLangSettings()

            // Zayn Play: always All + the 11 languages in the user's order,
            // then any extra installed-provider tags — never only the installed
            // providers' languages (with no repos installed, Bengali/Hindi
            // would never appear and could never be ticked).
            val installedLangs = APIHolder.apis.withLock {
                APIHolder.apis.map { it.lang }.distinct()
            }
            val languagesTagName = providerLanguageTagsInOrder(installedLangs).map { tag ->
                if (tag == AllLanguagesName) {
                    Pair(tag, getString(R.string.all_languages_preference))
                } else {
                    Pair(tag, getNameNextToFlagEmoji(tag) ?: tag)
                }
            }

            val currentIndexList = currentLangTags.map { langTag ->
                languagesTagName.indexOfFirst { lang -> lang.first == langTag }
            }.filter { it > -1 }

            arrayAdapter.addAll(languagesTagName.map { (tag, display) ->
                val sub = when (tag) {
                    AllLanguagesName -> getString(R.string.zayn_setup_all_languages_sub)
                    else -> zaynLanguageEnglishNames[tag]
                }
                zaynSetupOptionText(display, sub, subColor)
            })
            binding.apply {
                listview1.adapter = arrayAdapter
                listview1.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE
                currentIndexList.forEach {
                    listview1.setItemChecked(it, true)
                }

                listview1.setOnItemClickListener { _, _, position, _ ->
                    val clickedTag = languagesTagName[position].first
                    val isNowChecked = listview1.isItemChecked(position)
                    if (clickedTag == AllLanguagesName && isNowChecked) {
                        // "All languages" picked: untick every individual language
                        for (i in languagesTagName.indices) {
                            if (i != position) listview1.setItemChecked(i, false)
                        }
                    } else if (clickedTag != AllLanguagesName && isNowChecked) {
                        // An individual language picked: untick "All languages"
                        val allIndex = languagesTagName.indexOfFirst { it.first == AllLanguagesName }
                        if (allIndex >= 0) listview1.setItemChecked(allIndex, false)
                    }
                    val selectedLanguages = mutableSetOf<String>()
                    listview1.checkedItemPositions?.forEach { key, value ->
                        if (value) selectedLanguages.add(languagesTagName[key].first)
                    }
                    settingsManager.edit {
                        putStringSet(
                            ctx.getString(R.string.provider_lang_key),
                            selectedLanguages.toSet()
                        )
                    }
                }

                nextBtt.setOnClickListener {
                    findNavController().navigate(R.id.navigation_setup_provider_languages_to_navigation_setup_media)
                }

                prevBtt.setOnClickListener {
                    findNavController().popBackStack()
                }
            }
        }
    }
}
