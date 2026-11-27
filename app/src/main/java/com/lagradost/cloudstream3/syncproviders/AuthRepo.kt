package com.lagradost.cloudstream3.syncproviders

/**
 * (v0.2.10) Minimal faithful restore of upstream's AuthRepo.
 *
 * Upstream's authUser() reads the linked account from the account
 * store. Zayn Play has no sync account at all, so this always returns
 * null — which is exactly the "no account linked" state plugins
 * already handle (StreamPlay/TorraStream take their non-Personal
 * branch when authUser() is null; see SyncAPI.kt). No login, token
 * refresh or account-store code is restored.
 */
abstract class AuthRepo(open val api: AuthAPI) {
    val name: String get() = api.name
    fun authUser(): AuthUser? = null
    fun authData(): AuthData? = null
}
