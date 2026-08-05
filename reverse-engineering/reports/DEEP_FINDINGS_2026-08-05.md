# Deep reverse-engineering findings

## Provenance

- `source.tar.gz.0` contains the full three-part installation: base APK, ARM64 split, and xxhdpi split.
- All eight standalone `.so` files exactly match the corresponding binaries inside the ARM64 split.
- The long-named APK is the base APK: six DEX files and no native libraries.
- `data0.tar.gz.0` is empty apart from the `files/` directory.

## High-confidence SDK capabilities

Direct native symbols in `libble-sdk-native-lib.so` prove implementation of:

- GATT discovery, subscription handling, read/write callbacks, MTU handling, and communication restart.
- Motor-tuning read, write, and reset with profiles, values, result callbacks, and completion callbacks.
- Battery information, battery-cell updates, battery-change records, and max-capacity handling.
- Motor information, motor updates, and total-trip parsing.
- Firmware ID, firmware version/status, hardware summaries, receiver firmware information, and firmware-data parsing.
- A complete OTA manager: firmware lookup, local-file checks, header/chunk transfer, progress, preprocess/postprocess, restart, failure handling, and manual OTA checks.

These findings prove SDK capability. They do not prove that the BT638 exposes every capability.

## Confirmed on the user's BT638 through live BLE data

- `1505`: speed field.
- `1506`: odometer.
- `1508`: light and ECO/SPORT.
- `1509`: battery percentage, voltage, current, and direct power.
- `150D`: secondary speed source.
- Many additional standard and proprietary GATT READ characteristics are visible to the new scanner.

## SDK-supported but not yet confirmed on the BT638

- Individual battery-cell voltages and battery-change details.
- Rich motor information and temperature.
- Firmware, bootloader, and hardware detail values.
- Motor-tuning access on the expected service.
- Official OTA file formats and actual firmware payloads.
- Soft-charge/storage and richer charge-state settings found in SDK types and strings.

## Next comparisons

1. Export every GATT READ result with service UUID, characteristic UUID, status, raw value, and timestamp.
2. Correlate automatic byte-pattern candidates with the native SDK data-model names.
3. Compare complete GATT snapshots before charging, during charging, after a power-button attempt, and after unplugging.
4. Capture an official OTA session or cache to recover real firmware headers and formats rather than guessing.
5. Keep all motor-control writes disabled until original values can be read back and verified.

## Safety boundary

Firmware, charging, and motor-control findings are documented for compatibility and inspection. No protection bypass or unverified write packet is considered confirmed.