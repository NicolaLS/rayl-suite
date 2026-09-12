package xyz.lilsus.raylsuite.feature.currencysettings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.ui.components.AppListDefaults
import xyz.lilsus.raylsuite.core.ui.components.AppListScaffold
import xyz.lilsus.raylsuite.core.ui.components.AppSelectableListRow
import xyz.lilsus.raylsuite.core.ui.components.BackIconButton
import xyz.lilsus.raylsuite.core.ui.theme.RaylSuiteTheme
import xyz.lilsus.raylsuite.feature.currencysettings.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencySettingsScreen(
    state: CurrencySettingsUiState,
    onQueryChange: (String) -> Unit,
    onCurrencySelected: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings_currency)) },
                navigationIcon = {
                    BackIconButton(onClick = onBack)
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        CurrencyPicker(
            selectedCode = state.selectedCode,
            searchQuery = state.searchQuery,
            onQueryChange = onQueryChange,
            onCurrencySelected = onCurrencySelected,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .navigationBarsPadding()
                    .padding(AppListDefaults.ScreenPadding)
        )
    }
}

@Composable
fun CurrencyPicker(
    selectedCode: String,
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onCurrencySelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val options =
        CurrencyCatalog.supported.map { info ->
            CurrencyOption(
                code = info.code,
                label = stringResource(currencyNameResource(info.code))
            )
        }
    val filtered =
        options.filter { option ->
            option.label.contains(searchQuery, ignoreCase = true)
        }

    AppListScaffold(
        isEmpty = filtered.isEmpty(),
        emptyMessage = null,
        modifier = modifier,
        showSearchBar = true,
        searchQuery = searchQuery,
        onSearchQueryChange = onQueryChange,
        searchPlaceholder = stringResource(R.string.search_placeholder)
    ) {
        items(filtered, key = CurrencyOption::code) { option ->
            AppSelectableListRow(
                title = option.label,
                selected = selectedCode == option.code,
                onClick = { onCurrencySelected(option.code) }
            )
        }
    }
}

private data class CurrencyOption(val code: String, val label: String)

@StringRes
private fun currencyNameResource(code: String): Int = when (code) {
    "SAT" -> R.string.settings_currency_satoshi
    "USD" -> R.string.settings_currency_usd
    "EUR" -> R.string.settings_currency_eur
    "GBP" -> R.string.settings_currency_gbp
    "CAD" -> R.string.settings_currency_cad
    "AUD" -> R.string.settings_currency_aud
    "CHF" -> R.string.settings_currency_chf
    "JPY" -> R.string.settings_currency_jpy
    else -> R.string.settings_currency_satoshi
}

@Preview
@Composable
private fun CurrencySettingsScreenPreview() {
    RaylSuiteTheme {
        CurrencySettingsScreen(
            state = CurrencySettingsUiState(),
            onQueryChange = {},
            onCurrencySelected = {},
            onBack = {}
        )
    }
}
