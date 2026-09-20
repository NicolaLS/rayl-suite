# Rayl and Blip iOS encryption assessment

Assessment revision: **2026-09-13 / MOB-72** (original assessment: MOB-59).

**Rayl scope update:** Rayl now integrates Spark through the Breez SDK alongside
Blink and NWC. The prior Rayl assessment below does not cover this added provider
and native SDK, and requires reassessment before release. Its recorded conclusions
and binary evidence are retained for the previously assessed scope; this update
does not establish a new encryption classification or change the plist declaration.
Blip's provider scope is unchanged.

This maintained record explains the App Store Connect answers and the matching
`Info.plist` declarations for Rayl and Blip 1.0. It covers app code, linked iOS
libraries, and the available binary evidence. Android, Flint, and Lasr are outside
this assessment. The same declaration and maintenance contract are carried on
`main`; changes to its product or dependency scope require reassessment.

## Answers for the assessed distribution scope

**Both apps contain standard/published cryptography beyond Apple's operating
system. France is deferred for both apps' initial distribution.** This is an
engineering application of Apple's published instructions, not an individual
Apple, BIS, or ANSSI ruling about these products.

| App Store Connect question / property | Rayl 1.0 | Blip 1.0 |
| --- | --- | --- |
| Does the app use/include encryption? | Yes | Yes |
| Algorithm choice in the four-option dialog | **Option 2: standard encryption algorithms instead of, or in addition to, Apple's OS encryption** | **Option 2: standard encryption algorithms instead of, or in addition to, Apple's OS encryption** |
| Proprietary/unpublished cryptography identified? | No | No |
| Available on the App Store in France under this assessment? | **No — deferred** | **No — deferred** |
| Apple documentation upload required under this scope? | No, per the reasoning below | No, per the reasoning below |
| `ITSAppUsesNonExemptEncryption` | **`false`** | **`false`** |
| `ITSEncryptionExportComplianceCode` | Not required under this scope; none set | Not required under this scope; none set |

The Boolean records an exemption from Apple's documentation requirements. It
does **not** mean the app contains no cryptography, uses only Apple implementations,
or has no government export obligations. Do not select option 4 simply because
this Boolean is false.

The owner authorized deferring France if its initial formalities add effort.
**App Store Connect availability must exclude France before relying on this
assessment.** The repository cannot enforce storefront availability, and no store
setting was changed by this work. Recheck availability when submitting a build;
do not enable France or automatically add it through a territory-setting change
while keeping this conclusion unchanged. Existing uploaded builds retain their
old plist and may still require their questionnaire to be completed manually.

### Why `false` follows from the stated scope

