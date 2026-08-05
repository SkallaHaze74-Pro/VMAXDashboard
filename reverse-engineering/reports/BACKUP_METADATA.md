# App Manager backup metadata

## Installation

- Package: `com.gpstuner.vmax`
- Version code: `125345`
- Architecture: ARM64
- Split APKs: `split_config.arm64_v8a.apk`, `split_config.xxhdpi.apk`
- Installer: Google Play
- Backup encryption: none
- Checksum algorithm: SHA-256

## Permissions exported in `misc.am.tsv`

- Fine and coarse location: granted
- Bluetooth scan and connect: granted
- Notifications, Bluetooth advertise, external storage: not granted
- Battery optimization stub: false

## AppOps exported in `rules.am.tsv`

```text
com.gpstuner.vmax	74	APP_OP	1
com.gpstuner.vmax	94	APP_OP	1
com.gpstuner.vmax	116	APP_OP	1
```

The backup stores numeric AppOps IDs only, so no human-readable operation names are asserted here.

## `data0.tar.gz.0`

The archive is valid gzip/tar but contains only an empty `files/` directory. It adds no databases, preferences, cached firmware, login state, or other application data.