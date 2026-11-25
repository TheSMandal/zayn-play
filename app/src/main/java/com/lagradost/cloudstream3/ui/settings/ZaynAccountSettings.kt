// Zayn Play — Accounts and Security settings screen.
//
// FRESH BUILD (delete-first rebuild, v1.0.9): replaces the deleted
// SettingsAccountScreen.kt AND the deleted old SettingsAccount.kt fragment.
// The old XML preference screen (settings_account.xml) and the old
// fragment are gone. The account login/logout logic that lived in the old
// fragment's companion (showLoginInfo, showAccountSwitch, showPin,
// showAppLogin, addAccount) was moved here first, unchanged, so nothing
// is lost. Rows, order, settings keys and handlers are identical to the
// old screen; only the rendering is new (ZaynSettingsUi) with the
// preview's tile colours.
// NOTE: showLoginInfo was marked "Used by nginx plugin too" in the old
// file; it now lives as ZaynAccountSettings.showLoginInfo.

package com.lagradost.cloudstream3.ui.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.CountDownTimer
import android.view.View.FOCUS_DOWN
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.compose.LocalActivity
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CloudStreamApp.Companion.openBrowser
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.CommonActivity.onDialogDismissedEvent
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.AccountManagmentBinding
import com.lagradost.cloudstream3.databinding.AccountSwitchBinding
import com.lagradost.cloudstream3.databinding.AddAccountInputBinding
import com.lagradost.cloudstream3.databinding.DeviceAuthBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.aniListApi
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.animeSkipApi
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.kitsuApi
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.malApi
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.openSubtitlesApi
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.simklApi
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.subDlApi
import com.lagradost.cloudstream3.syncproviders.AuthLoginResponse
import com.lagradost.cloudstream3.syncproviders.AuthRepo
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.syncproviders.PlainAuthRepo
import com.lagradost.cloudstream3.syncproviders.SubtitleRepo
import com.lagradost.cloudstream3.syncproviders.SyncRepo
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.BackupUtils
import com.lagradost.cloudstream3.utils.BiometricAuthenticator
import com.lagradost.cloudstream3.utils.BiometricAuthenticator.authCallback
import com.lagradost.cloudstream3.utils.BiometricAuthenticator.biometricPrompt
import com.lagradost.cloudstream3.utils.BiometricAuthenticator.deviceHasPasswordPinLock
import com.lagradost.cloudstream3.utils.BiometricAuthenticator.isAuthEnabled
import com.lagradost.cloudstream3.utils.BiometricAuthenticator.promptInfo
import com.lagradost.cloudstream3.utils.BiometricAuthenticator.startBiometricAuthentication
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialogText
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.hideProgress
import com.lagradost.cloudstream3.utils.UIHelper.showProgress
import com.lagradost.cloudstream3.utils.setText
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.cloudstream4.AppSettings
import com.lagradost.cloudstream4.compose.PHONE
import com.lagradost.cloudstream4.compose.isLayout
import com.lagradost.cloudstream4.rememberAppSettings
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.SearchableSettings
import kotlinx.collections.immutable.persistentListOf
import qrcode.QRCode

object ZaynAccountSettings : SearchableSettings, BiometricAuthenticator.BiometricCallback {
    val syncApis = persistentListOf(
        SyncRepo(malApi),
        SyncRepo(kitsuApi),
        SyncRepo(aniListApi),
        SyncRepo(simklApi),
        SubtitleRepo(openSubtitlesApi),
        SubtitleRepo(subDlApi),
        PlainAuthRepo(animeSkipApi),
    )

    // Preview tile colours, in syncApis order:
    // MyAnimeList, Kitsu, AniList, Simkl, OpenSubtitles, SubDL, AnimeSkip.
    private val accountTileColors = listOf(
        Color(0xFF2E51A2),
        Color(0xFFE75E3D),
        Color(0xFF02A9FF),
        Color(0xFF0F1014),
        Color(0xFF475569),
        Color(0xFF0891B2),
        Color(0xFF7C3AED),
    )

    private fun updateAuthPreference(context: Context, enabled: Boolean) {
        val settings = AppSettings(context)
        settings.security.biometrics.set(enabled)
    }

