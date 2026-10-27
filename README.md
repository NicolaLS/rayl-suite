# Rayl

Rayl is a Kotlin Multiplatform Lightning app for Android and iOS. Connect a Blink
account with an API key, a compatible wallet through Nostr Wallet Connect, or a
Spark wallet with its recovery phrase. Rayl uses one provider experience at a
time and is the repository's only distributed app. It is prerelease.

See [Rayl architecture](specs/ARCH-rayl.md) for application composition and
[provider ownership](providers/specs/ARCH-providers.md) for the independent wallet
implementations.

## Project layout

- `app`: Rayl composition, platform entry points, and wallet selection
- `providers/blink`, `providers/nwc`, `providers/spark`: independent provider implementations
- `core/*`: provider-neutral models, platform services, and UI primitives
- `feature/*`: reusable provider-neutral user stories
- `integration/*`: shared external-service adapters
- `distribution/*`: distribution artifacts

## Build

Use JDK 21 and the root Gradle wrapper.

For upload-key setup and Google Play releases, see [Distribution](distribution/README.md).

```shell
./gradlew :app:androidApp:assembleDebug
./gradlew :app:androidApp:assembleE2e
./gradlew check
```

Run startup and camera benchmarks on a connected Android 10+ device with the
separate benchmark app. Use a stable physical device for comparable results:

```shell
./gradlew perfCheck
```

On macOS, open `app/iosApp/iosApp.xcodeproj`, or build the complete Kotlin
and Swift app for the iOS simulator as in [iOS CI](.github/workflows/ios.yml):

```shell
xcodebuild \
  -project app/iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath "${TMPDIR:-/tmp}/rayl-ios" \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO \
  build
```
