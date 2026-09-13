# WireGuard validation

Date: 2026-09-13

Code imported in commit `fa687168f3d012fc3ef2eb5050f7c7f5c7643c14`, based on upstream `f86d4aeef21de4524a986c4ae87ac5f58ac42d25`.

## QR import and TV input fix (2026-09-13)

- Replaced TV text fields with the Xtream-style phone QR flow: paste text or
  select a `.conf` file on the phone. Local file selection remains optional.
- VPN dialogs no longer stack or apply the manual 240-pixel D-pad scroll jump.
  Focus navigation and scroll-to-focused-control handle the VPN dialog body.
- Local `assembleDebug` and targeted VPN tests succeeded: **18 tests**, no failures
  (9 configuration policy tests and 9 real loopback HTTP server tests).
- Server tests cover exact UTF-8 and key-character transfer, tokens, invalid
  configuration retry, byte limits, single-use success, cancellation and expiry.
- `node scripts/test-wireguard-phone.cjs` passes: actual shipped JavaScript handlers
  exercise file selection, paste, rejection/retry, clearing after success and limits.
  This uses DOM adapters, not a real mobile browser.
- A browser rendering test could not run: Chromium was unavailable and its download
  did not complete. No browser rendering or real Fire TV remote-control test is claimed.
- `graphify update .` could not run because graphify is not installed here.

The older checks below describe the initial version. The real-device checks still
apply to this fix, including importing a genuine Proton profile and connecting.

## Initial version: completed locally

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
