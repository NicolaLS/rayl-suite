package xyz.lilsus.raylsuite.feature.walletmanagement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.lilsus.raylsuite.core.ui.components.BackIconButton
import xyz.lilsus.raylsuite.feature.walletmanagement.R

@Immutable
data class ManagedWallet(val id: String, val title: String, val details: List<String> = emptyList())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletManagementScreen(
    wallet: ManagedWallet?,
    onBack: () -> Unit,
    onAddWallet: () -> Unit,
    onRemoveWallet: () -> Unit,
    onWalletDetails: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.testTag(WalletManagementTestTags.SCREEN),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings_manage_wallet_title)) },
                navigationIcon = { BackIconButton(onClick = onBack) },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
        ) {
            wallet?.let {
                WalletCard(
                    wallet = it,
                    onDetails = onWalletDetails,
                    onRemoveWallet = onRemoveWallet,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            } ?: EmptyWalletState(onAddWallet)
        }
    }
}

@Composable
private fun WalletCard(
    wallet: ManagedWallet,
    onDetails: (() -> Unit)?,
    onRemoveWallet: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(WalletManagementTestTags.walletRow(wallet.id))
                .then(
                    if (onDetails == null) {
                        Modifier
                    } else {
                        Modifier.clickable(onClick = onDetails)
                    }
                ),
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = wallet.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (onDetails != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            wallet.details.forEach { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(WalletManagementTestTags.removeButton(wallet.id)),
                onClick = onRemoveWallet,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.settings_manage_wallet_remove))
            }
        }
    }
}

@Composable
private fun EmptyWalletState(onAddWallet: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag(WalletManagementTestTags.EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.settings_manage_wallet_placeholder),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        )
        Button(
            modifier = Modifier.testTag(WalletManagementTestTags.ADD_BUTTON),
            onClick = onAddWallet
        ) {
            Text(stringResource(R.string.settings_manage_wallet_add))
        }
    }
}

object WalletManagementTestTags {
    const val SCREEN = "settings_manage_wallet_screen"
    const val ADD_BUTTON = "settings_manage_wallet_add_button"
    const val EMPTY = "settings_manage_wallet_empty"

    fun walletRow(id: String): String = "settings_wallet_row_$id"

    fun removeButton(id: String): String = "settings_wallet_remove_$id"
}
