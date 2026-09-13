# Rayl and Blip Privacy Policy

Last updated: September 13, 2026

This policy covers version 1.0 of **Rayl** and **Blip** for Android and iOS.
Both apps connect to your existing Blink account. Each app keeps its own local
data; they do not synchronize with each other. The maintainer does not operate
an account service, payment backend, advertising service, or app analytics
backend for version 1.0. Android scanner diagnostics are described below.

## Who is responsible and how to contact us

Rayl and Blip are maintained by **Bitcoin Coast** ("we" or "the maintainer").
For privacy questions or requests, contact
[nicola@nicolasusca.com](mailto:nicola@nicolasusca.com). Please do not send API
keys, passwords, recovery phrases, or payment preimages.

## Information the apps use

The apps process the following information to provide the features you use.
This processing happens on your device and through the services described below;
the maintainer does not receive your wallet credentials, contacts, or payment
history through an app-operated server.

| Information | Purpose and storage |
| --- | --- |
| Blink API key and wallet connection details | Connect to Blink, display wallet information, and submit and check payments. The API key is kept in app-scoped secure storage. |
| Blink contacts and contacts you enter | A successful wallet connection automatically imports Blink contact names/aliases and Lightning addresses into the local Payment Hub. The apps do not read your device's address book. |
| Hub widgets, preset amounts, comments, and contact usage | Store your chosen layout and payment shortcuts. Successful-payment counts and the most recent successful-payment time support Favorites and Recents. These values stay in local app storage. |
| Payment information | Process invoices, recipients, amounts, fees, status, and payment receipts. Payment requests are processed in memory, and the last ten resolved payment receipts are kept for the current app session. The apps do not persist payment attempts or restore them after a restart. |
| Preferences | Remember appearance, language, display currency, and payment-confirmation settings locally. |
| QR codes and pasted text | Read a wallet key or payment request that you provide. Camera frames are processed on the device and are not saved or uploaded by the apps. Clipboard text is read when you use a paste action. |

Camera access is optional. You can deny or revoke it in your device's settings.
Revoking access stops camera scanning; it does not remove information already
saved or cancel a payment. The apps do not request access to your location,
microphone, or device contacts.

## Network requests and third parties

The apps send requests directly to the services needed for the feature you use:

- **Blink:** receives your API key and requests to retrieve wallet information,
  import Blink contacts, send payments, and check payment outcomes. Payment
  requests include the invoice and the relevant amount and funding-wallet
  information. Blink manages your account and its own transaction records under
  its [Privacy Policy](https://www.blink.sv/en/privacy-policy) and
  [Terms and Conditions](https://www.blink.sv/en/terms-conditions).
- **CoinGecko:** receives requests for Bitcoin exchange rates and requested
  display currencies. The apps do not send it your wallet key, contacts,
  invoices, or payment history. See
  [CoinGecko's Privacy Policy](https://www.coingecko.com/en/privacy).
- **Lightning-address and LNURL services:** the service identified by a request
  you open receives the recipient lookup. When an invoice is requested, its
  callback service receives the payment amount and any comment supplied with
  that request. Those requests can occur before a payment is sent. The apps do
  not send your Blink API key to these services.
- **Google ML Kit, on Android only:** the bundled QR scanner sends technical
  data to Google for SDK diagnostics and usage analysis. This includes device
  and app details, an installation identifier, processing timings, scanner
  configuration, input/output sizes, feature events, and error codes. Images
  and decoded QR contents are processed locally and are not sent to Google.
  These diagnostics use HTTPS. See
  [ML Kit's data disclosure](https://developers.google.com/ml-kit/android-data-disclosure),
  [ML Kit's privacy terms](https://developers.google.com/ml-kit/terms), and
  [Google's Privacy Policy](https://policies.google.com/privacy).
  The iOS apps use Apple's native scanner and do not include ML Kit.
- **External links:** opening a dashboard, policy, receipt link, or another
  website sends you to that destination using platform services. The destination
  processes the resulting request under its own policy. These legal documents
  and the public source repository are hosted by GitHub; see
  [GitHub's Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).

Services receiving a request can see network metadata such as your IP address,
request time, and protocol headers. Their servers and service providers may be
in a different country from yours. Your wallet provider and recipient services
are independent operators; their retention practices and privacy rights are
described by their own policies. Contact them about information they hold.

We do not sell personal information, use it for advertising, or track you
across other companies' apps or websites. Firebase Performance Monitoring is
not included. Apart from the Android SDK diagnostics described above, the apps
do not send app performance reports to the maintainer or an analytics service.
Local system traces may be used for development and troubleshooting without
including wallet keys, invoices, QR contents, or payment preimages in diagnostic
markers.

## Security and device backups

API keys use Android encrypted storage or the iOS Keychain. iOS credentials are
restricted to the device and are accessible only while it is unlocked. Other
local information uses the app's private storage. Wallet and exchange-rate
requests use encrypted HTTPS connections. Keep your device and operating system
secure and grant your API key only the permissions you need. No storage or
transmission method provides an absolute security guarantee.

Android cloud backup and device transfer are disabled for the apps' local data.
On iOS, ordinary app data may be included in operating-system backups according
to your device settings; the API key uses device-only Keychain protection.
Manage any existing operating-system backups through Apple or Google. The apps
do not provide their own cloud backup or cross-app synchronization.

## Retention, deletion, and your choices

- **Remove a wallet in the app's Settings** to erase its locally stored API key,
  connection details, and current-session payment receipts. Contacts, preferences,
  Hub widgets, and Hub usage counts and timestamps remain on the device.
- **Delete contacts or widgets in the Hub** to remove those items. Removing a
  widget does not delete its contact or the contact's payment usage information.
- **Clear app storage on Android, or delete the app on iOS**, to remove its
  local app data. Uninstalling on Android also removes local app data. Offloading
  or archiving an app and installing an update normally preserve that data.
  After an iOS reinstall, the app clears any old wallet credential left in the
  Keychain before using the credential store. Existing OS backups are separate.
- **Payment receipts** remain only in memory for the current app session and
  are cleared when the app process ends or the wallet connection is removed.
  Closing the app does not cancel a payment already submitted to Blink. Hub usage
  information remains with the saved Hub data.
- **Blink account data and API-key authority are separate.** Removing a wallet
  or deleting an app does not delete your Blink account or Blink's records,
  revoke the key at Blink, reverse a payment, or cancel a payment in progress.
  Revoke the key through Blink and contact Blink for account or record deletion.

There is no separate Rayl or Blip account to delete. The maintainer cannot read,
recover, or remotely erase data stored only on your device or in your Blink
account. If you contact us, we use the contact details and information you
choose to provide to respond and resolve your request. We retain that
correspondence only as needed for that purpose and any applicable legal
obligations; you can request its deletion through the contact above. Email and
public issue-hosting providers process correspondence under their own policies.
Public GitHub issues are visible to others, so use the private contact for
sensitive questions.

Depending on the law that applies to you, you may have rights to access,
correct, delete, restrict, or receive a copy of personal information, object to
its processing, withdraw consent where processing relies on consent, and
complain to your local data-protection authority. Contact us to exercise rights
concerning information the maintainer holds. The device controls above govern
local app data; independent services handle requests concerning their records.

## Changes

We will update this policy and its date when the described practices change.
Material changes will be explained in the app or its update information, and
consent will be requested before new processing where required. Future wallet
providers or Hub services are not covered as active features of version 1.0.
