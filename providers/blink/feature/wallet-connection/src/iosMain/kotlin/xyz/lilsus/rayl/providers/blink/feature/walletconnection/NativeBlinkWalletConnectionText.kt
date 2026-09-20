package xyz.lilsus.rayl.providers.blink.feature.walletconnection

import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

data class NativeBlinkWalletConnectionText(
    val title: String,
    val description: String,
    val instructions: String,
    val connectionNotice: String,
    val privacy: String,
    val terms: String,
    val apiKeyLabel: String,
    val apiKeyPlaceholder: String,
    val showApiKey: String,
    val hideApiKey: String,
    val paste: String,
    val connect: String
)

suspend fun nativeBlinkWalletConnectionText(appName: String): NativeBlinkWalletConnectionText =
    NativeBlinkWalletConnectionText(
        title = nativeString(
            NativeStringResource(table = "BlinkWalletConnection", key = "add_blink_wallet_title")
        ),
        description = nativeString(
            NativeStringResource(
                table = "BlinkWalletConnection",
                key = "add_blink_wallet_description"
            ),
            appName
        ),
        instructions = nativeString(
            NativeStringResource(
                table = "BlinkWalletConnection",
                key = "add_blink_wallet_instructions"
            )
        ),
        connectionNotice = nativeString(
            NativeStringResource(
                table = "BlinkWalletConnection",
                key = "add_blink_wallet_connection_notice"
            )
        ),
        privacy = nativeString(
            NativeStringResource(table = "BlinkWalletConnection", key = "add_blink_wallet_privacy")
        ),
        terms = nativeString(
            NativeStringResource(table = "BlinkWalletConnection", key = "add_blink_wallet_terms")
        ),
        apiKeyLabel = nativeString(
            NativeStringResource(
                table = "BlinkWalletConnection",
                key = "add_blink_wallet_api_key_label"
            )
        ),
        apiKeyPlaceholder = nativeString(
            NativeStringResource(
                table = "BlinkWalletConnection",
                key = "add_blink_wallet_api_key_placeholder"
            )
        ),
        showApiKey = nativeString(
            NativeStringResource(
                table = "BlinkWalletConnection",
                key = "add_blink_wallet_show_api_key"
            )
        ),
        hideApiKey = nativeString(
            NativeStringResource(
                table = "BlinkWalletConnection",
                key = "add_blink_wallet_hide_api_key"
            )
        ),
        paste = nativeString(
            NativeStringResource(table = "BlinkWalletConnection", key = "add_blink_wallet_paste")
        ),
        connect = nativeString(
            NativeStringResource(table = "BlinkWalletConnection", key = "add_blink_wallet_connect")
        )
    )