    override fun onAuthenticationError() {
        val context = activity ?: return
        updateAuthPreference(context, !isAuthEnabled(context))
    }

    override fun onAuthenticationSuccess() {
        val context = activity ?: return

        if (isAuthEnabled(context)) {
            updateAuthPreference(context, true)
            BackupUtils.backup(context)
            context.showBottomDialogText(
                context.getString(R.string.biometric_setting),
                context.getString(R.string.biometric_warning).html()
            ) { onDialogDismissedEvent }
        } else {
            updateAuthPreference(context, false)
        }
    }

    @Composable
    override fun getTitleRes(): String = stringResource(R.string.category_account)

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
        val activity = LocalActivity.current
        val context = LocalContext.current
        val hasSecurity = remember(context) {
            try {
                deviceHasPasswordPinLock(context)
            } catch (_: Throwable) {
                // e.g preview
                false
            }
        }

        return persistentListOf(
            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_accounts),
                preferenceItems = syncApis.mapIndexed { index, api ->
                    Preference.PreferenceItem.TextPreference(
                        title = api.name,
                        icon = api.icon?.let { painterResource(it) },
                        iconTileColor = accountTileColors.getOrNull(index),
                        onClick = {
                            val activity = activity ?: return@TextPreference
                            val info = api.authUser()
                            val index =
                                api.accounts.indexOfFirst { account -> account.user.id == info?.id }
                            if (api.accounts.isNotEmpty()) {
                                showLoginInfo(activity, api, info, index)
                            } else {
                                addAccount(activity, api)
                            }
                        })
                } + Preference.PreferenceItem.SwitchPreference(
                    preference = settings.security.skipAccountSelection,
                    title = stringResource(R.string.skip_startup_account_select_pref),
                    icon = painterResource(R.drawable.ic_outline_account_circle_24),
                    iconTileColor = Color(0xFF334155),
                ),
            ), Preference.PreferenceGroup(
                enabled = hasSecurity && isLayout(PHONE),
                title = stringResource(R.string.pref_category_security),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = settings.security.biometrics,
                        title = stringResource(R.string.biometric_setting),
                        subtitle = stringResource(R.string.biometric_setting_summary),
                        icon = painterResource(R.drawable.ic_fingerprint),
                        iconTileColor = Color(0xFF059669),
                        onValueChanged = { _ ->
                            val activity =
                                activity as? FragmentActivity ?: return@SwitchPreference false

                            if (deviceHasPasswordPinLock(activity)) {
                                startBiometricAuthentication(
                                    activity, R.string.biometric_authentication_title, false
                                )
                                promptInfo?.let {
                                    authCallback = this
                                    biometricPrompt?.authenticate(it)
                                }
                            }

                            return@SwitchPreference true
                        })
                )
            )
        )
    }

    /** Used by nginx plugin too */
    @SuppressLint("StringFormatInvalid")
    fun showLoginInfo(
        activity: Activity?,
        api: AuthRepo,
        info: AuthUser?,
        index: Int,
    ) {
        if (activity == null) return
        val binding: AccountManagmentBinding =
            AccountManagmentBinding.inflate(activity.layoutInflater, null, false)
        val builder =
            AlertDialog.Builder(activity, R.style.AlertDialogCustom)
                .setView(binding.root)
        val dialog = builder.show()

        binding.accountMainProfilePictureHolder.isVisible =
            !info?.profilePicture.isNullOrEmpty()
        binding.accountMainProfilePicture.loadImage(info?.profilePicture)

        binding.accountLogout.isVisible = info != null
        binding.accountLogout.setOnClickListener {
            if (info != null) {
                ioSafe { api.logout(info) }
            }
            dialog.dismissSafe(activity)
        }

        dialog.findViewById<TextView>(R.id.account_name)?.text = if (info != null) {
            info.name ?: "%s %d".format(
                activity.getString(R.string.account),
                index + 1
            )
        } else {
            activity.getString(R.string.no_account)
        }

        binding.accountSite.text = api.name
        binding.accountSwitchAccount.setOnClickListener {
            dialog.dismissSafe(activity)
            showAccountSwitch(activity, api)
        }

        if (Globals.isLayout(Globals.TV or Globals.EMULATOR)) {
            binding.accountSwitchAccount.requestFocus()
        }
    }

    private fun showAccountSwitch(activity: Activity, api: AuthRepo) {
        val accounts = api.accounts
        val binding: AccountSwitchBinding =
            AccountSwitchBinding.inflate(activity.layoutInflater, null, false)

        val builder =
            AlertDialog.Builder(activity, R.style.AlertDialogCustom)
                .setView(binding.root)
        val dialog = builder.show()

        binding.accountAdd.setOnClickListener {
            addAccount(activity, api)
            dialog?.dismissSafe(activity)
        }

        binding.accountNone.setOnClickListener {
            api.accountId = -1
            dialog?.dismissSafe(activity)
        }

        val adapter = AccountAdapter {
            dialog?.dismissSafe(activity)
            api.accountId = it.card.user.id
        }.apply {
            submitList(accounts.toList())
        }
        val list = dialog.findViewById<RecyclerView>(R.id.account_list)
        list?.adapter = adapter
    }

    @UiThread
    fun showPin(activity: Activity, api: AuthRepo) {
        val binding: DeviceAuthBinding =
            DeviceAuthBinding.inflate(activity.layoutInflater, null, false)

        val builder =
            AlertDialog.Builder(activity)
                .setView(binding.root)

        builder.apply {
            setNegativeButton(R.string.cancel) { _, _ -> }
            if (api.hasOAuth2) {
                setPositiveButton(R.string.auth_locally) { _, _ ->
                    api.openOAuth2PageWithToast()
                }
            }
        }

        val dialog = builder.create()

        ioSafe {
            val pinCodeData = try {
                api.pinRequest()
            } catch (e: ErrorLoadingException) {
                if (e.message != null) {
                    showToast(e.message)
                    null
                } else {
                    throw e
                }
            } catch (t: Throwable) {
                logError(t)
                null
            }
            if (pinCodeData == null) {
                if (api.hasOAuth2) {
                    showToast(R.string.device_pin_error_message)
                    api.openOAuth2PageWithToast()
                } else {
                    showToast(
                        txt(
                            R.string.authenticated_user_fail,
                            api.name
                        )
                    )
                }
                return@ioSafe
            }

            val qrCodeImage = QRCode.ofRoundedSquares()
                .withColor(activity.colorFromAttribute(R.attr.textColor))
                .withBackgroundColor(activity.colorFromAttribute(R.attr.primaryBlackBackground))
                .build(pinCodeData.verificationUrl)
                .render().nativeImage() as Bitmap

            activity.runOnUiThread {
                dialog.show()
                binding.apply {
                    devicePinCode.setText(txt(pinCodeData.userCode))
                    deviceAuthMessage.setText(
                        txt(
                            R.string.device_pin_url_message,
                            pinCodeData.verificationUrl
                        )
                    )
                    deviceAuthQrcode.loadImage(qrCodeImage)
                }

                val expirationMillis =
                    pinCodeData.expiresIn.times(1000).toLong()

                object : CountDownTimer(expirationMillis, 1000) {
                    override fun onTick(millisUntilFinished: Long) {
                        val secondsUntilFinished =
                            millisUntilFinished.div(1000).toInt()

                        binding.deviceAuthValidationCounter.setText(
                            txt(
                                R.string.device_pin_counter_text,
                                secondsUntilFinished.div(60),
                                secondsUntilFinished.rem(60)
                            )
                        )

                        ioSafe {
                            if (secondsUntilFinished.rem(pinCodeData.interval) == 0 && api.login(
                                    pinCodeData
                                )
                            ) {
                                showToast(
                                    txt(
                                        R.string.authenticated_user,
                                        api.name
                                    )
                                )
                                dialog.dismissSafe(activity)
                                cancel()
                            }
                        }
                    }

                    override fun onFinish() {
                        showToast(R.string.device_pin_expired_message)
                        dialog.dismissSafe(activity)
                    }
                }.start()
            }
        }
    }

    fun showAppLogin(activity: Activity, api: AuthRepo) {
        val binding: AddAccountInputBinding =
            AddAccountInputBinding.inflate(activity.layoutInflater, null, false)
        val builder =
            AlertDialog.Builder(activity, R.style.AlertDialogCustom)
                .setView(binding.root)
        val dialog = builder.show()
        val req =
            api.inAppLoginRequirement ?: throw ErrorLoadingException("Missing LoginRequirement")
        val visibilityMap = listOf(
            binding.loginEmailInput to req.email,
            binding.loginPasswordInput to req.password,
            binding.loginServerInput to req.server,
            binding.loginUsernameInput to req.username
        )

        if (Globals.isLayout(Globals.TV or Globals.EMULATOR)) {
            visibilityMap.forEach { (input, isVisible) ->
                input.isVisible = isVisible

                // Band-aid for weird FireTV behavior causing crashes because keyboard covers the screen
                input.setOnEditorActionListener { textView, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_NEXT) {
                        val view = textView.focusSearch(FOCUS_DOWN)
                        return@setOnEditorActionListener view?.requestFocus(
                            FOCUS_DOWN
                        ) == true
                    }
                    return@setOnEditorActionListener true
                }
            }
        } else {
            visibilityMap.forEach { (input, isVisible) ->
                input.isVisible = isVisible
            }
        }

        binding.createAccount.isGone = api.createAccountUrl.isNullOrBlank()
        binding.createAccount.setOnClickListener {
            openBrowser(
                api.createAccountUrl ?: return@setOnClickListener,
                activity,
            )
            dialog.dismissSafe()
        }

        val displayedItems = listOf(
            binding.loginUsernameInput,
            binding.loginEmailInput,
            binding.loginServerInput,
            binding.loginPasswordInput
        ).filter { it.isVisible }

        displayedItems.foldRight(displayedItems.firstOrNull()) { item, previous ->
            item.id.let { previous?.nextFocusDownId = it }
            previous?.id?.let { item.nextFocusUpId = it }
            item
        }

        displayedItems.firstOrNull()?.let {
            binding.createAccount.nextFocusDownId = it.id
            it.nextFocusUpId = binding.createAccount.id
        }
        binding.applyBtt.id.let {
            displayedItems.lastOrNull()?.nextFocusDownId = it
        }

        binding.text1.text = api.name

        binding.applyBtt.setOnClickListener {
            val loginData = AuthLoginResponse(
                username = if (req.username) binding.loginUsernameInput.text?.toString() else null,
                password = if (req.password) binding.loginPasswordInput.text?.toString() else null,
                email = if (req.email) binding.loginEmailInput.text?.toString() else null,
                server = if (req.server) binding.loginServerInput.text?.toString() else null,
            )
            binding.applyBtt.showProgress()
            ioSafe {
                try {
                    if (api.login(loginData)) {
                        showToast(
                            txt(
                                R.string.authenticated_user,
                                api.name
                            )
                        )
                        dialog.dismissSafe(activity)
                    } else {
                        showToast(
                            txt(
                                R.string.authenticated_user_fail,
                                api.name
                            )
                        )
                    }
                } catch (t: Throwable) {
                    if (t is ErrorLoadingException && t.message != null) {
                        showToast(t.message)
                        return@ioSafe
                    }
                    showToast(
                        txt(
                            R.string.authenticated_user_fail,
                            api.name
                        )
                    )
                } finally {
                    binding.applyBtt.hideProgress()
                }
            }
        }
        binding.cancelBtt.setOnClickListener {
            dialog.dismissSafe(activity)
        }
    }

    @UiThread
    fun addAccount(activity: Activity, api: AuthRepo) {
        try {
            if (api.hasPin && !Globals.isLayout(Globals.PHONE)) {
                showPin(activity, api)
            } else if (api.hasOAuth2) {
                api.openOAuth2PageWithToast()
            } else if (api.hasInApp) {
                showAppLogin(activity, api)
            } else {
                throw NotImplementedError("The api ${api.name} has no login")
            }
        } catch (t: Throwable) {
            showToast(txt(R.string.authenticated_user_fail, api.name))
            logError(t)
        }
    }
}
