# Distribution

## Android releases

Use [`.envrc.example`](../.envrc.example) as the template for the upload-key
environment variables. Load the values into your shell before invoking Gradle;
keep passwords in your secret manager. Flint also needs `FLINT_BREEZ_API_KEY`.

Build the chosen app's Google Play bundle from the repository root:

```shell
./gradlew :rayl:androidApp:bundleRelease
```

Replace `rayl` with `blip`, `flint`, or `lasr` as needed. Upload
`apps/<app>/androidApp/build/outputs/bundle/release/androidApp-release.aab`
manually in Google Play Console. The app's `androidApp/build.gradle.kts` owns
its version.

## Assets

Each app's directory contains its public product metadata and master icon:

- `icon.png`: app artwork used by distribution channels.
- `store-listing/en-US.md`, `de-DE.md`, `es-ES.md`: localized public store copy.

Keep names, capabilities, identifiers, screenshots, and legal links accurate for
the owning app. Maintain English, German, and Spanish copy together. Screenshots
must depict the current native UI; do not reuse retired multi-wallet screens.

Rayl's distribution identifier is `com.nicolasusca.rayl`. Public privacy policies
and terms live under [`docs/legal`](../docs/legal).

[`encryption.md`](encryption.md) records the Rayl and Blip iOS encryption
assessment, dependency evidence, and matching `Info.plist` declarations. The
current assessment excludes France from initial distribution; enabling France
requires resolving the documented declaration or exemption work first. Update
the assessment and both affected plist declarations when dependencies,
cryptographic behavior, or distribution territories change.
