package xyz.lilsus.rayl.providers.nwc.feature.walletconnection

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import xyz.lilsus.rayl.providers.nwc.feature.walletconnection.R
import xyz.lilsus.rayl.providers.nwc.integration.nwc.NwcConnectionError

@Composable
fun nwcConnectionErrorMessageFor(error: NwcConnectionError): String = when (error) {
    NwcConnectionError.AlreadyConnected ->
        stringResource(R.string.error_wallet_already_connected)

    NwcConnectionError.InvalidUri ->
        stringResource(R.string.error_invalid_wallet_uri)

    NwcConnectionError.RequiredMethodsMissing ->
        stringResource(
            R.string.connect_wallet_required_methods,
            xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
        )

    is NwcConnectionError.ConnectionFailed ->
        stringResource(R.string.error_relay_connection_failed)
}
