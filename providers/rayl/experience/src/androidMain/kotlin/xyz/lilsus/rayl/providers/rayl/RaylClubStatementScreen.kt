package xyz.lilsus.rayl.providers.rayl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.lilsus.rayl.providers.rayl.experience.R
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubStatementState
import xyz.lilsus.rayl.providers.rayl.integration.club.RaylClub
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.raylapi.ClubStatement
import xyz.lilsus.raylsuite.core.raylapi.ClubStatementLine
import xyz.lilsus.raylsuite.core.raylapi.ClubStatementLineKind
import xyz.lilsus.raylsuite.core.ui.components.BackIconButton
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter

/** The latest statement and what happened since (US-20). Plain screen until designs exist. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RaylClubStatementScreen(club: RaylClub, onBack: () -> Unit) {
    val state by club.statement.collectAsStateWithLifecycle()
    LaunchedEffect(club) { club.loadStatement() }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.rayl_club_statement)) },
                navigationIcon = { BackIconButton(onClick = onBack) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (val current = state) {
                ClubStatementState.Loading -> CircularProgressIndicator()

                is ClubStatementState.Failed -> {
                    Text(
                        text = stringResource(R.string.rayl_club_error_unavailable),
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(onClick = club::loadStatement) {
                        Text(stringResource(R.string.rayl_retry))
                    }
                }

                is ClubStatementState.Loaded -> Statement(current.statement)
            }
        }
    }
}

@Composable
private fun Statement(content: ClubStatement) {
    val context = LocalContext.current
    val statement = content.statement
    if (statement == null) {
        Text(
            text = stringResource(R.string.rayl_club_statement_none),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        clubEpochSeconds(statement.periodEnd)?.let {
            Section(stringResource(R.string.rayl_club_statement_closed, mediumDate(context, it)))
        }
        StatementStatus(statement)
        Lines(content.lines)
    }
    clubEpochSeconds(content.currentPeriodStart)?.let {
        Section(stringResource(R.string.rayl_club_statement_since, mediumDate(context, it)))
    }
    Lines(content.currentLines)
}

@Composable
private fun Section(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    )
}

@Composable
private fun Lines(lines: List<ClubStatementLine>) {
    val context = LocalContext.current
    val formatter = rememberAmountFormatter()
    if (lines.isEmpty()) {
        Text(
            text = stringResource(R.string.rayl_club_statement_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
    }
    lines.forEach { line ->
        val repayment = line.kind == ClubStatementLineKind.REPAYMENT
        val amount = formatter.format(DisplayAmount(line.amountSat, DisplayCurrency.Satoshi))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    stringResource(
                        if (repayment) {
                            R.string.rayl_club_statement_repayment
                        } else {
                            R.string.rayl_club_statement_spend
                        }
                    )
                )
                clubEpochSeconds(line.at)?.let {
                    Text(
                        text = mediumDate(context, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(if (repayment) "−$amount" else amount)
        }
    }
}
