# Logo asset sources

The app now loads issuer marks from `app/src/main/assets/logos`; no network
request is made to display a logo. SVG files are decoded locally by Coil's SVG
decoder.

## Bundled assets

- `aeroflot.svg` — Aeroflot icon from Simple Icons
  (https://github.com/simple-icons/simple-icons/blob/develop/icons/aeroflot.svg).
  Simple Icons is distributed under CC0-1.0; the source asset is used without
  modification.

## Adding an issuer

1. Add an SVG file to `app/src/main/assets/logos/`.
2. Record its upstream source and license here.
3. Add ticker -> filename in `AssetLogoCatalog.kt`. Map preferred-share
   tickers to the same issuer file as ordinary shares.
4. Add a unit test for the ticker mapping.

Do not add assets from collections that do not grant redistribution rights.
Company names and marks remain the property of their respective owners.
