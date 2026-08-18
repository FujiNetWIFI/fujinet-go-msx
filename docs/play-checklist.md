# FujiNet Go MSX — Google Play submission checklist

Build the upload artifact with `tools/release-play.sh` (signed AAB via
`bundleRelease`; requires `keystore.properties` in the repo root pointing at
the shared upload keystore `~/keystores/fujinet-go-upload.jks`, alias
`fujinet-go-msx`).

## Console setup (one-time)

1. Play Console → Create app — name "FujiNet Go MSX", App, Free.
2. Upload the AAB to **Internal testing** first. Accept **Play App Signing**
   enrollment (our key becomes the upload key; Google holds the signing key).

## Store listing

- Description: reuse the repo README / zapstore.yaml copy. Must include:
  - a link to https://github.com/FujiNetWIFI/fujinet-go-msx (GPL source offer), and
  - ROM wording: "Ships with the freely redistributable C-BIOS; no copyrighted firmware included."
- Screenshots: phone + 7" and 10" tablet (landscape emulator captures).
- 512×512 icon and 1024×500 feature graphic
  (fujinet-go-intv/tools/icons/ has make-icons.py / make-banner.py generators).
- Privacy policy URL: https://fujinetwifi.github.io/fujinet-go-msx/
  (enable GitHub Pages: Settings → Pages → Deploy from branch → main, /docs).

## App content declarations

- **Data safety**: no data collected, no data shared, no data processed
  ephemerally by third parties. (See docs/index.md.)
- **Ads**: none.
- **Target audience**: 13+, not child-directed.
- **Content rating (IARC)**: utility/emulator, no UGC, no gambling — expect Everyone.
- **News / COVID / Government app**: No.
- **Foreground service — FGS_SPECIAL_USE declaration**. Justification (matches
  the manifest PROPERTY_SPECIAL_USE_FGS_SUBTYPE):
  "Runs the MSX emulator and the FujiNet network bridge while the
  app is backgrounded or another activity is open."
  Attach a short screen recording: start the emulator → press Home →
  notification shown → return to the app with the session intact.
  If review objects to the `dataSync` half of the service type, dropping it
  from android:foregroundServiceType is a one-line manifest change.

## Before each release

- [ ] Version bumped (versionCode must increase monotonically).
- [ ] `tools/release-play.sh` runs green (release ROM guards included).
- [ ] Device pass: first-run flow, emulator boots, FujiNet web UI loads.
- [ ] targetSdk: 35 today — **bump to 36 before Aug 2026 Play deadline**.
