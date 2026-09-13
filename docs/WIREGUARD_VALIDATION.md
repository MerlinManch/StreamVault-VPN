# WireGuard validation

Date: 2026-09-13

Code imported in commit `fa687168f3d012fc3ef2eb5050f7c7f5c7643c14`, based on upstream `f86d4aeef21de4524a986c4ae87ac5f58ac42d25`.

## Completed locally

- Android Gradle build: `:app:assembleDebug` — successful.
- Targeted Gradle tests: `:app:testDebugUnitTest --tests 'com.streamvault.app.vpn.*'` — 9 tests, 0 failures, 0 errors, 0 skipped.
- APK signature verification with Android `apksigner` — valid APK Signature Scheme v2 signature.
- Packaged WireGuard native libraries verified for `armeabi-v7a` and `arm64-v8a`.
- Manifest and English/German resources parsed; all 32 new resource keys match.
- WireGuard patch checked against the pinned upstream source before import.
- Application source and resources in GitHub checked against the local build using Git blob hashes. The upstream's ignored historical build logs are not part of the imported source commit.

The build emits upstream compiler/deprecation and D8 Kotlin-metadata warnings, but completes successfully. Existing player/provider source and upstream dependency versions were preserved.

## Still requires a device

Actual Fire TV / Android TV installation, remote-control navigation, VPN consent, server handshake, IP/DNS routing, reconnect/revoke behavior and IPTV playback through a real VPN have not been exercised here. No user WireGuard credentials or IPTV provider credentials were supplied. See the device checklist in [WIREGUARD.md](WIREGUARD.md).

This is a debug test build, not a device-validated production release. Release signing and release shrinking were not tested.

The GitHub Actions run provides the independently built APK and its own test report: [StreamVault VPN APK builds](https://github.com/MerlinManch/StreamVault-VPN/actions/workflows/wireguard.yml).
