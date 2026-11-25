// Zayn Play setup — Extensions (step 3 of 6).
//
// FRESH BUILD (delete-first rebuild, v1.0.9): replaces the deleted
// SetupFragmentExtensions.kt and fragment_setup_extensions.xml. The repo
// search box is now part of the layout (was built in code, plain). Same
// step, same order, same behaviour (install / test / toggle providers).

package com.lagradost.cloudstream3.ui.setup

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity.Companion.afterRepositoryLoadedEvent
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentZaynSetupExtensionsBinding
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.home.ZaynProviders
import com.lagradost.cloudstream3.ui.home.ZaynProviders.ZaynRepo
import com.lagradost.cloudstream3.ui.settings.extensions.ZaynRepoActions
import com.lagradost.cloudstream3.ui.settings.extensions.ZaynRepoAdapter
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding

class ZaynSetupExtensionsFragment : BaseFragment<FragmentZaynSetupExtensionsBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentZaynSetupExtensionsBinding::inflate)
) {
    companion object {
        const val SETUP_EXTENSION_BUNDLE_IS_SETUP = "isSetup"

        /**
         * If false then this is treated a singular screen with a done button
         * */
        fun newInstance(isSetup: Boolean): Bundle {
            return Bundle().apply {
                putBoolean(SETUP_EXTENSION_BUNDLE_IS_SETUP, isSetup)
            }
        }
    }

    private var zaynAdapter: ZaynRepoAdapter? = null
    private var allRepos: List<ZaynRepo> = emptyList()
    private var searchQuery: String = ""
    private val busyInstallIds = mutableSetOf<String>()
    private val busyTestIds = mutableSetOf<String>()

    override fun onResume() {
        super.onResume()
        afterRepositoryLoadedEvent += ::setRepositories
    }

    override fun onStop() {
        super.onStop()
        afterRepositoryLoadedEvent -= ::setRepositories
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(view)
    }

    private fun installedUrls(): Set<String> =
        RepositoryManager.getRepositories().map { it.url }.toSet()

    private fun applyFilter() {
        val q = searchQuery.trim().lowercase()
        val list = if (q.isBlank()) allRepos
        else allRepos.filter { repo ->
            repo.name.lowercase().contains(q) ||
                repo.shortcode.lowercase().contains(q) ||
                repo.plugins.any { it.lowercase().contains(q) }
        }
        zaynAdapter?.submitList(list)
    }

    private fun installRepo(repo: ZaynRepo) {
        if (!busyInstallIds.add(repo.id)) return
        zaynAdapter?.busyInstallIds = busyInstallIds.toSet()
        zaynAdapter?.refresh()
        ioSafe {
            val error = ZaynRepoActions.installRepo(activity, repo)
            main {
                busyInstallIds.remove(repo.id)
                zaynAdapter?.busyInstallIds = busyInstallIds.toSet()
                if (error == null) {
                    showToast(R.string.zayn_repos_install_ok, Toast.LENGTH_SHORT)
                } else {
                    showToast(R.string.zayn_repos_install_fail, Toast.LENGTH_LONG)
                }
                // Refresh installed states; plugins load via afterRepositoryLoadedEvent.
                setRepositories()
            }
        }
    }

    private fun testRepo(repo: ZaynRepo) {
        if (!busyTestIds.add(repo.id)) return
        zaynAdapter?.busyTestIds = busyTestIds.toSet()
        zaynAdapter?.refresh()
        ioSafe {
            val count = ZaynRepoActions.testRepo(repo)
            main {
                busyTestIds.remove(repo.id)
                zaynAdapter?.busyTestIds = busyTestIds.toSet()
                if (count != null) {
                    showToast(
                        context?.getString(R.string.zayn_repos_test_ok, count),
                        Toast.LENGTH_SHORT
                    )
                } else {
                    showToast(R.string.zayn_repos_test_fail, Toast.LENGTH_LONG)
                }
            }
        }
    }

    private fun setRepositories(success: Boolean = true) {
        main {
            val context = context ?: return@main
            allRepos = ZaynProviders.loadRepos(context)
            // The bundled catalogue is always non-empty, so the blank screen
            // is never shown on this step any more.
            binding?.repoRecyclerView?.isVisible = true
            binding?.blankRepoScreen?.isVisible = false

            if (zaynAdapter == null) {
                zaynAdapter = ZaynRepoAdapter(
                    repos = allRepos,
                    isInstalled = { installedUrls().contains(it.url) },
                    onCount = { ZaynProviders.onCountForRepo(it) },
                    isProviderOn = { ZaynProviders.isProviderEnabled(it) },
                    onInstall = { installRepo(it) },
                    onTest = { testRepo(it) },
                    onToggleProvider = { name, on ->
                        ZaynProviders.setProviderEnabled(name, on)
                        zaynAdapter?.refresh()
                        showToast(
                            if (on) R.string.zayn_provider_enabled
                            else R.string.zayn_provider_disabled,
                            Toast.LENGTH_SHORT
                        )
                    },
                    onToggleAll = { repo, on ->
                        ZaynProviders.setProvidersEnabled(repo.plugins, on)
                        zaynAdapter?.refresh()
                    },
                ).also { binding?.repoRecyclerView?.adapter = it }
            } else {
                zaynAdapter?.busyInstallIds = busyInstallIds.toSet()
                zaynAdapter?.busyTestIds = busyTestIds.toSet()
            }
            applyFilter()
        }
    }

    override fun onBindingCreated(binding: FragmentZaynSetupExtensionsBinding) {
        val isSetup = arguments?.getBoolean(SETUP_EXTENSION_BUNDLE_IS_SETUP) ?: false

        safe {
            // Search/filter box is part of the layout now (Zayn search pill).
            binding.repoSearchBox.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?, start: Int, count: Int, after: Int
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?, start: Int, before: Int, count: Int
                ) = Unit

                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString().orEmpty()
                    applyFilter()
                }
            })

            setRepositories()
            binding.apply {
                if (!isSetup) {
                    nextBtt.setText(R.string.setup_done)
                }
                prevBtt.isVisible = isSetup

                nextBtt.setOnClickListener {
                    // Continue setup
                    if (isSetup)
                        if (
                        // If any available languages
                            apis.distinctBy { it.lang }.size > 1
                        ) {
                            findNavController().navigate(R.id.action_navigation_setup_extensions_to_navigation_setup_provider_languages)
                        } else {
                            findNavController().navigate(R.id.action_navigation_setup_extensions_to_navigation_setup_media)
                        }
                    else
                        findNavController().navigate(R.id.navigation_home)
                }

                prevBtt.setOnClickListener {
                    findNavController().navigate(R.id.navigation_setup_language)
                }
            }
        }
    }
}
