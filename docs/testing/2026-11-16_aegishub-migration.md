# 2026-11-16 AegisHub migration acceptance

## Scope

- Removed TinaServer endpoints, signed server-config synchronization, and MessagePack serialization.
- Replaced favorite cloud synchronization with the existing local Room repository. Upgrade cleanup cancels persisted legacy WorkManager jobs and removes non-empty legacy endpoint preferences without deleting local favorites or download history.
- Routed startup counts, host crashes, diagnostics archives, and feedback through the configured AegisHub endpoint. Empty AegisHub configuration disables reporting rather than falling back to the legacy service.
- User-program and component crash screens do not gain host restart or upload actions from a legacy tag alone.

## Verification

- `:core:network:testDebugUnitTest --tests '*aegis.*'`: 8 tests passed.
- `:core:crash:testDebugUnitTest`: 22 tests passed.
- `:core:logging:testDebugUnitTest`: 34 tests passed.
- `:core:database:testDebugUnitTest`: 6 tests passed.
- `:feature:settings:testDebugUnitTest`: 143 tests passed.
- `:app:testArm64DebugUnitTest --tests CrashPresentationPolicyTest --tests CrashActivityIntentTest`: 9 tests passed.
- `:app:testArm64DebugUnitTest --tests LegacyServerCleanupTest`: 2 tests passed; 224 tests across the listed focused suites.
- Clean checkout at `5a696d619`: `:app:assembleArm64Release` passed with `-Ptina.autoIncrementReleaseVersion=false`; the three stale `synced` constructor arguments were removed in that commit. Distribution policy, ARM64 ELF, APK v2 signature, and lint vital checks passed.
- `:app:compileArm64ReleaseAndroidTestKotlin -Ptina.instrumentationBuildType=release`: passed. Instrumentation was compiled but not executed on the physical device.
- `py tools/i18n/check_all.py` and `py tools/checks/check_direct_file_operations.py`: passed.
- `:app:assembleArm64Release`: passed as TinaIDE `0.19.4` (`versionCode=19052`). Distribution policy, ARM64 ELF, and APK v2 signature checks passed.
- AegisHub health endpoint returned HTTP 200. Synthetic diagnostic report acceptance and retry returned the same report ID; repeating the same ZIP attachment returned the same attachment ID and SHA-256. A synthetic internal session was accepted with status `telemetry`.

## Device state

The previous wireless ADB connection to the physical `PMS110` at `192.168.2.185:39375` disappeared before installation. Subsequent `adb devices` snapshots showed only an x86_64 emulator, and TCP connectivity to the remembered wireless ADB port failed. The emulator advertises `x86_64,arm64-v8a`, but installation of the signed ARM64 APK was rejected because the existing TinaIDE package has a different signing certificate (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). It was not uninstalled, preserving emulator app data. The APK was therefore not installed on, or verified on, the physical phone; keep this as an explicit acceptance gap.

## Release artifact

- `app/build/outputs/apk/arm64/release/TinaIDE-0.19.4-arm64-v8a-release.apk`
- SHA-256: `07EF4E84E83D3FC2366CE6219AC90DB22E496A98D0555835D57F64341FED3308`
- Signature certificate SHA-256: `e1ef5c24ca8349376e6f3ebf0d9b93a7c44c20d7bb9ae2144f053abb649fd45c`
- Build ID: `5a696d619b-20261116092037`

The production AegisHub check used synthetic diagnostic data. Its accepted report was `6cf87cab-48f0-4be8-89e3-30b711923674`; it can be removed from the console if the acceptance record should not remain visible.
