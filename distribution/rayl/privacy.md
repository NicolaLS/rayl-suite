# Rayl Privacy Policy

Last updated: October 20, 2026

This policy covers **Rayl** for Android and iOS, maintained by **Bitcoin Coast**
("we" or "the maintainer"). For privacy questions or requests, contact
[nicola@nicolasusca.com](mailto:nicola@nicolasusca.com) or open an issue at
<https://github.com/NicolaLS/rayl/issues>. Please do not send API keys,
connection secrets, recovery phrases, or payment preimages.

Rayl is an open-source client that connects to a wallet you choose, such as a
Blink account (using an API key) or a compatible wallet through Nostr Wallet
Connect (NWC). The maintainer does not operate a user account service, payment
processor, Nostr relay, advertising service, or analytics service for Rayl.

## Information stored on your device

Rayl processes the following information locally on your device to provide the
features you use:

- **Wallet credentials and connection details:** Your Blink API key or NWC
  connection URI is stored in app-scoped encrypted storage on Android or the
  Keychain on iOS. Only one wallet provider is active at a time; switching
  providers or removing your wallet deletes the stored credential.
- **Contacts and Payment Hub widgets:** Contact names, Lightning addresses,
  local widget layouts, preset shortcut amounts, payment comments, and local
  payment counts/timestamps used for Favorites and Recents are stored in local
  app storage. When connecting a Blink account, Rayl imports your Blink contact
  aliases and Lightning addresses into the local Hub. Rayl does not access your
  device's system address book.
- **Payment information:** Invoices, recipients, amounts, fees, status, and
  payment preimages are processed in memory. Up to ten resolved payment receipts
  are kept in memory for the current app session only and are cleared when the
  app process ends or the wallet is removed.
- **Preferences:** Theme, language, display currency, and payment-confirmation
  settings are stored locally on your device.
- **Camera and clipboard input:** Camera frames are used only to scan QR codes
  on the device and are never saved or uploaded. Clipboard text is read only
  when you trigger a paste action.

Android cloud backup and device-to-device transfer are disabled for Rayl's local
data. On iOS, wallet credentials use device-only Keychain protection accessible
only while the device is unlocked, and any stale Keychain credentials are
cleared after reinstallation before a new wallet is connected.

## Network requests and third parties

Rayl sends network requests only to the services required for the features you
use:

- **Your connected wallet provider:**
  - **Blink:** When using Blink, Rayl sends your API key and requests to
    retrieve wallet details, import Blink contacts, and submit or check
    payments. Blink processes your account and transactions under its
    [Privacy Policy](https://www.blink.sv/en/privacy-policy) and
    [Terms and Conditions](https://www.blink.sv/en/terms-conditions).
  - **Nostr Wallet Connect (NWC):** When using NWC, Rayl sends encrypted NWC
    requests to the relay or relays specified in your NWC connection URI. Those
    relays and your connected wallet service process requests under their own
    terms and policies.
- **Exchange-rate service:** Rayl requests aggregated Bitcoin exchange rates
  from the Rayl exchange-rate endpoint to convert between satoshis and your
  selected display currency. These requests do not include wallet credentials,
  contacts, payment history, or user identifiers.
- **Lightning-address and LNURL services:** Opening or paying a Lightning
  address or LNURL contacts the domain named in that request to resolve the
  recipient and request an invoice, including the payment amount and any
  optional comment you enter. Wallet credentials are never sent to these
  services.
- **Google ML Kit (Android only):** The Android app uses Google ML Kit for
  on-device barcode scanning, which sends technical SDK diagnostics (such as
  device/app metadata, an installation identifier, timings, and error codes) to
  Google over HTTPS. Camera images and decoded QR contents stay on your device.
  See [ML Kit's data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)
  and [Google's Privacy Policy](https://policies.google.com/privacy). The iOS
  app uses Apple's native scanner and does not include ML Kit.
- **External links:** Opening external links (such as wallet dashboards,
  support pages, or these legal documents on GitHub) uses your operating
  system's browser or link handler under the destination's privacy policy.

Services that receive network requests observe standard network metadata such as
your IP address and request headers. We do not sell personal data, use
advertising SDKs, or track you across apps or websites.

## Deletion and your choices

- **Remove your wallet in Settings** to delete the stored wallet credential and
  clear current-session payment receipts. Removing a wallet in Rayl does not
  delete your external wallet account or revoke a Blink API key or NWC
  connection at the provider; use your wallet provider's controls to revoke
  access.
- **Delete contacts or widgets in the Hub** to remove them from local storage.
- **Clear app storage on Android or delete the app on iOS** to erase all local
  app data on the device.

## Changes

We will update this policy and the date above if Rayl's data practices change.
