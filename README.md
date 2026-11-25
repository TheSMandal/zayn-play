# Zayn Play

**ONE APPS EVERY UNIVERSE**

Zayn Play is Samim Mandal's own streaming app — movies, series, anime and live TV, in one place. Watch from providers you install, switch between them from the tabs on top, and search everything at once.

> The app itself provides no video sources. Content comes from extensions (providers) installed from repositories — install a repo from Extensions, turn its providers ON, and they appear as tabs on Home.

## Features

- **Provider tabs** — every provider you turn ON appears as a tab under the search bar; tap a name to switch its whole feed
- **Two searches** — the Home search bar searches inside the current provider; the Search tab searches all providers at once
- **Smart-Streams** — plays from the fastest server by default, with your preferred resolution and audio languages; or choose a server yourself
- **Two players** — pick your player from Settings, with external player support (VLC / MX Player / JustPlayer)
- **Downloads** — download to watch offline, with progress and a badge
- **Picture-in-Picture mini player, Skip Intro, autoplay next episode**
- **Subtitles** — download subtitles from OpenSubtitles / SubDL
- **Library** — Watching, Plan to Watch, Completed, On-Hold, Dropped
- **11 languages** — English, বাংলা, हिन्दी, मराठी, తెలుగు, தமிழ், ગુજરાતી, اردو, ಕನ್ನಡ, ଓଡ଼ିଆ, മലയാളം
- **Backup & Restore** — back up your setup to a file and restore it
- **Light & Dark mode** — follows your device, or pick from Settings

## Install

1. Download the latest APK from the [Actions](../../actions/workflows/build-apk.yml) build (`zayn-play-1.0.x` artifact) — or from a release, when published.
2. Install it on your Android phone (Android 6.0+).
3. Open the app, finish the first-run setup, then go to **Extensions**, install a repository (for example Phisher or NetMirror), and turn its providers ON.

## Providers

Providers come from community repositories (27 built in, including Phisher, Megix, NetMirror, CNC Verse and the official CloudStream repo). Add more from any repo shortcode or `repo.json` URL. A provider with several sites shows each site separately, each with its own name.

## About

- **Design by:** Samim Mandal
- **Website:** https://zaynplay.netlify.app
- **Package:** `app.netlify.zaynplay`
- **Built on:** [CloudStream](https://github.com/recloudstream/cloudstream) — Zayn Play carries CloudStream's whole system and settings, with its own Zayn Play design on top.
- **License:** GPL-3.0 — this project stays open source, like the project it is built on.
