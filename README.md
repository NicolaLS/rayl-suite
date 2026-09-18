> **Default app: Rayl.** Connect your Blink wallet in 1.0. Blip, Lasr, and Flint
> remain purpose-built alternatives. All four apps are prerelease. See
> [Rayl architecture and behavior](docs/rayl-app.md) for scope and provider ownership.

# Rayl Suite

Rayl Suite contains four independent Kotlin Multiplatform Lightning clients and
the provider-neutral modules they share:

- **Rayl** is the unified app; 1.0 offers Blink during setup, with more providers planned.
- **Blip** connects to one Blink wallet with an API key.
- **Flint** opens one Spark wallet from its recovery phrase.
- **Lasr** connects to one wallet through Nostr Wallet Connect.

Blip, Flint, and Lasr retain their single-provider focus. All products are
prerelease; no migration or cross-app data transfer is implemented.

## Project layout

- `apps/rayl`: unified Rayl composition and wallet selection
- `apps/blip`: Blip product composition
- `apps/flint`: Flint product composition
- `apps/lasr`: Lasr product composition
- `providers/blink`, `providers/nwc`, `providers/spark`: independent provider implementations
- `core/*`: provider-neutral models, platform services, and UI primitives
- `feature/*`: reusable provider-neutral user stories
- `integration/*`: shared external-service adapters
- `distribution/*`: app-specific store copy, legal/reviewer material, and icons

## Build

Use JDK 21 and the root Gradle wrapper.

For upload-key setup and Google Play releases, see [Distribution](distribution/README.md).

```shell
./gradlew :rayl:androidApp:assembleDebug
./gradlew :blip:androidApp:assembleDebug
./gradlew :flint:androidApp:assembleDebug
./gradlew :lasr:androidApp:assembleDebug
./gradlew :blip:androidApp:assembleE2e
./gradlew :flint:androidApp:assembleE2e
./gradlew :lasr:androidApp:assembleE2e
./gradlew check
```

For iOS, open the chosen app’s `iosApp.xcodeproj`, or validate a Kotlin Debug
simulator framework directly:

```shell
./gradlew :rayl:shared:linkDebugFrameworkIosSimulatorArm64
./gradlew :blip:shared:linkDebugFrameworkIosSimulatorArm64
./gradlew :flint:shared:linkDebugFrameworkIosSimulatorArm64
./gradlew :lasr:shared:linkDebugFrameworkIosSimulatorArm64
```

See [E2E build guidance](docs/e2e.md),
[performance diagnostics](docs/performance-monitoring.md),
[Rayl architecture and behavior](docs/rayl-app.md), and
[the native app shell](docs/native-shell.md).
