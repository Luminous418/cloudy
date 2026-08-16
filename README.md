# Cloudy

OTA client for **LumiROM** on the Samsung Galaxy A32 4G (`a32`, A-only). Checks for updates, downloads the ROM from the configured server and triggers installation in recovery.

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
https://raw.githubusercontent.com/Luminous418/cloudy-app/refs/heads/main/updater/<codename>.json
```

Schema:

```jsonc
{
  "rom_name": "LumiROM",
  "maintainer": {
    "name": "Lumi",
    "handle": "@Luminous418",
    "device": "Samsung Galaxy A32 4G",
    "codename": "a32",
    "avatar_url": "https://.../avatar.png",   // optional
    "telegram": "https://t.me/LumiROMs",       // optional
    "donate_url": "https://paypal.me/aerocat"  // optional
  },
  "releases": [
    {
      "version": "8.6.3",
      "version_code": 80603,                    // compared against ro.cloudy.rom.ver.code
      "build_date": "2026-08-11",
      "android_version": "16",
      "oneui_version": "80500",                 // "80500" -> "8.5" in the UI
      "security_patch": "2026-08-01",
      "build_fingerprint": "samsung/a32x/...",
      "device_model": "SM-A325F",
      "kernel_version": "5.10.x",
      "partition_layout": "a-only",
      "changelog": ["line 1", "line 2"],
      "download": {
        "url": "https://.../LumiROM_8.6.3.zip",
        "filename": "LumiROM_8.6.3.zip",
        "size_bytes": 2147483648,
        "sha256": "0123...abcd",                // optional but recommended
        "install_type": "recovery_zip"          // "recovery_zip" | "raw_image"
      }
    }
  ]
}
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

[`LumiROM/scripts/Mods.sh`](https://github.com/Luminous418/LumiROM) already downloads and integrates the three files from this repo (APK from the latest release, whitelist and allowlist from `main`) into the baked ROM:

- `system/system/priv-app/Cloudy/Cloudy.apk`
- `system/system/etc/permissions/privapp-permissions-cloudy.xml`
- `system/system/etc/sysconfig/cloudy-allowed-preload.xml`

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