1. [Apple's documentation table][apple-table] lists a French declaration for
   industry-standard encryption implemented outside Apple's OS. Its footnote
   limits that document to distribution in France. Proprietary encryption is a
   separate category for which the table lists CCATS documentation as well.
2. [Apple's determination procedure][apple-process] says to update the plist when
   documentation is unnecessary, indicating either no encryption or exemption
   from documentation.
3. [Apple's Security guidance][apple-security] defines `NO` to include encryption
   exempt from documentation requirements, including linked libraries. It
   separately notes that such apps may still require a US self-classification
   report. [The property reference][apple-key] describes the Boolean and the
   Apple-issued code for cases requiring documentation.

Combining those instructions with the technical inventory gives the above
no-documentation assessment for **standard/published cryptography with France
excluded**. It does not claim a banking exemption, a government classification,
or a successful App Store Connect questionnaire submission. If Apple's actual
questionnaire requests documentation inconsistent with this reading, resolve it
through [Apple export-compliance support][apple-support] and update this record;
do not change factual answers to bypass the request.

## Assessed source and dependency graph

Historical source baselines inspected before adding these declarations (commit IDs remain valid after branch retirement):

| Branch | Commit |
| --- | --- |
| `release/rayl/1.0` | `a62f88f4878219a6c565e36305885684970454a5` |
| `release/blip/1.0` | `c571b9134e141a5c0f88bbe50bcdb1070a9e0959` |

The 12 September consolidation restores the local-only source from Rayl's final
1.0 branch tip `3ce2873` onto `main`. The Claro service purchase/recovery code,
its Hub credential-store initialization, and its additional feature dependency
edges are removed. The retained core/provider dependency definitions, iOS
Keychain implementation, and Rayl/Blip framework exports match the originally
assessed local-only scope. No dependency version, cryptographic library, or
territory is intentionally changed. Hub networking remains source-disabled,
including external URL overrides. This does not remove or change the bundled
ACINQ/NWC cryptography listed below. Both plist Booleans remain `false` under the
same France-excluded scope; their revision comments are refreshed. Historical
binary evidence below remains historical, not a new candidate assessment.

The 13 September foreground-payment simplification removes the Blink and NWC
payment features' direct JSON serialization dependencies and serialization
compiler plugins with their pending-attempt stores. NWC payment also drops its
direct multiplatform-settings dependency. JSON serialization and settings remain
supplied by the existing integration/features that use them; no library version,
cryptographic implementation, framework export, or territory changes. Foreground
Blink status queries use the existing Apollo/HTTPS client. Both plist Booleans remain `false`
under the same France-excluded scope; their assessment revision comments are
refreshed. This source review does not replace candidate binary evidence.

Relevant core/provider dependency definitions agree between these release tips.
The `iosArm64CompileKlibraries` reports resolved on the assessment date show:

| Dependency family | Selected version | Consumers |
| --- | --- | --- |
| `fr.acinq.bitcoin:bitcoin-kmp` | `0.31.0` | Both |
| `fr.acinq.lightning:lightning-kmp-core` | `1.13.0` | Both |
| `fr.acinq.secp256k1:secp256k1-kmp` | `0.23.0` | Both; overrides Nostr's requested `0.22.0` |
| `com.apollographql.apollo:apollo-runtime` | `5.0.1` | Both |
| `io.ktor:ktor-client-darwin` | `3.5.1` | Both; overrides older transitive requests |
| `io.github.nicolals:nwc-kmp` | `0.3.3-SNAPSHOT` | Rayl |
| `io.github.nicolals:nostr-crypto`, `nip04`, `nip44` | `0.3.2-SNAPSHOT` | Rayl |
| `com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings` | `0.9.5` | Rayl |
| `com.squareup.okio:okio` | Rayl `3.16.4`; Blip `3.16.2` | Both; byte/IO/hash utilities |

The reports also include the Kotlin/Compose runtimes, lifecycle/navigation,
serialization/coroutines, Kermit, URI/UUID utilities, and multiplatform-settings.
No additional third-party confidentiality engine was identified in the inspected
app call paths. This is not a claim that every dependency contains no hash or
cryptographic utility. Xcode does not add a separate Swift package dependency to
either of these two app projects at these baselines.

Relevant entry points are [the version catalog](../gradle/libs.versions.toml),
[shared payment dependencies](../core/payment/build.gradle.kts),
[Blink integration](../providers/blink/integration/blink/build.gradle.kts),
[Rayl framework exports](../apps/rayl/shared/build.gradle.kts), and
[Blip framework exports](../apps/blip/shared/build.gradle.kts).

### Common iOS behavior and ACINQ

| Function | App behavior and implementation evidence |
| --- | --- |
| Credential storage | [SecureSettings](../core/settings/src/iosMain/kotlin/xyz/lilsus/raylsuite/core/settings/SecureSettings.ios.kt) uses Apple's Security `SecItem*` Keychain APIs with `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`. Android's AES-GCM implementation is outside this iOS assessment. |
| HTTPS / secure WebSockets | [The shared HTTP factory](../core/network/src/iosMain/kotlin/xyz/lilsus/raylsuite/core/network/PlatformHttpClient.ios.kt) uses Ktor Darwin; [BlinkApiClient](../providers/blink/integration/blink/src/commonMain/kotlin/xyz/lilsus/blip/integration/blink/BlinkApiClient.kt) uses Apollo's default Apple engine. [Ktor's implementation][ktor-source] and [Apollo's implementation][apollo-source] use `NSURLSession`. |
| Invoice authentication | [LightningInputParser](../core/payment/src/commonMain/kotlin/xyz/lilsus/raylsuite/core/payment/LightningInputParser.kt) calls the ACINQ parser. [BOLT11 parsing][acinq-invoice] hashes the invoice and recovers/verifies its ECDSA secp256k1 signature. |
| LNURL metadata integrity | [LnurlInvoiceResolver](../core/payment/src/commonMain/kotlin/xyz/lilsus/raylsuite/core/payment/LnurlInvoiceResolver.kt) compares SHA-256 metadata hashes. [ACINQ's iOS digest implementation][acinq-digest] delegates SHA hashing to Apple CoreCrypto. |
| BOLT12 parsing | [Offer parsing][acinq-offer] handles TLVs and a tagged-hash/Merkle-derived identifier. The input parser decodes offers to report them as unsupported; generic payment-request parsing also includes a BOLT12 branch. The inspected Blink code does not call onion/route decryption. |
| Native secp256k1 | [ACINQ's native implementation][acinq-secp] bundles native secp256k1 and exposes ECDSA, Schnorr, key operations, and **ECDH**. Its compiled capability is broader than the invoice-validation call sites. |
| Additional ACINQ cipher code | [Lightning's ChaCha20-Poly1305 implementation][acinq-chacha] contains Kotlin ChaCha20/Poly1305 code. A separate [Apple Noise cipher wrapper][acinq-apple-cipher] uses CryptoKit. These are different implementation paths; neither establishes the other is absent. |

The linked ACINQ, Apollo, and Ktor Kotlin source files were compared byte-for-byte
with the corresponding cached iOS source JAR files. The PhoenixCrypto Swift
wrapper was inspected at the same Lightning release commit, rather than compared
to a source JAR. Their links identify exact upstream commits.

[BIS distinguishes signature/authentication/integrity functions from data
confidentiality][bis-confidentiality]. That distinction describes Blink's invoice
validation use, but it does not justify describing the whole Blip binary as
signature-only: retained ECDH and configuration-dependent cipher code are recorded
below. The current Apple answer therefore does not depend on that narrower claim.

### Rayl's NWC implementation

Rayl's shared framework exports `providers:nwc:experience`, which depends on
[`providers:nwc:integration:nwc`](../providers/nwc/integration/nwc/build.gradle.kts).
The cached NWC client registers both NIP-04 and NIP-44.

| Function | Cached implementation |
| --- | --- |
| NIP-04 confidentiality | [NIP-04 module][nwc-nip04]: secp256k1 ECDH, random IV, AES-256-CBC with PKCS#7. [The iOS AES implementation][nwc-aes] calls Apple's `CCCrypt`; ECDH is supplied by bundled secp256k1. |
| NIP-44 v2 confidentiality/integrity | [NIP-44 module][nwc-nip44] and [NostrCrypto][nwc-crypto]: secp256k1 ECDH, HKDF-SHA-256, ChaCha20 IETF, and HMAC-SHA-256. ChaCha20 and SHA-256 use libsodium bindings; HMAC/HKDF construction is Kotlin over the hash primitive. |
| Nostr event authentication | [NostrSigning][nwc-signing] uses secp256k1 Schnorr signatures. Randomness comes from libsodium. |

These are published cryptographic constructions. References include
[ChaCha20/Poly1305 (RFC 8439)](https://www.rfc-editor.org/rfc/rfc8439),
[HKDF (RFC 5869)](https://www.rfc-editor.org/rfc/rfc5869), and the
[published NIP-44 specification](https://github.com/nostr-protocol/nips/blob/master/44.md).
No secret/proprietary algorithm was identified. Published Nostr protocol details
are not equated with an IETF standards-track endorsement; Apple's
[overview][apple-overview] and [BIS guidance][bis-nonstandard] distinguish
proprietary/unpublished cryptographic functionality when describing non-standard
cryptography.

The local `nostr-kmp` checkout was clean at
`dbae06173df2631c7817b8c401db1aafc4b20285`, but its current sources differ from
the resolved SNAPSHOT source JARs. It is **not** treated as the source commit for
the published artifacts. The Nostr source permalinks below identify individual
historical file blobs matched exactly to the cached sources; different commits
do not collectively establish one reproducible publication commit. No complete
source-commit match was established for the cached `NwcClient.kt`.

## Binary evidence and its limits

The following existing local archives were inspected without rebuilding or
changing them. They have app version `1.0.0`, build `1`. Their executable SHA-256
and Mach-O/dSYM UUID identify the evidence independently of machine-specific paths.
Matching UUIDs were checked before reading dSYM symbols.

| Artifact | Executable SHA-256 | Matching executable/dSYM UUID |
| --- | --- | --- |
| Rayl archive, 2026-09-08 16:58 | `55456dc06a89b8ed502bfd0317768b08e9c3fa497db9cd66ae9e1f37c4e738d8` | `C7DB6673-A67C-3279-957F-B63AFD2DB099` |
| Blip archive, 2026-08-26 10:15, duplicate archive named with suffix `2` | `4978f2910313c7400dfcedecb004651f166b6c94a25044ccc95746cf9cf982b7` | `A41F7731-7A36-3720-9033-59A6017418A0` |

- Rayl's matching dSYM includes `_crypto_stream_chacha20_ietf_xor`,
  `NostrCrypto#chacha20IetfXor`, `aes256CbcEncrypt`, `ecdhXOnly`, `NwcClient`,
  and the exported `NwcIosExperience`. These are final-linked evidence, beyond
  merely seeing a dependency in a Gradle file. The stripped executable alone
  does not retain these private symbol names.
- Blip's matching dSYM includes `_secp256k1_ecdh` and
  `Secp256k1Native#ecdh`. No ChaCha20/Poly1305 symbols were found in that older
  archive. Absence of symbol-name matches alone is not a proof of absence of all
  cryptographic code, and this archive predates the current release branch.
- An existing Blip simulator Debug framework contains ACINQ ChaCha20/Poly1305 and
  ECDH symbols. This establishes configuration-dependent compiled capability;
  it is **not** evidence that those ciphers survived the older Release app link.
- Rayl's existing device Release `Shared.framework/Shared` also contains NWC
  crypto. Its SHA-256 is
  `e334187990bba8a4a5ac14453b795df9190ecfb7d88c5a251d2101d4dda555b8`.

None of these observations proves which archive was uploaded to Apple, or the
exact Git commit and complete resolved dependency set used to create an archive.
The record therefore separates current source/graph evidence, cached artifact
identity, and observed binary capabilities. It does not certify a newly built
release candidate. Both archived plists lacked the encryption keys; changing the
source plists affects subsequent builds only.

### Audited cached device artifacts

These SHA-256 values identify the inspected `iosArm64` `.klib` cache files, not
an assertion that the above archives were built from those exact bytes. Record
new values whenever resolving new SNAPSHOT contents, even at the same version.

| Artifact | SHA-256 |
| --- | --- |
| `bitcoin-kmp` 0.31.0, main | `61b834183b66aa9cb9aa415503f22210ca87e392f6c995047f87bace2fab73b6` |
| `bitcoin-kmp` 0.31.0, CoreCrypto cinterop | `300eae28d71c5e4ada84258d42496497119543d25ebccd7aa26c1b0455d60432` |
| `lightning-kmp-core` 1.13.0, main | `299da4d0031a3916f772a10aa86a4fafac09e453dd19bb01094f9843793d2def` |
| `lightning-kmp-core` 1.13.0, PhoenixCrypto cinterop | `64a1fd89fae6179153e129e0203330a3affb3fc1a7842081d75a2a7d662c67d6` |
| `secp256k1-kmp` 0.23.0, main | `a51bc2da16b3307c57af747589522f3054d813c3b0c51d0edd66446c579d8e96` |
| `secp256k1-kmp` 0.23.0, libsecp256k1 cinterop | `2d82b60101e935f7d8381a82aef1acf93888a8cb982af91ba676db522bc1b818` |
| `nwc-kmp-iosarm64` 0.3.3-SNAPSHOT | `3c3233f46fac087668ca3d27003fc0105347c70dee5168297c8c870dbbbb60b7` |
| `nostr-crypto-iosarm64` 0.3.2-SNAPSHOT | `97e53557b9e73a8c783f948c876c907b742016315c6346965b7afdb3efe99fde` |
| `nip04-iosarm64` 0.3.2-SNAPSHOT | `de1843444d92a94e852e7374a7f239a1a0b1a59de8dd10486a276844eeb4d4cb` |
| `nip44-iosarm64` 0.3.2-SNAPSHOT | `e41acfd7432e779161757dda8eeebda4acd300fc1d4ca6fdad56dc803d4e9bf3` |
| `multiplatform-crypto-libsodium-bindings` 0.9.5, main | `8be93759876ffb93cb48d013a8aa0e64af4e6c45836424847abb5b6cc5a5ba38` |
| `multiplatform-crypto-libsodium-bindings` 0.9.5, libsodium cinterop | `124b168945c07cb0a85f6515c497230552f08c7145bdf87c1fce838a4655193b` |

Corresponding NWC source JAR SHA-256 values, in the same NWC/Nostr/NIP order:

```text
nwc-kmp       13ea8aca260ff04883967115e5a8c17836ee948d69696a6ce4e90b58b55c65df
nostr-crypto  c0f04af6414e4165171fd24968dfa90d7c694961e1cf45ff33ab6a8fbfa9a9d6
nip04         7024fd69e4de1366906a2940f9bdb84684d80f9adbf7df6be61efc111ac50d80
nip44         6e251432a6ba4d41b95839349dccbf9c68999bc2faedc7438caabfe7ff128473
```

## Deferred work and release conditions

- [ ] **France — both apps:** before enabling the French storefront, complete the
  applicable ANSSI declaration and Apple document review, or obtain a specific
  exemption determination for the actual product. Then update this record and
  the plists; if Apple requires non-exempt documentation, use `true` and only the
  real `ITSEncryptionExportComplianceCode` Apple supplies. Do not invent a code.
  [ANSSI's procedure][anssi-procedure] describes the formalities. The financial
  exclusion in [French decree 2007-663, Annex 1, category 3][france-decree] has
  conditions about financial-only purpose, general-public use, and inaccessible
  cryptographic capability. This assessment does not assume that either app
  satisfies those conditions merely because it is a wallet.
- [ ] **US classification/reporting:** establish and retain the applicable EAR
  classification/authorization or exclusion before distribution. No CCATS,
  public-source notification, or annual report has been submitted by this work.
  Start with [BIS's encryption analysis][bis-overview]. If relying on
  [ENC 740.17(b)(1)][bis-enc], review its self-classification requirements;
  [BIS's reporting instructions][bis-report] give the applicable annual deadline
  of February 1 after the export year (February 1, 2027 for reportable 2026
  exports). Excluding France settles neither US classification nor reporting.
- [ ] **Exact next candidate:** associate each archive with its source commit,
  resolved artifact hashes, executable/dSYM UUID, effective plist, and selected
  territories. Verify the current Blip release candidate rather than treating
  the August archive as current. Check the actual App Store Connect response for
  standard cryptography / no France against the documented inference above.

## Maintenance contract

Any new, removed, or upgraded **direct or transitive** iOS library invalidates
the previous dependency review until reassessed. This includes changes to
SNAPSHOT bytes, SPM/CocoaPods/native libraries, framework exports, HTTP engines,
credential/storage implementation, algorithms/protocols, or the features that
can invoke them. Territory changes also require reassessment.

In the same change:

1. Update the dependency/algorithm inventory and exact source/artifact evidence
   here. State each implementation's purpose and whether Apple supplies it.
   A library's name or open-source license does not establish exemption.
2. Review the current Apple and relevant government sources and update the
   answer table, reasoning, scope, and assessment revision.
3. Update each affected app's `Info.plist` declaration. If the Boolean remains
   correct, refresh its assessment revision comment to record the review.
   Keep a real Apple code only when the revised determination requires it.
4. Apply the change to `main` and each affected 1.0 branch using the repository's
   normal cherry-pick policy; update the related MOB issue.
5. Validate the plist XML and inspect the processed plist of the next candidate.
   Source plist edits cannot retroactively change an uploaded build.

For a dependency-only inventory, run these **separate scoped tasks**; no app
Release build is necessary to obtain the graph:

```sh
./gradlew :rayl:shared:dependencies --configuration iosArm64CompileKlibraries --console=plain
./gradlew :blip:shared:dependencies --configuration iosArm64CompileKlibraries --console=plain
plutil -lint apps/rayl/iosApp/iosApp/Info.plist apps/blip/iosApp/iosApp/Info.plist
```

For an existing archive, use `shasum -a 256` on the app executable,
`dwarfdump --uuid` on both the executable and dSYM, and `nm` on the matching dSYM
to investigate compiled functions. Inspect source and link evidence together:
unused source, Debug symbols, and final Release symbols answer different
questions. Keep credentials, invoices, and other wallet data out of this record.

[apple-table]: https://developer.apple.com/help/app-store-connect/reference/app-information/export-compliance-documentation-for-encryption/
[apple-process]: https://developer.apple.com/help/app-store-connect/manage-app-information/determine-and-upload-app-encryption-documentation/
[apple-security]: https://developer.apple.com/documentation/security/complying-with-encryption-export-regulations
[apple-key]: https://developer.apple.com/documentation/bundleresources/information-property-list/itsappusesnonexemptencryption
[apple-overview]: https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance/
[apple-support]: https://developer.apple.com/contact/topic/SC1110/subtopic/30067/solution/select
[bis-confidentiality]: https://www.bis.gov/learn-support/encryption-controls/cryptography-for-data-confidentiality
[bis-nonstandard]: https://www.bis.gov/learn-support/encryption-controls/license-exception-enc-740.17-b-3
[bis-overview]: https://www.bis.gov/learn-support/encryption-controls
[bis-enc]: https://www.bis.gov/learn-support/encryption-controls/license-exception-enc-740.17-b-1
[bis-report]: https://www.bis.gov/learn-support/encryption-controls/annual-self-classification
[anssi-procedure]: https://cyber.gouv.fr/reglementation/reglementation-identite-confiance-numerique/controles-reglementaires-cryptographie/controle-moyen-de-cryptologie/
[france-decree]: https://www.legifrance.gouv.fr/loda/id/JORFTEXT000000646995/
[acinq-invoice]: https://github.com/ACINQ/lightning-kmp/blob/956299158b83023485001d8d6f66f6b5dc8ee366/modules/core/src/commonMain/kotlin/fr/acinq/lightning/payment/Bolt11Invoice.kt
[acinq-digest]: https://github.com/ACINQ/bitcoin-kmp/blob/1c49b7a1f08309688d3ce793097bebad5bb4d863/src/iosMain/kotlin/fr/acinq/bitcoin/crypto/Digest.ios.kt
[acinq-offer]: https://github.com/ACINQ/lightning-kmp/blob/956299158b83023485001d8d6f66f6b5dc8ee366/modules/core/src/commonMain/kotlin/fr/acinq/lightning/wire/OfferTypes.kt
[acinq-secp]: https://github.com/ACINQ/secp256k1-kmp/blob/a8117f92cb02bb07f7b8051d19bfddce74532b48/src/nativeMain/kotlin/fr/acinq/secp256k1/Secp256k1Native.kt
[acinq-chacha]: https://github.com/ACINQ/lightning-kmp/blob/956299158b83023485001d8d6f66f6b5dc8ee366/modules/core/src/commonMain/kotlin/fr/acinq/lightning/crypto/Chacha20Poly1305.kt
[acinq-apple-cipher]: https://github.com/ACINQ/lightning-kmp/blob/956299158b83023485001d8d6f66f6b5dc8ee366/modules/ios-crypto/PhoenixCrypto/Classes/NativeChaChaPoly.swift
[apollo-source]: https://github.com/apollographql/apollo-kotlin/blob/0086a707c19aad5c4294c0333365621fe887ee34/libraries/apollo-runtime/src/appleMain/kotlin/com/apollographql/apollo/network/http/DefaultHttpEngine.apple.kt
[ktor-source]: https://github.com/ktorio/ktor/blob/5ba9d6fdf1ea9acfac5de67e7fe5a072639eac64/ktor-client/ktor-client-darwin/darwin/src/io/ktor/client/engine/darwin/internal/DarwinSession.kt
[nwc-crypto]: https://github.com/NicolaLS/nostr-kmp/blob/e2fe4368c3791cc04c06085c176974cf145f404b/nostr-crypto/src/commonMain/kotlin/io/github/nicolals/nostr/crypto/NostrCrypto.kt
[nwc-aes]: https://github.com/NicolaLS/nostr-kmp/blob/e2fe4368c3791cc04c06085c176974cf145f404b/nostr-crypto/src/iosMain/kotlin/io/github/nicolals/nostr/crypto/Aes256Cbc.kt
[nwc-signing]: https://github.com/NicolaLS/nostr-kmp/blob/41ded2cfb5713dbf65aada4b909ef99eafc57e1d/nostr-crypto/src/commonMain/kotlin/io/github/nicolals/nostr/crypto/NostrSigning.kt
[nwc-nip04]: https://github.com/NicolaLS/nostr-kmp/blob/5464c6192b08c34816e44a0d1fbdfb6bd39bd608/nips/nip04/src/commonMain/kotlin/io/github/nicolals/nostr/nip04/Nip04Module.kt
[nwc-nip44]: https://github.com/NicolaLS/nostr-kmp/blob/b9dd2ff4054f1a5813903e8eee80780f3fdc89ca/nips/nip44/src/commonMain/kotlin/io/github/nicolals/nostr/nip44/Nip44Module.kt
