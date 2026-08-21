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
- `1509`: battery percentage, voltage, and current.

The former `150D` secondary-speed interpretation was disproved by the first real ride on 2026-08-13. The `1509/9` direct-power field remains a candidate: its strong agreement with `|voltage × current|` is useful cross-field evidence, but not independent physical confirmation.

## SDK-supported but not yet confirmed on the BT638

- Individual battery-cell voltages and battery-change details.
- Actual BT638 availability, GATT status and payloads for the additional standard and proprietary `PROPERTY_READ` characteristics now inventoried by the scanner.
- Rich motor information and temperature.
- Firmware, bootloader, and hardware detail values.
- Motor-tuning access on the expected service.
- Official OTA file formats and actual firmware payloads.
- Soft-charge/storage and richer charge-state settings found in SDK types and strings.

## Next comparisons

1. Export every GATT READ result with service UUID, characteristic UUID, status, raw value, and timestamp.
2. Correlate automatic byte-pattern candidates with the native SDK data-model names.
3. Compare the final GATT/telemetry state before charger-induced BLE shutdown, any brief partial READ/Notify dump after one power-button attempt, and the first state after unplugging/reconnect. Do not assume live BLE remains available during charging, and do not treat an ordinary short reconnect as proof of charging.
4. Capture an official OTA session or cache to recover real firmware headers and formats rather than guessing.
5. Keep all motor-control writes disabled until original values can be read back and verified.

## Safety boundary

Firmware, charging, and motor-control findings are documented for compatibility and inspection. No protection bypass or unverified write packet is considered confirmed.
