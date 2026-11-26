#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# App-Logo aus dem Logo-Baukasten von rosenweg-basis (Wappen mit Turm, Weg und
# Rose, rechts unten das Zeichen der App: Fernseher). Name, Titel und Zeichen stehen in
# rosenweg-basis werkzeug/logo/android-apps.txt. Danach Banner (320x180) und das
# grosse Logo fuer die Oberflaeche. Aus dem Repo-Wurzelverzeichnis:
#   sh assets/logo/erzeugen.sh [pfad/zu/rosenweg-basis]
# Benoetigt: python3, rsvg-convert (librsvg).
set -eu
BASIS="${1:-../rosenweg-basis}"
sh "$BASIS/werkzeug/logo/android-apps.sh" . rosenweg-tv-kiosk
mkdir -p app/src/main/res/drawable-nodpi app/src/main/res/drawable-xhdpi
cp assets/logo/rosenweg-kiosk-512.png app/src/main/res/drawable-nodpi/rosenweg_logo.png
python3 assets/logo/banner.py assets/logo/rosenweg-kiosk.svg assets/logo/rosenweg-kiosk-banner.svg
# Banner fuer den Google-TV-Startbildschirm: xhdpi 320x180 px (= 160x90 dp)
rsvg-convert -w 320 -h 180 assets/logo/rosenweg-kiosk-banner.svg -o app/src/main/res/drawable-xhdpi/banner.png
echo "Banner: app/src/main/res/drawable-xhdpi/banner.png"
