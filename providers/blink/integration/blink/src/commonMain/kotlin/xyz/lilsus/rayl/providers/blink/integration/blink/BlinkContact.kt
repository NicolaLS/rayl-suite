package xyz.lilsus.rayl.providers.blink.integration.blink

data class BlinkContact(
    val handle: String,
    val alias: String?,
    val transactionsCount: Int,
    val lightningAddressDomain: String
)
