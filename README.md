<p align="center">
  <img src="assets/logo/rosenweg-kiosk-512.png" width="128" alt="Logo Rosenweg Kiosk: Turm, Weg und Rose mit Fernseher">
</p>

<h1 align="center">Rosenweg Kiosk</h1>

<p align="center">
  Startseite mit PIN für die Info-Fernseher der Siedlung Rosenweg in Kaiseraugst:<br>
  Diashow sofort nach dem Einschalten, alles andere nur mit PIN.
</p>

---

## Inhalt

- [Was die App macht](#was-die-app-macht)
- [Bedienung](#bedienung)
- [Einrichten am Fernseher](#einrichten-am-fernseher)
- [Als Startseite (HOME) festlegen](#als-startseite-home-festlegen)
- [Updates](#updates)
- [Bauen](#bauen)
- [Neue Version](#neue-version)
- [Aufbau](#aufbau)
- [Lizenz](#lizenz)

## Was die App macht

Rosenweg Kiosk ist eine kleine Android-TV-App (Google TV, z. B. Chromecast mit Google TV). Sie wird als Startseite eingerichtet und startet sofort die Diashow-App, standardmässig [SlideShow](https://slideshow.digital) (`sk.mimac.slideshow`). Wer die Diashow verlässt, landet auf einem PIN-Schirm statt auf dem Google-TV-Startbildschirm.

| Situation | Was passiert |
|---|---|
| Fernseher eingeschaltet | Diashow startet direkt |
| Diashow verlassen (Zurück, Home) | PIN-Schirm mit Ziffernfeld |
| Home-Taste irgendwo (Diashow, andere App, Kiosk-Menü) | immer PIN-Schirm, nie direkt die App-Liste |
| PIN-Schirm 30 s ohne jede Eingabe | zurück zur Diashow; jede Taste (Ziffer, Steuerkreuz, Fokuswechsel) startet die 30 s neu |
| Falsche PIN | Meldung; ab dem 3. Fehlversuch Wartezeit (30 s, dann doppelt so lang, höchstens 15 min) |
| Richtige PIN | Kacheln: „Diashow starten“, Android-„Einstellungen“, „Kiosk-Einstellungen“ und alle installierten TV-Apps |
| hinter der PIN (Apps, Einstellungen, PIN ändern, Diashow-App wählen) | kein 30-s-Zähler; erst nach 10 min ohne jede Bedienung zurück zur Diashow, jede Taste startet die 10 min neu |
| andere App offen (z. B. Android-Einstellungen, Netflix) oder Systemfenster darüber | der Kiosk wartet und funkt nicht dazwischen; kommt man nach mehr als 10 min zurück, erscheint der PIN-Schirm |
| Kiosk-Einstellungen | PIN ändern, Diashow-App wählen, nach Updates suchen |

Die PIN (4 bis 8 Ziffern) liegt nur als Hash (PBKDF2-HMAC-SHA256 mit zufälligem Salz) im privaten Speicher der App. Im Repo steht keine PIN; die erste PIN wird per adb gesetzt. Ohne PIN lässt sich der Kiosk nicht entsperren.

Die App braucht kein Rosenweg-Konto und kein Backend. Sie läuft auch auf Handys und Tablets (kein Touchscreen oder Leanback verlangt), ist aber für die Fernbedienung gebaut: grosse Schrift, deutlicher Fokus, Ziffern auch direkt über die Zifferntasten der Fernbedienung.

## Bedienung

| Taste | PIN-Schirm | Apps | Kiosk-Einstellungen |
|---|---|---|---|
| Steuerkreuz + Mitte | Ziffer, ⌫ oder OK wählen | Kachel öffnen | Punkt wählen |
| Zifferntasten | Ziffer eingeben, Fokus springt auf OK | – | – |
| Zurück | Eingabe löschen, sonst Diashow | Diashow | zurück zu den Apps |
| Home (wenn Kiosk Startseite ist) | bleibt auf dem PIN-Schirm | PIN-Schirm (Freigabe weg) | PIN-Schirm (Freigabe weg) |

> **Hinweis zu SlideShow:** SlideShow fängt die Zurück-Taste selbst ab (getestet mit 4.12.8 auf Google TV: Zurück bleibt in der Diashow). Den PIN-Schirm erreicht man dann mit der **Home-Taste**, sobald der Kiosk die Startseite ist, oder in den SlideShow-Einstellungen das Verlassen mit Zurück erlauben.

## Einrichten am Fernseher

Alle Befehle vom Mac aus, Fernseher per adb verbunden (`export ANDROID_SERIAL=<ip>:<port>`).

**1. Installieren** (APK von [www.rosenweg4303.ch/apps](https://www.rosenweg4303.ch/apps) oder selbst gebaut):

```bash
adb install -r rosenweg-kiosk-release.apk
```

**2. Erst-PIN setzen.** Nur die adb-Shell (bzw. das System) darf das: der Empfänger verlangt `android.permission.DUMP`, die keine normale App bekommt. Die PIN nicht in die Shell-History schreiben:

```bash
read -rs "PIN?PIN (4-8 Ziffern): "; echo        # zsh; in bash: read -rsp "PIN: " PIN
adb shell am broadcast -a ch.rosenweg4303.kiosk.EINRICHTEN \
  -n ch.rosenweg4303.kiosk/.AdbEinrichtung -f 0x20 --es pin "$PIN"
unset PIN
```

Die Antwort steht in der letzten Zeile, z. B. `Broadcast completed: result=0, data="PIN gesetzt; PIN eingerichtet: ja"`. Derselbe Befehl setzt eine vergessene PIN neu und hebt eine Wartezeit auf. `-f 0x20` (FLAG_INCLUDE_STOPPED_PACKAGES) braucht es, solange die App noch nie gestartet wurde.

**3. Andere Diashow-App (optional):**

```bash
adb shell am broadcast -a ch.rosenweg4303.kiosk.EINRICHTEN \
  -n ch.rosenweg4303.kiosk/.AdbEinrichtung -f 0x20 --es paket sk.mimac.slideshow
```

Gestartet wird der Leanback-Startintent des Pakets (bei SlideShow `.StartupActivity`), sonst der normale Startintent. Am Fernseher geht das auch unter Kiosk-Einstellungen → „Diashow-App“.

**4. Ausprobieren:** `adb shell am start -n ch.rosenweg4303.kiosk/.KioskActivity` oder die Kachel „Rosenweg Kiosk“ auf dem Google-TV-Startbildschirm.

## Als Startseite (HOME) festlegen

Google TV lässt `cmd package set-home-activity` nicht zu („Failed to set default home“), solange der Google-TV-Startbildschirm aktiv ist. Er muss für Benutzer 0 abgeschaltet werden (so am Heizungsraum-Fernseher am 26.11.2026 eingerichtet):

| Schritt | Befehl |
|---|---|
| Google-TV-Startbildschirm abschalten | `adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx` |
| Einrichtungsassistent abschalten (meldet sich mit Priorität 1 als Startseite) | `adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith` |
| Kiosk als Startseite festlegen | `adb shell cmd package set-home-activity ch.rosenweg4303.kiosk/.KioskActivity` |
| prüfen | `adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME` |
| **rückgängig** | `adb shell pm enable com.google.android.apps.tv.launcherx` und `adb shell pm enable com.google.android.tungsten.setupwraith`, danach Home drücken und den Google-TV-Startbildschirm wählen (oder den Kiosk deinstallieren: `adb uninstall ch.rosenweg4303.kiosk`) |

Hinter der PIN bleibt alles ohne den Google-TV-Startbildschirm erreichbar: die Kachel „Einstellungen“ öffnet die Android-TV-Einstellungen (`android.settings.SETTINGS` → `com.android.tv.settings`), die App-Kacheln starten die TV-Apps direkt (Leanback-Startintent).

Achtung: Ohne Google-TV-Startbildschirm fehlen dessen Empfehlungen, Konto- und Eingangsauswahl; Android-Einstellungen und alle Apps bleiben über den Kiosk (mit PIN) erreichbar.

Solange der Kiosk **nicht** Startseite ist, startet er nach dem Einschalten über `BOOT_COMPLETED`. Android erlaubt das aus dem Hintergrund nur mit „Über anderen Apps einblenden“:

```bash
adb shell appops set ch.rosenweg4303.kiosk SYSTEM_ALERT_WINDOW allow     # rückgängig: … default
```

## Updates

Die App enthält den **Rosenweg Updater** (`ch.rosenweg4303:updater` aus [commons-rosenweg](https://github.com/Rosenweg/commons-rosenweg)). Unter Kiosk-Einstellungen → „Nach Updates suchen“ liest er den App-Katalog der Telefonanlage (`https://pbx.rosenweg4303.ch/api/apps/index.json`, öffentlich, ohne Anmeldung), prüft jede APK (Prüfsumme, Paketname, Version, Rosenweg-Signatur) und übergibt sie dem Android-Installer. Beim ersten Mal fragt Android, ob die App Apps installieren darf.

| Was | Stand |
|---|---|
| Suche von Hand (Kiosk-Einstellungen) | geht, sobald die App im Katalog steht |
| tägliche Prüfung mit Benachrichtigung | noch nicht: `ch.rosenweg4303.kiosk` fehlt in `RosenwegUpdater.ZUSTAENDIG` (commons-rosenweg) |

## Bauen

```bash
./gradlew assembleRelease -PkeystoreProperties=/pfad/zu/keystore.properties
```

| Was | Wo |
|---|---|
| APK | `app/build/outputs/apk/release/rosenweg-kiosk-release.apk` |
| Paketname | `ch.rosenweg4303.kiosk` |
| minSdk / targetSdk | 28 / 35 (Google TV ab Android 9) |
| Signatur | Rosenweg-Schlüssel, Zertifikat SHA-256 `7b3bac26…78c5a5ae`; `keystore.properties` hier, per `-PkeystoreProperties=…` oder `../android-sip-rosenweg/keystore.properties` (nie einchecken) |
| Rosenweg-Module | GitHub Packages von commons-rosenweg mit `gpr.user`/`gpr.key` in `~/.gradle/gradle.properties` (Token mit `read:packages`, z. B. `gh auth token`) oder `GPR_TOKEN`/`GITHUB_TOKEN`; sonst `mavenLocal()` |

Prüfen: `apksigner verify --print-certs app/build/outputs/apk/release/rosenweg-kiosk-release.apk`.

**Logo neu erzeugen** (rosenweg-basis daneben): `sh assets/logo/erzeugen.sh`. Das Logo kommt aus dem Logo-Baukasten von rosenweg-basis (`werkzeug/logo`): Rosenweg-Wappen mit Turm, Weg und Rose, rechts unten das Zeichen der App (Fernseher mit Antenne und Wiedergabe-Zeichen, `zeichen/fernseher.svg`). Dazu erzeugt das Skript das TV-Banner (320×180, `assets/logo/banner.py`) und das grosse Logo für den PIN-Schirm.

## Neue Version

1. `versionCode` und `versionName` in `app/build.gradle.kts` erhöhen, APK wie oben bauen und signieren.
2. `gh release create v<versionName> app/build/outputs/apk/release/rosenweg-kiosk-release.apk --notes "Was ist neu"` (Tag = versionName, eine APK je Release).
3. Der Workflow `.github/workflows/apps.yml` (wiederverwendbarer Workflow `apps-release.yml` aus Rosenweg/Website) schickt sie an [www.rosenweg4303.ch/apps](https://www.rosenweg4303.ch/apps) und in den App-Katalog. Anmeldung per GitHub-OIDC, **kein Secret nötig**. Erneut senden: `gh workflow run apps.yml --ref <tag>`.

Einmalig auf der Anlage nötig: Repo in `APPS_KATALOG_REPOS` (Website `pbx/api/app.py`) und App auf rosenweg4303.ch/smartphones anlegen (ohne Gruppen).

## Aufbau

| Datei | Inhalt |
|---|---|
| `app/src/main/kotlin/…/KioskActivity.kt` | Startseite: Diashow, PIN-Schirm, Apps, Kiosk-Einstellungen, Zeitgrenzen |
| `app/src/main/kotlin/…/KioskSpeicher.kt` | Diashow-App, PIN-Hash, Fehlversuche und Wartezeit |
| `app/src/main/kotlin/…/Empfaenger.kt` | `AdbEinrichtung` (PIN/Paket per adb), `StartNachBoot` |
| `app/src/main/kotlin/…/Oberflaeche.kt` | Knöpfe und Kacheln mit Fokus für die Fernbedienung |
| `assets/logo/` | Logo-Quellen (SVG), Banner, `erzeugen.sh` |
| `.github/workflows/apps.yml` | Release an /apps |

## Lizenz

Eigene Entwicklung für die Siedlung Rosenweg. Lizenz: GNU General Public License v3.0 oder später (GPL-3.0-or-later), siehe [LICENSE](LICENSE).
