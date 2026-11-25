// Zayn Play setup — App Layout (step 6 of 6).
//
// FRESH BUILD (delete-first rebuild, v1.0.9): replaces the deleted
// SetupFragmentLayout.kt and fragment_setup_layout.xml. Same step, same
// order, same behaviour (Auto/Phone/TV/Emulator, activity recreates on pick).

package com.lagradost.cloudstream3.ui.setup

import android.view.View
import android.widget.AbsListView
import android.widget.ArrayAdapter
import androidx.core.content.edit
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentZaynSetupLayoutBinding
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding

class ZaynSetupLayoutFragment : BaseFragment<FragmentZaynSetupLayoutBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentZaynSetupLayoutBinding::inflate)
) {

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(view)
    }

    override fun onBindingCreated(binding: FragmentZaynSetupLayoutBinding) {
        safe {
            val ctx = context ?: return@safe

            val settingsManager = PreferenceManager.getDefaultSharedPreferences(ctx)

            val prefNames = resources.getStringArray(R.array.app_layout)
            val prefValues = resources.getIntArray(R.array.app_layout_values)

            val currentLayout =
                settingsManager.getInt(getString(R.string.app_layout_key), -1)

            val arrayAdapter =
                ArrayAdapter<String>(ctx, R.layout.zayn_setup_option_single)

            arrayAdapter.addAll(prefNames.toList())
            binding.apply {
                listview1.adapter = arrayAdapter
                listview1.choiceMode = AbsListView.CHOICE_MODE_SINGLE
                listview1.setItemChecked(
                    prefValues.indexOf(currentLayout), true
                )

                listview1.setOnItemClickListener { _, _, position, _ ->
                    settingsManager.edit {
                        putInt(getString(R.string.app_layout_key), prefValues[position])
                    }
                    activity?.recreate()
                }

                nextBtt.setOnClickListener {
                    setKey(HAS_DONE_SETUP_KEY, true)
                    findNavController().navigate(R.id.navigation_home)
                }

                prevBtt.setOnClickListener {
                    findNavController().popBackStack()
                }
            }
        }
    }
}
