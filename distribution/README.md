# Distribution

## Android releases

Use [`.envrc.example`](../.envrc.example) as the template for the upload-key
environment variables. Load the values into your shell before invoking Gradle;
keep passwords in your secret manager. Rayl also needs `RAYL_BREEZ_API_KEY` for
its production Spark integration.

Build Rayl's Google Play bundle from the repository root:

```shell
./gradlew :app:androidApp:bundleRelease
```

Upload `app/androidApp/build/outputs/bundle/release/androidApp-release.aab`
manually in Google Play Console. Rayl's `androidApp/build.gradle.kts` owns its
version.

## Assets

The `rayl/` directory contains public product metadata and the master icon:

- `icon.png`: app artwork used by distribution channels.
- `store-listing/en-US.md`, `de-DE.md`, `es-ES.md`: localized public store copy.

Keep names, capabilities, identifiers, screenshots, and legal links accurate for
Rayl. Maintain English, German, and Spanish copy together. Screenshots
must depict the current native UI; do not reuse retired multi-wallet screens.

Rayl's distribution identifier is `com.nicolasusca.rayl`. Its public legal URLs
are configured in [RaylConfiguration](../app/shared/src/commonMain/kotlin/xyz/lilsus/rayl/RaylConfiguration.kt).

[`encryption.md`](encryption.md) retains the historical Rayl and Blip iOS
encryption assessment and its evidence. It excludes France from the assessed
distribution scope. Rayl's Spark/Breez integration is outside that scope and
requires reassessment before release; the historical conclusions do not certify
the current app. Review Rayl's assessment and `Info.plist` declaration when
dependencies, cryptographic behavior, or distribution territories change.
