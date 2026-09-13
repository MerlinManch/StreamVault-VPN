# WireGuard-Erweiterung

Based on StreamVault. Originalentwickler: **David Nashash (Davidona)**.

- Originalprojekt: https://github.com/Davidona/StreamVault-IPTV
- Unterstützung des Originalentwicklers: https://ko-fi.com/davidona
- Ausgangscommit: `f86d4aeef21de4524a986c4ae87ac5f58ac42d25`
- Die unveränderte Projektlizenz in `LICENSE` gilt auch für diese Erweiterung.
- WireGuard Tunnel Library `com.wireguard.android:tunnel:1.0.20260102`:
  https://git.zx2c4.com/wireguard-android/about/ (Apache-2.0).

## Benutzung auf Fire TV / Android TV

1. Einstellungen → Datenschutz / Privacy → **WireGuard VPN** öffnen.
2. **Profil hinzufügen** wählen, einen Namen vergeben und eine `.conf`-Datei
   importieren oder den Konfigurationstext einfügen. Falls Fire OS keine
   Dateiauswahl anbietet, ist das Einfügen weiterhin verfügbar.
3. Beim Profil **Verbinden** wählen und die Android-VPN-Freigabe bestätigen.
4. Nach **Tunnel aktiv** wie gewohnt in StreamVault navigieren und abspielen.
   Der zusätzlich angezeigte Handshake bestätigt, dass der VPN-Server geantwortet
   hat. Ohne Datenverkehr muss noch kein Handshake vorliegen; es wird kein
   fremder Prüfdienst kontaktiert. Die Handshake-Anzeige ist keine fortlaufende
   Internet-Erreichbarkeitsgarantie.
5. Unter WireGuard VPN oder über die Benachrichtigung **Trennen** wählen.
   Vor einem Profilwechsel zuerst trennen. Profile lassen sich nach Bestätigung
   löschen. Maximal 32 Profile, je 64 KiB, Namen bis 64 Zeichen.

Es ist keine separate WireGuard-App und kein Root-Zugriff nötig. Es wird ein
bereits vorhandenes, gültiges VPN-Profil benötigt; diese App stellt keinen
VPN-Server bereit. Fire OS muss Androids `VpnService` und Freigabedialog anbieten.

## Routing und Lebensdauer

- Nur die tatsächlich installierte StreamVault-Paket-ID wird als erlaubte App
  eingetragen, einschließlich der `.debug`-Variante. Importierte Listen anderer
  Apps werden ersetzt. Der Player und die Provider-Implementierungen bleiben
  unverändert.
- Ein Peer, ein Endpunkt, eine Interface-Adresse, mindestens eine numerische
  DNS-Serveradresse und eine IPv4-Standardroute (`0.0.0.0/0`) sind erforderlich.
  Wenn IPv6-Adressen, IPv6-DNS oder IPv6-Routen konfiguriert sind, ist zusätzlich
  `::/0` erforderlich. Reine IPv4-Profile werden akzeptiert; die WireGuard-
  Bibliothek lässt dabei die unkonfigurierte IPv6-Familie nicht am Tunnel vorbei.
  Teilnetz- und Mehrfach-Peer-Profile werden mit einer Erklärung abgelehnt.
- Die Konfiguration wird mit dem offiziellen Parser validiert. Desktop-
  Erweiterungen wie `PostUp`/`PostDown` werden nicht ausgeführt.
- Der VPN-Dienst läuft während der Nutzung und im Hintergrund weiter, auch für
  Downloads und Aufnahmen innerhalb der App. Das Schließen eines Dialogs oder
  der Wechsel zum Player trennt ihn nicht.
- Nach Neustart, Prozessbeendigung oder Widerruf der VPN-Freigabe muss man erneut
  verbinden. Kein automatischer Boot-Start, kein Android-Always-on und kein
  dauerhafter Kill-Switch. Beim Trennen oder Beenden des VPN-Diensts gilt wieder
  die normale Netzwerkverbindung. Vor einem Streamstart verbinden; bestehende
  Streams bei Bedarf nach dem Verbinden neu starten.
- Android verwaltet jeweils ein VPN. Die Systemfreigabe kann ein anderes VPN
  ersetzen. Andere Apps, externe Player, Plugin-Prozesse in anderen Apps und
  Cast-Empfänger sind nicht Bestandteil dieses App-Tunnels.
- Profile sind mit AES-256-GCM und einem Schlüssel aus dem Android Keystore
  verschlüsselt in `noBackupFilesDir` gespeichert. Sie werden weder vom
  bestehenden StreamVault-Backup noch vom Android-Backup exportiert. Der
  Konfigurationstext wird nicht in `savedInstanceState` abgelegt. Fehlermeldungen
  übernehmen weder Schlüssel noch Rohtexte aus Parser-Ausnahmen.

## Bauen und installieren

Voraussetzungen wie beim Original: JDK 17, Android SDK 36, Gradle Wrapper und
Zugriff auf Google Maven / Maven Central. Keine eigenen Signierschlüssel oder
Provider-Zugangsdaten sind in der Erweiterung enthalten.

```sh
./gradlew :app:testDebugUnitTest --tests 'com.streamvault.app.vpn.*'
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Die Debug-App verwendet die vorhandene Paket-ID `com.streamvault.app.debug` und
kann neben der offiziellen Release-App installiert werden. Für eine spätere
Release-Verteilung sind eine eigene Signierung und die Lizenzbedingungen des
Originalprojekts zu beachten. Der ursprüngliche Updatekanal ist unverändert;
Updates des Originalprojekts enthalten diese Erweiterung nicht automatisch.

## Geräteprüfung vor Freigabe

Die folgenden Prüfungen benötigen ein echtes Fire-TV-/Android-TV-Gerät und ein
funktionierendes Testprofil; sie werden nicht durch einen erfolgreichen Build
oder die Parser-Tests ersetzt:

- Import per Datei und Texteingabe; D-Pad-Fokus, Zurück, Abbruch, Löschbestätigung.
- Freigabe bestätigen/ablehnen, erneut verbinden, Profilwechsel, VPN widerrufen.
- Server-Handshake und IPTV-Wiedergabe einschließlich EPG und Downloads prüfen.
- IPv4/IPv6 und DNS über einen kontrollierten VPN-Server prüfen; andere Apps
  müssen weiter die normale Verbindung verwenden.
- Server/Internet unterbrechen und wiederherstellen; Hintergrundwiedergabe,
  Geräteneustart und Prozessbeendigung prüfen. Die App darf nach einem Neustart
  keinen aktiven Tunnel vortäuschen.
- Native Bibliotheken für `armeabi-v7a` und `arm64-v8a` auf den Zielgeräten prüfen.
