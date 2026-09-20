package xyz.lilsus.rayl.providers.blink.feature.blinkcontacts

import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkContact
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkWallet
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.feature.paymenthub.PaymentHubRepository

/** Imports every valid Blink contact that is not already in the app's local Hub. */
class BlinkContactsImporter internal constructor(
    private val fetchContacts: suspend () -> List<BlinkContact>,
    private val hubRepository: PaymentHubRepository
) {
    constructor(
        blinkWallet: BlinkWallet,
        hubRepository: PaymentHubRepository
    ) : this(blinkWallet::fetchContacts, hubRepository)

    suspend fun importAll() {
        val existingAddresses =
            hubRepository.hub.value.contacts
                .map { it.address.importKey() }
                .toMutableSet()

        fetchContacts().forEach { contact ->
            val rawHandle = contact.handle.trim()
            if (rawHandle.isBlank()) return@forEach
            val address =
                LightningAddress.parse(
                    if ('@' in rawHandle) {
                        rawHandle
                    } else {
                        "$rawHandle@${contact.lightningAddressDomain}"
                    }
                ) ?: return@forEach
            if (!existingAddresses.add(address.importKey())) return@forEach

            hubRepository.saveContact(
                address = address,
                title = contact.alias?.trim()?.takeIf(String::isNotEmpty)
            )
        }
    }
}

private fun LightningAddress.importKey(): String = full.trim().lowercase()
