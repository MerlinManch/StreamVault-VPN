# VPN controls (2026-09-14)

## Fire TV controls

Open Settings → Privacy → WireGuard VPN. Each switch is one D-pad focus target;
press OK to toggle it. Focusing a row never toggles it or opens a keyboard.
All three settings default to off and persist on this device.

- **VPN bei App-Start verbinden**: use the selected startup profile whenever the
  main activity returns to the foreground and no tunnel is running. Choose a
  profile with **Für Autostart auswählen**. Manual connection also selects it.
  Removing that profile disables automatic startup.
- **VPN Kill-Switch**: block the shared IPTV/API, playback, image, recording and
  download HTTP client until the app VPN is confirmed. This also covers startup,
  denied VPN permission, connection failure, manual disconnect and tunnel loss.
  Buffered/local content is not itself a provider connection.
- **VPN-Status im App-Menü**: a button in the top bar says **VPN Verbunden** or
  **VPN Getrennt** and opens the existing VPN menu directly.

A bottom-right card shows three actual setup stages: load the profile, establish
the tunnel, confirm the server handshake. The progress bar is stage progress,
not an invented estimate of network duration. A failed start shows a warning.
Android VPN consent still has to be granted manually once; autostart reports
missing permission and does not silently bypass the system dialog.

## Connection and kill-switch behavior

The connection monitor sends a small DNS query through the VPN to the DNS server
already configured in the profile. No third-party test URL or account is used.
An initial WireGuard handshake confirms setup. A DNS reply or a newer handshake
renews a 30-second liveness window. Detection of a remote outage is therefore
not instantaneous. The native tunnel continues to contain packets while the
remote endpoint is unreachable; the app never deliberately falls back to direct
HTTP while the kill switch is enabled.

The protected HTTP client rejects requests and DNS when disconnected, binds
new sockets and DNS explicitly to the confirmed VPN Network, and closes tracked
sockets when protection or VPN transport changes. Existing connection-pool and
stream sockets cannot be reused as direct connections after enabling protection.
Player DNS sorting delegates to this resolver. External subtitle HTTP uses the
same client. The player observes protection changes and stops disallowed playback.

The switch applies to StreamVault's own provider network stack, not to Android
globally or other applications. New external-player, Cast and external-plugin requests are blocked
while it is enabled. Stop playback already running in another app/device there.
Non-HTTP remote playback transports are rejected with protection enabled because
they cannot use the protected HTTP stack. Existing QR import remains accessible
on the local network when the VPN is disconnected.

API reference: [Android Network socket factories and DNS](https://developer.android.com/reference/android/net/Network).

## Validation

GitHub Actions runs the existing 18 VPN tests plus 11 regressions for fail-closed
cold start, DNS/socket blocking, closing existing direct sockets, loss/reconnect,
unchanged healthy connections, handshake/liveness timeout and recovery. It also
runs the phone-page JavaScript check and builds an APK.

The previous QR/import version was confirmed working on Fire TV by the user.
These new controls still need real-device validation:
1. Select profile, enable autostart, leave/reopen the app: progress → connected.
2. Invalid server/permission denied: warning; with kill switch on no provider request.
3. Play HTTP IPTV, enable protection, disconnect VPN and interrupt the remote server:
   no direct provider traffic; status and warning update; retry after reconnect.
4. Verify provider-side source IP and DNS with a controlled endpoint during startup,
   disconnect/reconnect and Android revocation, including downloads/recordings.
5. Test toggles and top-bar shortcut with the Fire TV remote and many profile rows.

No local execution environment was available for this revision; build/test results
come from GitHub Actions. No real-device leak or throughput test is claimed.
The repository's graphify command could not run because no shell environment or
installed graphify executable was available.
