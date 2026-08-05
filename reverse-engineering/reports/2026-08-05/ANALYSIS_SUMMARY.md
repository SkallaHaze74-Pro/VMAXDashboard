# VMAX reverse-engineering inventory and first-pass analysis

## Scope
This report covers the files uploaded on 2026-08-05:

- `libble-sdk-native-lib.so`
- `libbugsnag-ndk.so`
- `libbugsnag-plugin-android-anr.so`
- `libbugsnag-root-detection.so`
- `libdatastore_shared_counter.so`
- `libMapViewerDll.so`
- `libnative-lib.so`
- `libutil.so`
- VMAX base APK `V125345`

Hashes and exact sizes are in `inventory/files.tsv`.

## Strong findings from `libble-sdk-native-lib.so`

The BLE SDK library contains native protocol handlers and JNI exports for:

- battery information, battery replacement/capacity data and battery-change events;
- total-trip information;
- battery-cell updates;
- firmware IDs, firmware versions, hardware summaries and extended firmware data;
- OTA package download, upload, chunking, execution and progress callbacks;
- motor-tuning reads, writes and resets;
- charge-state and soft-charge/storage-mode data models.

Concrete motor-tuning terms found in the binary include:

- `MaxSpeed`
- `SpeedCut`
- `AssistFactor`
- `DynamicFactor`
- `BrakeCombined`
- `BrakeStatic`

Concrete battery/charging terms include:

- `StateOfCharge`
- `ChargeState`
- `BatterySerialNumber`
- `ExtendedBatterySerialNumber`
- `MainBatterySoftChargeMode`
- `ExtendedBatterySoftChargeMode`
- `MainBatteryStorageMode`
- `ExtendedBatteryStorageMode`

The library also contains `BootloaderVersion`, firmware/hardware summary handling and a complete OTA manager. This proves the generic SDK supports these concepts. It does **not** by itself prove that the BT638 exposes every feature or that every characteristic is readable on this model.

## BLE UUID family

The SDK contains the `DA1A15xx` telemetry family and `DA1A16xx` motor-tuning/firmware family. The exact extracted UUID list is stored in `reports/ble_uuids.txt`.

Notable entries include `1500` through several `15xx` characteristics and `1600`, `1601`, `1602`, `1605`, `1607`, `1608`, `160A`, `160B`, `160C`, and `160D`.

## APK notes

The uploaded APK contains six DEX files and application resources, but no native `.so` files in its archive. The native libraries therefore appear to have come from split APKs / extracted application storage rather than this base APK. To reproduce the complete installed package, the architecture split APK (especially `split_config.arm64_v8a.apk`) is still useful.

## Files with low VMAX protocol relevance

The Bugsnag libraries are crash/ANR/root-detection components. `libdatastore_shared_counter.so` is an AndroidX datastore support library. They are retained in the inventory but are unlikely to contain scooter protocol decoding.

`libMapViewerDll.so` is large and appears related primarily to maps/navigation. `libutil.so` and `libnative-lib.so` need further targeted review, but the strongest BLE/firmware evidence is currently in `libble-sdk-native-lib.so` and the application DEX files.

## Safety and confidence convention

- **Confirmed SDK capability:** directly present as native symbol/string/model in the supplied binary.
- **Confirmed BT638 field:** observed repeatedly in the user's BLE recordings and independently matched to scooter behavior.
- **Candidate:** plausible but not yet uniquely mapped.
- **Unsupported/unseen:** SDK may support it, but BT638 has not exposed it in current captures.

No write packet should be generated solely from a string or symbol name. Writes require a verified frame format, original-value backup, stillstand checks and successful read-back.

## Missing or helpful additions

For a complete installed-app archive and deeper static analysis, the most useful missing items are:

- `split_config.arm64_v8a.apk` (or the complete APKS/XAPK bundle);
- any other split APKs (`xxhdpi`, language, feature splits);
- `source.tar.gz.0`, `meta_v5.am.json`, `info_v5.am.json`, `misc.am.tsv`, `checksums.txt` if they belong to this exact app version;
- firmware/OTA files captured from app cache during a real update;
- fresh charging and indicator recordings from VMAXDashboard 7.3.
