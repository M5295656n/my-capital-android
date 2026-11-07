# Offline issuer logo catalog

All assets in `app/src/main/assets/logos/` are bundled in the APK and render offline.
The catalog is intentionally conservative: a ticker is mapped only when its current
brand mark has been checked against a credible source. Other tickers use the plain
ticker fallback until their authentic artwork is verified. Do not recreate marks
from text, initials, colored circles, or approximate geometry.

## Verified assets

| Asset | Tickers | Brand / source reference | Notes |
|---|---|---|---|
| `sber.svg` | SBER, SBERP | [Sberbank Logo 2020 — Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Sberbank_Logo_2020.svg), source identified as Sberbank | Official 2020 symbol, vector |
| `tbank.svg` | T, TCSG | [T‑Bank official brand materials](https://www.tbank.ru/about/brand/) | 2024 bear mark, vector; official brand page provides original logo downloads |
| `yandex.svg` | YDEX | [Yandex logo rules](https://yandex.ru/company/rules/logotype) | Official red Я mark, vector |
| `x5.svg` | X5 | [X5 official brandbook](https://mediahub.x5.ru/brandbook) | Current two-colour X5 wordmark, official SVG download |
| `russian_coat_of_arms.svg` | OFZ / Russian federal government bonds | [State Coat of Arms of the Russian Federation — Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Coat_of_Arms_of_the_Russian_Federation.svg); source: [official Kremlin emblem resource](https://flag.kremlin.ru/emblem/) and Federal Constitutional Law No. 2-FKZ | Full multi-colour state coat of arms, not a hand-drawn eagle |

## Rules for future additions

1. Prefer the issuer's own press/brand/identity page and its original SVG or high-resolution transparent PNG.
2. Wikimedia Commons is acceptable only after checking the file page, the depicted issuer, the source, and the current version.
3. Do not use Google Favicon, initials, generic circles, or hand-drawn approximations as company marks.
4. For a ticker without a verified asset, leave it unmapped. Never show a wrong logo merely to fill the slot.
5. Keep the original source URL and exact file/variant in this manifest.
