# MovieBox — Web Streaming App & Android WebView

A responsive modern cinematic streaming web platform and companion native Android WebView application configured for MovieBox media streaming, HTML5 fullscreen video playback, multi-mirror switching, and offline resilience.

---

## 🌐 1. Running the Web Application Locally (`npm run dev`)

The web version is built with **Vite**, modern **JavaScript (ES Modules)**, and **CSS Design System** matching MovieBox's dark cinematic visual aesthetic.

### Quick Start:
```bash
# 1. Install dependencies (if not already installed)
npm install

# 2. Start the local development server
npm run dev
```

The web application is now active at:
- **Local:** [http://localhost:3000/](http://localhost:3000/)
- **Network:** `http://<your-local-ip>:3000/` (open on your phone, tablet, or smart TV on the same Wi-Fi!)

### Web Features:
- **Cinematic Billboard / Hero Section**: Blockbuster spotlight with trailer streaming, metadata, and quick action buttons.
- **Feeds & Categories**:
  - 🔥 Trending Now (Ranked 1-10)
  - 🍿 Blockbuster Movies
  - 📺 Binge-Worthy TV Series
  - 🥊 FightZone & Live Combat Sports
  - ✨ Anime Universe
  - 🌍 Nollywood & African Cinema
- **Instant Search**: Real-time filtering by title, genres, and cast (shortcut: `/`).
- **Interactive Video Player Modal**: HTML5 streaming player with server switching (Server 1 Fast CDN, Server 2 Alpha 4K, Server 3 Mirror), theater view, and quality tags.
- **My Watchlist**: Saved to `localStorage` with slide-in drawer management.
- **Mirror Switcher**: Live ping and toggle between MovieBox CDN gateways (`h5.aoneroom.com`, `themoviebox.org`, `movieboxonline.net`).
- **Direct HD Download**: One-click download button for offline playback.

---

## 📱 2. Android WebView Application

A native Android application wrapping the MovieBox streaming portal.

### Run in Android Studio:
1. Open this workspace in **Android Studio** (`/Users/djshirleybeats/Desktop/Moviebox`).
2. Click **Sync Project with Gradle Files**.
3. Select your device or the `Pixel_4a` emulator and click **Run**.

### Run from Terminal:
```bash
# Build Debug APK
./gradlew assembleDebug

# Install and launch on emulator/device
./gradlew installDebug
~/Library/Android/sdk/platform-tools/adb shell am start -n com.moviebox.webview/.MainActivity
```

Pre-built APK path:
`app/build/outputs/apk/debug/app-debug.apk`

---

## Project Structure

```
Moviebox/
├── index.html              # Web App Entry HTML
├── vite.config.js          # Vite Server Configuration (Port 3000)
├── package.json            # Web Dependencies & Scripts
├── src/
│   ├── main.js             # Web Streaming Controller & State
│   ├── moviesData.js       # Media Catalog & Streaming Feeds
│   └── style.css           # Cinematic CSS Design System
├── app/                    # Native Android WebView App Module
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/moviebox/webview/
│       │   ├── MainActivity.kt
│       │   └── VideoWebChromeClient.kt
│       └── res/
├── gradlew
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 🚀 Releasing updates (auto-update for installed apps)

Installed apps (version 1.3 and newer) check this repo's **latest GitHub Release** when they open
(at most every 3 hours). If the release is newer, users see **"Update X available"** → **Update now**,
then Android asks them to confirm the install.

### One-time setup: add signing secrets
GitHub → repo **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | output of `base64 -i moviebox-release.jks \| pbcopy` (copies it) |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `moviebox` |
| `KEY_PASSWORD` | key password |

> ⚠️ Never commit `moviebox-release.jks` or `keystore.properties` (both are git-ignored).
> Every update **must** be signed with this same key or it won't install over the old app.

### Publish a new version
```bash
# 1. Set the new version in app/build.gradle.kts  →  System.getenv("APP_VERSION") ?: "1.4"
git commit -am "Update 1.4: what changed"
git tag v1.4
git push origin main --tags
```
GitHub Actions builds the signed APK and publishes **Update 1.4**. Done — users get prompted.

Edit the Release description on GitHub to change the "What's new" text shown in the app.

### Without GitHub Actions
Build locally (`./gradlew :app:assembleRelease`), then on GitHub → **Releases → Draft a new release**,
tag `v1.4`, attach `app/build/outputs/apk/release/app-release.apk`, and publish.
