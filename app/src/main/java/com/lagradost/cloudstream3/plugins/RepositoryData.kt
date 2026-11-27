package com.lagradost.cloudstream3.plugins

/**
 * (v1.0.2) The real RepositoryData class lives at the upstream location,
 * com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData (see
 * that file for why: plugins compiled against upstream call it by that
 * exact type). This typealias keeps every existing plugins-package
 * reference compiling unchanged — it erases to the UI-package class,
 * so RepositoryManager.getRepositories()/addRepository() carry the
 * UI-package JVM descriptors that compiled plugins expect.
 */

typealias RepositoryData = com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData

const val REPOSITORIES_KEY = "REPOSITORIES_KEY"
