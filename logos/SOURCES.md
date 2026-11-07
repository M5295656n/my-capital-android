# Local issuer marks

The SVG files in `app/src/main/assets/logos/` are simplified, locally bundled vector identifiers for the first group of major MOEX issuers. They are deliberately stored in the APK so they render without network access.

These are simplified visual interpretations for in-app identification, not a claim that every file is the current official master artwork or an endorsement by the issuer. Company names and trademarks belong to their respective owners. Where an exact official vector master is available and verified, it can replace the corresponding file without changing the ticker mapping.

The catalog maps ordinary and preferred share tickers to one issuer mark. Add a new SVG in the assets folder and add its ticker mapping to `AssetLogoCatalog.kt` and this manifest.

OFZ are not company shares: they use the separate double-headed eagle drawing in the app UI.
