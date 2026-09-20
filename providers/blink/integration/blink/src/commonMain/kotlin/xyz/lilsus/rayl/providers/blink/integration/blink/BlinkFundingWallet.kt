package xyz.lilsus.rayl.providers.blink.integration.blink

enum class BlinkWalletCurrency {
    BTC,
    USD
}

data class BlinkFundingWallet(val id: String, val currency: BlinkWalletCurrency) {
    init {
        require(id.isNotBlank()) { "A Blink funding wallet ID cannot be blank" }
    }
}

sealed interface BlinkPaymentAmount {
    data class Bitcoin(val milliSatoshis: Long) : BlinkPaymentAmount {
        init {
            require(milliSatoshis > 0L) { "A Bitcoin payment amount must be greater than zero" }
        }
    }

    data class Usd(val cents: Long) : BlinkPaymentAmount {
        init {
            require(cents > 0L) { "A USD payment amount must be greater than zero" }
        }
    }
}
