package com.lagradost.cloudstream3.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * (v0.2.10) Minimal restore of upstream's WatchType / SyncWatchType
 * enums (ui/WatchType.kt). Entry names, declaration order (ordinal)
 * and internalIds are verbatim from upstream — StreamCenter reads
 * ordinals and hashCodes, and its <clinit> touches SyncWatchType
 * entries, so the enum must exist for the plugin class to initialise.
 *
 * stringRes / iconRes are 0: they reference CloudStream's own
 * R.string / R.drawable entries (type_watching, ic_baseline_...),
 * which this host does not have. Nothing in the content path reads
 * them; the one caller of getStringRes (StreamCenter's tracking
 * status label) would get resource 0. Do NOT invent resource IDs.
 */
enum class WatchType(val internalId: Int, @StringRes val stringRes: Int, @DrawableRes val iconRes: Int) {
    WATCHING(0, 0, 0),
    COMPLETED(1, 0, 0),
    ONHOLD(2, 0, 0),
    DROPPED(3, 0, 0),
    PLANTOWATCH(4, 0, 0),
    NONE(5, 0, 0);

    companion object {
        fun fromInternalId(id: Int?) = entries.find { value -> value.internalId == id } ?: NONE
    }
}

enum class SyncWatchType(val internalId: Int, @StringRes val stringRes: Int, @DrawableRes val iconRes: Int) {
    NONE(-1, 0, 0),
    WATCHING(0, 0, 0),
    COMPLETED(1, 0, 0),
    ONHOLD(2, 0, 0),
    DROPPED(3, 0, 0),
    PLANTOWATCH(4, 0, 0),
    REWATCHING(5, 0, 0);

    companion object {
        fun fromInternalId(id: Int?) = entries.find { value -> value.internalId == id } ?: NONE
    }
}
