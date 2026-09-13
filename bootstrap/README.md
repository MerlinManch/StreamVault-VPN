# StreamVault VPN import

This repository is Based on StreamVault by David Nashash (Davidona).

The import workflow copies the complete upstream source at commit `f86d4aeef21de4524a986c4ae87ac5f58ac42d25`, applies the reviewed WireGuard patch, commits the source here and builds a debug APK. The original license and links are preserved.

Original: https://github.com/Davidona/StreamVault-IPTV
Support: https://ko-fi.com/davidona

After import, see `docs/WIREGUARD.md` for setup, routing behavior and device checks. The app adds WireGuard profiles under Settings → Privacy; existing player and provider code is unchanged.
