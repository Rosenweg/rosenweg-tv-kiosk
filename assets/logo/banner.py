#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Banner (16:9) fuer den Google-TV-Startbildschirm: Logo links, Name rechts, auf Rosenweg-Rot.

    python3 assets/logo/banner.py <logo.svg> <banner.svg>

Das Logo aus dem Baukasten (Turm, Weg, Rose und Fernseher-Plakette) wird unveraendert
eingebettet; nur Rahmen und Schrift kommen hier dazu.
"""
import re
import sys

quelle, ziel = sys.argv[1], sys.argv[2]
with open(quelle) as f:
    logo = f.read()
m = re.search(r"<svg([^>]*)>(.*)</svg>", logo, re.S)
attr, inhalt = m.group(1), m.group(2)
vb = re.search(r'viewBox="([^"]+)"', attr).group(1)

B, H = 1280, 720
svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {B} {H}">
  <rect x="0" y="0" width="{B}" height="{H}" fill="#D32F2F"/>
  <svg x="60" y="80" width="560" height="560" viewBox="{vb}">{inhalt}</svg>
  <g font-family="DejaVu Sans, Helvetica, Arial, sans-serif" fill="#FFFFFF">
    <text x="660" y="330" font-size="118" font-weight="bold">Rosenweg</text>
    <text x="660" y="490" font-size="150" font-weight="bold">Kiosk</text>
  </g>
</svg>
'''
with open(ziel, "w") as f:
    f.write(svg)
