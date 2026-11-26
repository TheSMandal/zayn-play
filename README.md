# Zayn Play — UI-only build (v0.1.0)

A **fresh Android app with ONLY Zayn Play's own layout** — no old-engine
code, no old UI, no engine (no providers, no real player, no
downloads). Every screen is the approved preview, with sample data only,
exactly like the preview.

**Purpose (gated restart, user 2026-11-26):** build the app with only his
layout first. He reviews everything on his phone; if he says OK, the
the streaming engine goes into this UI afterwards. Benefits he named: the
app stays small and there are no useless files in the project.

- Package: `app.netlify.zaynplay` — app name **Zayn Play**
- Version: **0.1.0 (versionCode 1)** — a UI-review build, never to be released
- Min SDK 24, target/compile SDK 34, Kotlin 1.9.24, AGP 8.5.2, Gradle 8.7

## How the UI is built

The UI is the approved preview
(`~/workspace/your_files/zayn-play-preview/zayn-play-preview.html`),
extracted into one self-contained page,
`app/src/main/assets/zaynplay-app.html` (4.5 MB):

- the preview's **one phone** (dark phone) — splash, 6-step setup, all
  19 screens, bottom tab bar, provider picker sheet, server sheet,
  subtitle sheet, toasts, PiP — with its CSS and its app JavaScript
  (script 1) plus the 11-language dictionary engine (scripts 3–4);
- the **light-phone duplicate** (script 2, `l*` functions) is dropped —
  the single phone switches Dark/Light itself via the theme classes;
- the preview-page chrome (section headers, screen switcher navs,
  captions, the light-slot clone) is dropped;
- app-only additions (marked in the file): theme + language + setup
  persistence in `localStorage`, `?bootsplash=0` (the native splash
  already showed), and the `window.ZaynApp.goBack()` bridge for the
  Android back button.

The native shell is tiny on purpose: `SplashActivity` (Splash #13
"Bokeh Nights", native, dark/light forms) → `MainActivity` (one
full-screen WebView). Back goes: close a sheet → Home → leave the app.
External sample links open in the browser. Fonts (Hind Siliguri /
Montserrat / Sora) load from Google Fonts when online and fall back to
system fonts offline — the app needs INTERNET only for those fonts.

## Screens in this build (all from the preview, sample data only)

Splash, Setup (6 steps: Profile, Language, Extensions, Provider
Language, Media, Layout), Home, Search, Provider search (search inside
one provider), Details, Player (glass UI demo — no real video),
Library, Downloads, Settings, and every Settings sub-screen: General,
App language, Player, Playback & streams, Layout, App theme, Providers,
Extension languages, Extensions, Updates & backup, Accounts. Plus the
provider picker (glass bottom sheet) and the server/audio/subtitle
sheets in the player.

Theme: System (default, follows the device) / Dark / Light, from
Settings → Layout → App theme — the same three options as the preview.
App language: the same 11 languages, same engine, same no-mixing rule.

## What is NOT in this build

No providers, no extensions/repo loading, no search backend, no video
playback, no downloads, no accounts sync, no updater. Buttons that
would need the engine (Play, Install, Test, Download, Search) show the
preview's sample behaviour only. The old engine's name was removed
from the preview text itself (162 mentions replaced, verified zero in
the shipped page) — the sample repo/provider names in the demo data
are Zayn Play's own sample names.

## Building

CI (parent arranges): standard Gradle build, JDK 17,
`./gradlew :app:assembleDebug` (or `assembleRelease` for the signed
review APK — signing is arranged by the parent; no signing config is
in this repo). Local: same command with JDK 17.

## After approval (engine phase — NOT this build)

Wire the streaming engine's backend behind this UI exactly the way that
engine works (MainAPI/providers, player, downloads, search-all-providers,
extensions), keeping the layout in front untouched. Reference clone:
the read-only engine clone kept in the workspace (its path is in the
project's memory notes, not in this repo). The old patched
project (`~/workspace/zaynplay-build/zaynplay`) was deleted by the
user's order and must not be restored.

## Known limits of this review build

- Portrait only (the UI is designed as a portrait phone).
- WebView UI: the review is about the layout, not native performance.
- No on-device run was possible here (no Android emulator in this VM);
  the parent verifies the built APK.
