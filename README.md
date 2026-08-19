<p align="right">
  <img align="right" height="130" src="art/icon.svg" alt="Cloudy"/>
</p>

<img src="art/title.svg" height="70" alt="Cloudy"/>
<br><br>

OTA client for **LumiROM** series. Checks for updates, downloads the ROM from the configured server and triggers installation in recovery. It also updates **itself** from a JSON manifest and can notify you about new app and ROM releases.

> Current integration status: the app installs as a **system priv-app** inside the ROM, with its privileged permissions and its entry in Samsung's *allowlist*. The whole pipeline (build, signing, release, ROM integration) lives in this repo.

---

## Features

- **Update checks** against a JSON manifest (configurable URL in Settings).
- **Multi-release selector**: list of published versions, pick which one to view/download.
- **Maintainer card** with avatar, contact handle and donation link.
- **Per-release tech sheet**: build date, Android version, **One UI version**, security patch level, build fingerprint, device model, kernel version, partition layout and changelog.
- **Device One UI version** (read from `ro.build.version.oneui`).
- **Robust download** with progress bar, resume support and **SHA-256** verification.
- **Installation**: downloads to `/data/media/0/cloudy/`, stages the OTA and **reboots to recovery** (requires `REBOOT` + `RECOVERY`, available as a priv-app).
- **Flash ROM from storage**: pick a local zip/raw image and stage it via recovery (TWRP).
- **Self-update**: Cloudy updates itself from [`updater/app.json`](updater/app.json) using the same download + SHA-256 pipeline as ROMs, handed to the system installer — no root needed.
- **Background update notifications**: a silent check for new Cloudy *and* LumiROM releases runs on every app open plus a periodic background check (configurable interval), alerting with a heads-up notification.
- **Root service** via libsu (`RootService`) for privileged operations.
- **One UI 8 Settings screen**: app info header (icon, name, version) with an in-place self-update check pill, credits and advanced settings, rendered with the SESL OneUI preference fork.

## Requirements

- Target ROM must be **A-only** (`a-only` partition layout).
- For install/reboot features: app installed as a **priv-app** with `privapp-permissions` and, on One UI 8.x, an entry in Samsung's **allowlist** (see [ROM integration](#rom-integration)).
- Optional: Magisk module for the SELinux context of OTA staging.
- **Self-update and update notifications need no root or priv-app privileges** — they only use `REQUEST_INSTALL_PACKAGES` (system installer, user-approved) and `POST_NOTIFICATIONS` (runtime, Android 13+).

## Building

Requirements: JDK 17+ and Android SDK (build-tools 36).

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleDebug        # debug APK
./gradlew assembleRelease      # signed release APK
```

The release build uses your own keystore. Create `keystore.properties` in the repo root (it is in `.gitignore`):

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=cloudy
keyPassword=...
```

If `keystore.properties` is missing, the release build falls back to debug signing (useful for CI/development).

## JSON manifest

The app reads a manifest by default from:

```
https://raw.githubusercontent.com/Luminous418/cloudy/refs/heads/main/updater/<codename>.json
```

Notes:
- `oneui_version` is the raw value of `ro.build.version.oneui` (`80500`); the app formats it as `8.5` (the fifth digit is dropped when `0`).
- `releases` may be ordered newest to oldest; the app marks the installed version.

### Self-update manifest (`app.json`)

Cloudy's own update metadata lives in [`updater/app.json`](updater/app.json) (the URL is fixed, not configurable). It mirrors the ROM manifest's `download` object so the same pipeline applies:

```json
{
  "app_name": "Cloudy",
  "version": "2.22",
  "version_code": 6,
  "changelog": ["..."],
  "download": { "url": "...", "filename": "Cloudy_2.22.apk", "size_bytes": 0, "sha256": "...", "install_type": "apk" }
}
```

The check button is the pill in the Settings app info header. When a newer version exists, Cloudy downloads the APK, verifies its SHA-256, and hands it to the **system installer** (`ACTION_VIEW` via a FileProvider URI — requires `REQUEST_INSTALL_PACKAGES`).

## Background update notifications

A global **Update notifications** switch in Settings (default on) enables silent checks for new Cloudy *and* LumiROM releases. Anything newer than what's installed triggers a notification; an up-to-date app/ROM stays quiet.

- **When**: on every app open, plus a periodic background check via a Doze-friendly `AlarmManager` alarm. The interval is configurable in **Advanced settings → Check interval**: every hour, 8 hours, 12 hours, day (default), week or month.
- **Reboots**: the alarm is re-armed on boot (`RECEIVE_BOOT_COMPLETED`).
- **Tap target**: an app update opens the Settings tab; a ROM update opens the Update tab.
- **Pop-ups**: notifications use a dedicated `update_notifications` channel at alert importance, so they appear as heads-up pop-ups while the screen is on. On Android 13+ the runtime `POST_NOTIFICATIONS` permission is requested when the toggle is enabled.
- Works fully without root; when running as a priv-app it needs no special allowlist entry for these features.

## Releases

Workflow [`release.yml`](.github/workflows/release.yml) (manual, via `workflow_dispatch`):

1. Input `name` -> sanitized into a tag (e.g. `Cloudy-2.22`).
2. Builds `assembleRelease` with the keystore decoded from secrets.
3. Generates a changelog from commits since the last tag.
4. Creates the GitHub release and uploads `app-release.apk` to a rolling `latest` release.
5. Regenerates **`updater/app.json`**: reads `versionCode`/`versionName` from `build.gradle.kts`, computes the APK's SHA-256 and size, fills the changelog from `git log`, and commits + pushes it — so the self-update and the update notifications always point at the freshly released APK.

Required secrets:

| Secret | Description |
| --- | --- |
| `RELEASE_KEYSTORE` | `release.jks` as base64 |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_PASSWORD` | key password (same as keystore on JDK 17+ builds) |

> The key alias is `cloudy` and is hardcoded in the workflow (validated with `keytool -list -alias cloudy`).

## ROM integration

For Cloudy to install as a system app on a Samsung ROM (One UI 8.x / Android 16), three pieces are required:

1. **APK in priv-app**

   ```
   /system/priv-app/Cloudy/Cloudy.apk
   ```

2. **Privileged permissions whitelist** -> [`privapp-permissions/privapp-permissions-cloudy.xml`](privapp-permissions/privapp-permissions-cloudy.xml)

   ```
   /system/etc/permissions/privapp-permissions-cloudy.xml
   ```

   Grants `android.permission.REBOOT` and `android.permission.RECOVERY`. Without this, since Android 9 the app is silently dropped.

3. **Samsung system allowlist** -> [`allowed-preload/cloudy-allowed-preload.xml`](allowed-preload/cloudy-allowed-preload.xml)

   ```
   /system/etc/sysconfig/cloudy-allowed-preload.xml
   ```

   **Important (classic One UI 8.x pitfall):** Samsung will not install any package that is not in its `allowed-system-preload-apps.xml` list. PM drops it with `Package is not in allowed list : <pkg>` and leaves no trace in `packages.xml`. This extra file is what lets the app install. Without it, the app looks correctly placed and signed but never shows up as installed.

## Support

Report issues in this repo by using the Issues tab or clicking this [link](https://github.com/Luminous418/cloudy/issues)
