package xyz.lilsus.raylsuite.feature.walletmanagement

import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

data class NativeWalletManagementText(
    val removeTitle: String,
    val removeConfirmationTitle: String,
    val removeConfirmationBody: String,
    val cancelTitle: String
)

suspend fun nativeWalletManagementText(): NativeWalletManagementText = NativeWalletManagementText(
    removeTitle = nativeString(
        NativeStringResource(table = "WalletManagement", key = "settings_manage_wallet_remove")
    ),
    removeConfirmationTitle =
        nativeString(
            NativeStringResource(
                table = "WalletManagement",
                key = "settings_manage_wallet_remove_confirmation_title"
            )
        ),
    removeConfirmationBody =
        nativeString(
            NativeStringResource(
                table = "WalletManagement",
                key = "settings_manage_wallet_remove_confirmation_body"
            )
        ),
    cancelTitle = nativeString(
        NativeStringResource(table = "WalletManagement", key = "settings_manage_wallet_cancel")
    )
)
