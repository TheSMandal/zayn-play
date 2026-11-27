# Zayn Play

<p align="center"><b>ONE APPS EVERY UNIVERSE</b></p>

Zayn Play is Samim Mandal's own streaming app — movies, series, anime and live TV, in one place. Watch from providers you install, switch between them from the tabs on top, and search everything at once.

**Current version: 1.0.3 (versionCode 1003)** — [Download the APK](https://github.com/TheSMandal/zayn-play/releases)

## How it works

Zayn Play is an extension-based streaming app. The app itself provides no video sources — content comes from providers (extensions) installed from repositories. Install a repository, turn its providers ON, and they appear as tabs on the home screen. Tap a provider's name to switch its feed. Search searches all installed providers at once.

- Package: `app.netlify.zaynplay` — app name **Zayn Play**
- Design: Zayn Play's own UI (dark red, NetMirror-style) on every screen
- Engine: the CloudStream streaming engine runs behind the UI (providers, player, downloads, search)
- App language: 11 languages (English, বাংলা, हिन्दी, मराठी, తెలుగు, தமிழ், ગુજરાતી, اردو, ಕನ್ನಡ, ଓଡ଼ିଆ, മലয়ാളം)
- Player: fastest server by default, manual server choice, resolution + audio language, external player support

## Building

CI builds the APK on every push (GitHub Actions, workflow_dispatch): JDK 17, `./gradlew :app:assembleDebug`. Releases carry the signed APK. Local build: same command with JDK 17.

## License

GPL-3.0 (the streaming engine is GPL-3.0; this app stays open source).
