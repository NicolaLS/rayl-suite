package xyz.lilsus.raylsuite.backend.club

import io.ktor.server.application.Application
import io.ktor.server.application.log
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Serves Rayl Club and starts it in the background: the schema once Postgres answers, then the
 * treasury (if `TREASURY_MNEMONIC` is set), which activates memberships as fees arrive and records
 * repayments. Statements close every month once the schema is applied. Until the treasury is up,
 * joins that need a fee and repayments answer `club_unavailable`; fee-waived joins work.
 */
fun Application.startClub(
    config: ClubConfig,
    treasuryConfig: TreasuryConfig?,
    btcUsd: () -> BigDecimal?
) {
    val database = ClubDatabase.connect(config.database)
    val treasury = AtomicReference<Treasury?>(null)
    val memberships =
        MembershipStore(database, treasury::get, btcUsd, config.joinPlatforms, config.paused)
    val credit = CreditStore(database, treasury::get, config.paused, config.exposureCapSat)
    val statements = StatementStore(database, treasury::get)
    if (config.paused) log.warn("Rayl Club is paused: no new credit spends or joins")
    if (config.exposureCapSat == null) {
        log.warn("Rayl Club credit is off: CLUB_EXPOSURE_CAP_SAT is not set")
    }
    club(
        ClubStore(database, config.allowTestTiers),
        memberships,
        credit,
        statements,
        ClubTokenVerifier.forSupabase(config),
        config.operatorToken
    )
    launch {
        database.applySchemaWhenReachable { log.warn("Club schema not applied yet: ${it.message}") }
        log.info("Rayl Club schema applied")
        launch { closeStatements(statements) }
        if (treasuryConfig == null) {
            log.warn("Rayl Club treasury disabled: TREASURY_MNEMONIC is not set")
            return@launch
        }
        val connected = connectWithRetry(treasuryConfig)
        connected.watchPaidInvoices { invoice ->
            val result = memberships.feePaid(invoice)
            if (result != FeePaidResult.Unknown) log.info("Club fee payment: $result")
            if (result ==
                FeePaidResult.Overpaid
            ) {
                log.warn("Club fee paid by an existing member: $invoice")
            }
            if (result == FeePaidResult.Unknown) {
                val repaid = statements.repaymentPaid(invoice)
                if (repaid != RepaymentPaidResult.Unknown) log.info("Club repayment: $repaid")
            }
        }
        treasury.set(connected)
        log.info("Rayl Club treasury connected on ${treasuryConfig.network}")
        reconcileSpends(credit)
    }
}

/** Closes statements whose month has ended, every ten minutes; a restart catches up. */
private suspend fun Application.closeStatements(statements: StatementStore) {
    while (true) {
        try {
            val closed = statements.closeDue()
            if (closed > 0) log.info("Club statements closed: $closed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Club statement close failed: ${e.message}")
        }
        delay(10.minutes)
    }
}

/** Resolves credit spends left `paying` (after a restart or an unclear send) every minute. */
private suspend fun Application.reconcileSpends(credit: CreditStore) {
    while (true) {
        try {
            val resolved = credit.reconcile()
            if (resolved > 0) log.info("Club credit spends reconciled: $resolved")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Club credit reconciliation failed: ${e.message}")
        }
        delay(1.minutes)
    }
}

private suspend fun Application.connectWithRetry(config: TreasuryConfig): Treasury {
    while (true) {
        try {
            return BreezTreasury.connect(config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Club treasury not connected yet: ${e.message}")
            delay(30.seconds)
        }
    }
}
