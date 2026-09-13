# Flint Privacy Policy

Last updated: September 13, 2026

Flint is an open-source client for a self-custodial Spark wallet. The Flint
maintainer does not operate an account service, payment processor, analytics
service, or application backend for Flint.

## Data stored on your device

Flint stores the wallet recovery phrase and related credential material in
app-scoped encrypted storage. It also stores wallet data, app preferences,
contacts, and payment shortcuts locally. Flint does not include its local data
in Android cloud backup or device transfer.

Recent payment receipts are kept only in memory for the current app session.
Flint does not keep a separate persisted payment-attempt queue or restore it
after a restart. The Breez Spark SDK maintains its own wallet data, including
transaction records. Closing Flint does not reverse a submitted payment.

Removing the wallet deletes its stored credential. Compatible app updates and
operating-system offload or archive features retain local data. Deleting Flint
resets its local data; after reinstallation it rejects any wallet credential
that may have survived outside the app container.

## Network requests

Flint uses the Breez Spark SDK and its services to synchronize the Spark wallet
and perform actions you request. Their own terms and privacy policies apply.

When required for a feature you use, Flint may also contact:

- CoinGecko to retrieve a Bitcoin exchange rate;
- the server named by a Lightning address or LNURL you open; and
- operating-system services needed to open external links.

Those services receive the network information normally associated with a
request, such as your IP address. Flint does not send the wallet recovery phrase
to CoinGecko or LNURL servers.

## Camera

Camera access is optional and is used to scan QR codes. Frames are processed on
the device and Flint does not store or upload camera images.

## Performance diagnostics

Flint does not include Firebase Performance Monitoring or offer remote
performance diagnostics in this version. Local system performance traces are
available for development and troubleshooting.

## Questions

Questions and privacy reports can be opened at
<https://github.com/NicolaLS/lasr/issues>.
