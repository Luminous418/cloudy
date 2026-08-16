# Cloudy

OTA client for **LumiROM** series. Checks for updates, downloads the ROM from the configured server and triggers installation in recovery.

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
- **Root service** via libsu (`RootService`) for privileged operations.

## Requirements

- Target ROM must be **A-only** (`a-only` partition layout).
- For install/reboot features: app installed as a **priv-app** with `privapp-permissions` and, on One UI 8.x, an entry in Samsung's **allowlist** (see [ROM integration](#rom-integration)).
- Optional: Magisk module for the SELinux context of OTA staging.

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

## Releases

Workflow [`release.yml`](.github/workflows/release.yml) (manual, via `workflow_dispatch`):

1. Input `name` -> sanitized into a tag (e.g. `Cloudy-2.0`).
2. Builds `assembleRelease` with the keystore decoded from secrets.
3. Generates a changelog from commits since the last tag.
4. Creates the GitHub release and uploads `app-release.apk`.

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

### Build script

On LumiROM builds all the Cloudy integration is handled by a single dedicated script: `scripts/Cloudy.sh` (function `ADD_CLOUDY`), called from `build_local.sh`. It:

- Downloads (and caches) the APK + the two XML files above into `LumiROM/Mods/Cloudy/`, then copies them into the firmware:

  ```
  system/system/priv-app/Cloudy/Cloudy.apk
  system/system/etc/permissions/privapp-permissions-cloudy.xml
  system/system/etc/sysconfig/cloudy-allowed-preload.xml
  ```

- Copies the baked readiness marker and boot init file:
  `system/system/etc/cloudy_ready` and `system/system/etc/init/cloudy.rc` (creates `/cache/recovery` + `/data/media/0/cloudy` at `post-fs-data`).
- Appends the SELinux rules to `system_ext_sepolicy.cil` (same content as the Magisk module's `sepolicy.rule`), so no module is needed on LumiROM.

### Magisk module (optional)

[`magisk-module/`](magisk-module/) grants the SELinux context and prepares the paths (`/data/media/0/cloudy`, `/cache/recovery`) for OTA staging and the reboot to recovery. Compatible with Magisk and KernelSU.

## Structure

```
├── app/                        # App source code (Kotlin, Jetpack, libsu)
├── updater/                    # JSON manifests (a32.json, ...)
├── privapp-permissions/        # Privileged permissions whitelist
├── allowed-preload/            # Samsung system allowlist
├── magisk-module/              # Magisk/KernelSU module for SELinux
├── .github/workflows/release.yml  # Signed release pipeline
└── gradlew / build.gradle.kts  # Gradle build
```

## Support

Report issues in this repo. For the ROM, updates and community: [@LumiROMs](https://t.me/LumiROMs).