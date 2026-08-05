# Split APK verification

- `base.apk`: 6 DEX files, no native libraries.
- `split_config.arm64_v8a.apk`: 8 ARM64 native libraries.
- `split_config.xxhdpi.apk`: density resources.

| Library | Bytes | SHA-256 | Standalone byte-identical |
|---|---:|---|---|
| `libMapViewerDll.so` | 12789768 | `f4c97c1a7260a1b3109f9953d00d5c6f70faaf1a4f433ce1a25491d3d1db4198` | yes |
| `libble-sdk-native-lib.so` | 1433968 | `6050df512a62edb19279d169c0e416a6674ed036d046f5026c6a6512dafe7760` | yes |
| `libbugsnag-ndk.so` | 1150192 | `b0049b240ece72d799bd8900ef19b5ffd2a3a009bad4ca73c746fc2770b85702` | yes |
| `libbugsnag-plugin-android-anr.so` | 13656 | `2a145c6fd8c75db3ca64d1a4ce5b1220a1f1019a34dd11cd8fad6390640460ba` | yes |
| `libbugsnag-root-detection.so` | 5016 | `b121e17b36f93a13796b90f659124cc26dc03c473689b24bab717f650567ba3a` | yes |
| `libdatastore_shared_counter.so` | 7112 | `d3e48717c9aa147e0ab21063ba0e8e0211cabf8bf40b222640829519edbf58e1` | yes |
| `libnative-lib.so` | 17800 | `be2e8af6cbe518e5b77928620ce6a3fba2318d437a878ab2b4e74e06d74d559d` | yes |
| `libutil.so` | 1083272 | `815d0a0625eb69e51178fedf95d2e1ed15034698249fb3986e426c0017f82053` | yes |

All eight standalone libraries are byte-for-byte identical to the corresponding files in the ARM64 split. This proves their exact origin from this VMAX app build.