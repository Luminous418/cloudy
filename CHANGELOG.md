# Changelog

All notable changes to the Cloudy OTA helper.

## [2.31] - 2026-08-19

### Added
- **Localization**: the app is now fully translated and follows the system language — Spanish, French, Portuguese, German, Italian, Russian and Vietnamese (plus the English default). Covers every screen, dialog and error message.

### Changed
- All hardcoded user-facing strings extracted to `values/strings.xml` and referenced from resources, making future translations and maintenance easier.

## [2.3] - 2026-08-18

### Added
- **Self-updater**: Cloudy checks its own `updater/app.json` manifest, shows the installed/available version on the Settings header and can download and install new app versions.
- **Update notifications**: background checks with a configurable interval (hourly / 8h / 12h / daily / weekly / monthly) that notify when a new LumiROM or Cloudy build is available.

### Changed
- Settings tab header now shows the Cloudy version with a "Check for app updates" action.
- Notifications moved to a dedicated notification channel.

## [2.22] - 2026-08-17

### Changed
- Floating bottom navigation: the tab bar now floats with a fixed gap above the content and the content's bottom edge stays rounded while scrolling, so the separation between content and bar no longer changes or becomes straight when the large title collapses.
- Tab press flash (recoil/touch feedback) now matches the floating pill: same corner radius, slightly smaller than the bar, and the correct ripple color in light mode.

## [2.21] - 2026-08-17

### Added
- Settings tab: **Credits** dialog (with app/ROM credits and Telegram link) and **Advanced settings** screen (Update source + Maintenance), opened with a subtle slide/fade animation.
- Subtle fade animation when switching bottom tabs (no frame overlap between tabs).
- Maintainer avatar is now cached on disk (`filesDir/avatars/`) and reused across launches, including offline via the stored avatar URL.

## [2.2] - 2026-08-16

### Added
- **Flash ROM from storage**: pick a ROM zip from internal storage, confirm, and the app stages it into recovery (`/data/media/0/cloudy/`), writes the recovery automation files and reboots to apply it.

### Fixed
- Crash on flash (`ExceptionInInitializerError`): the libsu main shell was created before `RootManager` configured its builder. Preload now goes through `RootManager.preload()`.
- Raw partition resolution on dynamic-partition (super) devices: `/dev/block/mapper/<name>` is now checked before `by-name`.
- Recovery staging now also writes TWRP's `/cache/recovery/openrecoveryscript` (TWRP ignores the stock `command` file), so the flash actually runs on TWRP recovery.

## [2.1] - 2026-08-16

### Added
- Support for the baked-ROM readiness marker (`/system/etc/cloudy_ready`): the ROM can provide Cloudy's SELinux rules and staging dirs without a Magisk module.

### Changed
- Update repo moved to `Luminous418/cloudy`.