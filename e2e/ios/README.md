# Wallet lifecycle regressions

These Maestro flows use the separate `com.nicolasusca.rayl.e2e` app and an invalid
test phrase; they require no network wallet or funds. Run them on an isolated iOS
simulator. Set `RAYL_TEST_DEVICE` to its UDID.

Build with simulator signing enabled: an unsigned executable cannot access the
Keychain, so it cannot exercise the credential lifecycle.

```sh
xcodebuild -project app/iosApp/iosApp.xcodeproj -scheme iosAppE2E \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /tmp/rayl-ios-e2e ARCHS=arm64 ONLY_ACTIVE_ARCH=YES \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- build
xcrun simctl install "$RAYL_TEST_DEVICE" "/tmp/rayl-ios-e2e/Build/Products/Debug-iphonesimulator/Rayl E2E.app"
xcrun simctl launch "$RAYL_TEST_DEVICE" com.nicolasusca.rayl.e2e --spark-corrupt-configured
maestro --device "$RAYL_TEST_DEVICE" test e2e/ios/wallet-recovery.yaml
xcrun simctl terminate "$RAYL_TEST_DEVICE" com.nicolasusca.rayl.e2e
xcrun simctl launch "$RAYL_TEST_DEVICE" com.nicolasusca.rayl.e2e --spark-orphan
maestro --device "$RAYL_TEST_DEVICE" test e2e/ios/wallet-fresh-setup.yaml
```

The recovery flow checks retry, dismissal and acceptance of removal confirmation,
return to the chooser, and a fresh Spark setup. The fresh-setup flow seeds an
unowned Keychain item (as can survive an uninstall or interrupted import), then
checks that choosing Spark cannot restore it and that restarting unfinished setup
returns to the chooser. It also checks cancellation back to the chooser from Blink
and NWC setup.
