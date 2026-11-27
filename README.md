<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Zayn Play logo" width="128" />
</p>

<h1 align="center">Zayn Play</h1>

<p align="center"><b>ONE APPS EVERY UNIVERSE</b></p>

<p align="center">
  <a href="https://github.com/TheSMandal/zayn-play/actions/workflows/build-apk.yml"><img src="https://github.com/TheSMandal/zayn-play/actions/workflows/build-apk.yml/badge.svg" alt="Build status" /></a>
  <img src="https://img.shields.io/badge/version-1.0.5-E8261C.svg" alt="Version 1.0.5" />
  <a href="https://www.gnu.org/licenses/gpl-3.0"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License: GPL v3" /></a>
  <img src="https://img.shields.io/badge/platform-Android%206.0%2B-green.svg" alt="Android 6.0+" />
</p>

<p align="center">
  Zayn Play is Samim Mandal's own streaming app — movies, series, anime and live TV, in one place.<br />
  Watch from providers you install, switch between them from the tabs on top, and search everything at once.
</p>

<p align="center">
  <a href="https://zaynplay.netlify.app">Website</a> •
  <a href="https://github.com/TheSMandal/zayn-play/releases">Download</a> •
  <a href="https://github.com/TheSMandal/zayn-play/issues">Report an issue</a>
</p>

---

## About

Zayn Play is an extension-based streaming app. **The app itself provides no video sources** — content comes from providers (extensions) installed from repositories. Install a repository, turn its providers ON, and they appear as tabs on the home screen. Tap a provider's name to switch its whole feed.

- **No ads. No tracking.**
- **Phone-first** — built for your phone, in portrait.
- **Light & dark mode** — follows your device, or pick from Settings.

## Features

### Discover
- **Provider tabs** — every provider you turn ON appears as a tab under the search bar; tap a name to switch its whole feed.
- **Two searches** — the Home search bar searches inside the current provider; the Search tab searches all providers at once.
- **Library** — Watching, Plan to Watch, Completed, On-Hold, Dropped.
- **Continue Watching, trending rows** on Home.

### Player
- **Smart-Streams** — plays from the fastest server by default, with your preferred resolution and audio languages; or pick a server yourself.
- **Two players** — choose your player from Settings, with external player support (VLC / MX Player / JustPlayer).
- **Gestures** — double-tap left/right to seek ∓10 s (tap again to seek further), drag to scrub, left side brightness, right side volume, single tap to show/hide controls.
- **Skip Intro, autoplay next episode, Picture-in-Picture** mini player.
- **Subtitles** — download from OpenSubtitles / SubDL, in your language.

### Your stuff
- **Downloads** — download to watch offline, with progress and a badge.
- **Backup & Restore** — back up your whole setup to a file and restore it.
- **Cache control** — video and image caches are capped so they never pile up; one-tap Clear cache in Settings → Player.
- **11 languages** — English, বাংলা, हिन्दी, मराठी, తెలుగు, தமிழ், ગુજરાતી, اردو, ಕನ್ನಡ, ଓଡ଼ିଆ, മലയാളം. Pick one and the whole app switches to it.

## Installation

### What you need
- An Android phone or tablet running **Android 6.0 or newer**.
- About **30 MB** of free space (the APK is ~26 MB — one file works on all phones).
- Permission to install apps from outside the Play Store (your browser will ask once).

### Step 1 — Download the APK
1. Open the [**Releases page**](https://github.com/TheSMandal/zayn-play/releases).
2. Open the latest release (for example `v1.0.5`).
3. Download the APK file (for example `ZaynPlay-1.0.5.apk`).

> You can also get the APK from the latest successful **Actions** run ("Build Zayn Play APK" → the `zayn-play-1.0.5` artifact).

### Step 2 — Install it
1. Open the downloaded APK file.
2. If your phone asks, allow your browser to **"Install unknown apps"** (one-time permission).
3. Tap **Install**, then **Open**.

### Step 3 — First-run setup
The first time the app opens you walk through setup once:
1. **Profile** — your name and picture (optional — you can skip).
2. **Language** — pick your app language.
3. **Extensions** — pick which repositories to install.
4. **Provider Language** — which languages your providers show (English, বাংলা and हिन्दी are on by default).
5. **Media** — which kinds of content to show.
6. **Layout** — how the home screen looks.

You can run setup again any time from **Settings → Redo setup process**.

### Step 4 — Add providers (this is how you get content)
Home starts empty — that is normal. The app only shows content from providers you turn ON, and no repository is built in — you install only the ones you want:
1. Go to **Extensions** (bottom navigation).
2. Add a repository by shortcode or `repo.json` URL — for example **Phisher** (BanglaPlex, MovieBox, YTS and more), **NetMirror** (Netflix, Disney+, Hotstar, Prime Video) or **Megix** (Bollyflix, MoviesDrive, VegaMovies and more).
3. Tap the repository to see its providers, and turn the ones you want **ON**.
4. Go back to **Home** — each provider you turned ON is now a tab under the search bar. Tap a tab to see its feed.

You can add more repositories from any website by shortcode or `repo.json` URL, and test any repository or provider before using it.

### Step 5 — Watch
Tap any title to play it. It starts from the fastest server at your preferred resolution and audio language. Use **Server** in the player to pick a different source, **Subtitles** for subtitles, and **Downloads** to save it for offline.

## Updating

When a new version is released:
- Open **Settings → Updates & Backup** and tap **Check for updates**.
- The update downloads the new APK and opens it to install.

Updates come from this repository's **Releases** — that is the only place the app looks.

## Languages

The whole app — menus, settings, player — is available in 11 languages:

|  |  |  |
|---|---|---|
| English | বাংলা | हिन्दी |
| मराठी | తెలుగు | தமிழ் |
| ગુજરાતી | اردو | ಕನ್ನಡ |
| ଓଡ଼ିଆ | മലയാളം | |

Change it in **Settings → General → App language**. Provider languages and subtitle languages are set separately.

## Built on CloudStream

Zayn Play carries [CloudStream](https://github.com/recloudstream/cloudstream)'s whole system and settings — providers, extensions, downloads, tracking — with its own Zayn Play design on top. CloudStream is open source under the GPL-3.0, and so is Zayn Play.

- **Package:** `app.netlify.zaynplay`
- **Design by:** Samim Mandal
- **Website:** https://zaynplay.netlify.app

## Contributing & issues

Found a bug, or want a feature? Open an [issue](https://github.com/TheSMandal/zayn-play/issues) with what happened, what you expected, and your app version (Settings → Updates & Backup → About). Pull requests are welcome.

## License

Zayn Play is free and open-source software, licensed under the **GNU General Public License v3.0** — see [LICENSE](LICENSE) for the full text. You are free to use, study, change and share it, under the terms of the GPL-3.0.
