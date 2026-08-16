# Changelog

All notable changes to the Cloudy OTA helper.

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