package xyz.lilsus.flint.feature.walletconnection

import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

data class NativeFlintWalletConnectionText(
    val importTitle: String,
    val importBody: String,
    val phraseLabel: String,
    val phraseHint: String,
    val storageNote: String,
    val importAction: String,
    val removeTitle: String,
    val removeBody: String,
    val removeConfirm: String,
    val cancel: String,
    val retry: String,
    val resetAction: String,
    val loading: String,
    val connecting: String,
    val removing: String,
    val working: String,
    val reconnectTitle: String,
    val reconnectBody: String,
    val resetTitle: String,
    val resetBody: String,
    val credentialTitle: String,
    val credentialUnavailable: String,
    val credentialInvalidated: String,
    val credentialCorrupt: String,
    val errorAlreadyConfigured: String,
    val errorInvalidMnemonic: String,
    val errorConnection: String,
    val errorStorage: String,
    val errorReset: String
)

suspend fun nativeFlintWalletConnectionText(appName: String): NativeFlintWalletConnectionText =
    NativeFlintWalletConnectionText(
        importTitle = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_import_title")
        ),
        importBody = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_import_body"),
            appName
        ),
        phraseLabel = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_phrase_label")
        ),
        phraseHint = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_phrase_hint")
        ),
        storageNote = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_storage_note")
        ),
        importAction = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_import_action")
        ),
        removeTitle = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_remove_title"),
            appName
        ),
        removeBody = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_remove_body")
        ),
        removeConfirm = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_remove_confirm")
        ),
        cancel = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_cancel")
        ),
        retry = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_retry")
        ),
        resetAction = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_reset_action"),
            appName.uppercase()
        ),
        loading = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_progress_loading")
        ),
        connecting = nativeString(
            NativeStringResource(
                table = "FlintWalletConnection",
                key = "wallet_progress_connecting"
            )
        ),
        removing = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_progress_removing")
        ),
        working = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_progress_working")
        ),
        reconnectTitle = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_reconnect_title")
        ),
        reconnectBody = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_reconnect_body"),
            appName
        ),
        resetTitle = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_reset_title")
        ),
        resetBody = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_reset_body")
        ),
        credentialTitle = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_credential_title")
        ),
        credentialUnavailable = nativeString(
            NativeStringResource(
                table = "FlintWalletConnection",
                key = "wallet_credential_unavailable"
            )
        ),
        credentialInvalidated = nativeString(
            NativeStringResource(
                table = "FlintWalletConnection",
                key = "wallet_credential_invalidated"
            )
        ),
        credentialCorrupt = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_credential_corrupt")
        ),
        errorAlreadyConfigured = nativeString(
            NativeStringResource(
                table = "FlintWalletConnection",
                key = "wallet_error_already_configured"
            )
        ),
        errorInvalidMnemonic = nativeString(
            NativeStringResource(
                table = "FlintWalletConnection",
                key = "wallet_error_invalid_mnemonic"
            )
        ),
        errorConnection = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_error_connection")
        ),
        errorStorage = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_error_storage")
        ),
        errorReset = nativeString(
            NativeStringResource(table = "FlintWalletConnection", key = "wallet_error_reset")
        )
    )
