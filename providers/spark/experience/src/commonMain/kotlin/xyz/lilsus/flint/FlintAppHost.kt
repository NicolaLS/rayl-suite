package xyz.lilsus.flint

import xyz.lilsus.flint.application.AppBootstrapConfig
import xyz.lilsus.flint.application.AppEnvironment
import xyz.lilsus.flint.application.payment.PaymentLinkInbox
import xyz.lilsus.flint.application.payment.createPaymentLinkInbox
import xyz.lilsus.flint.application.wallet.WalletAccess

enum class FlintEnvironment {
    DEBUG,
    PRODUCTION
}

class FlintAppHost internal constructor(
    internal val bootstrapConfig: AppBootstrapConfig,
    internal val walletAccess: WalletAccess,
    internal val paymentLinks: PaymentLinkInbox = createPaymentLinkInbox()
) {
    fun offerPaymentLink(rawUrl: String) {
        paymentLinks.offer(rawUrl)
    }

    override fun toString(): String = "FlintAppHost(<opaque>)"
}

internal fun FlintEnvironment.toAppEnvironment(): AppEnvironment = when (this) {
    FlintEnvironment.DEBUG -> AppEnvironment.DEBUG
    FlintEnvironment.PRODUCTION -> AppEnvironment.PRODUCTION
}
