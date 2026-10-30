package xyz.lilsus.raylsuite.backend.club

import io.ktor.server.application.Application
import io.ktor.server.application.log
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Serves Rayl Club and starts it in the background: the schema once Postgres answers, then the
 * treasury (if `TREASURY_MNEMONIC` is set), which activates memberships as fees arrive. Until the
 * treasury is up, joins that need a fee answer `club_unavailable`; fee-waived joins work.
 */
fun Application.startClub(
    config: ClubConfig,
    treasuryConfig: TreasuryConfig?,
    btcUsd: () -> BigDecimal?
) {
    val database = ClubDatabase.connect(config.database)
    val treasury = AtomicReference<Treasury?>(null)
    val memberships = MembershipStore(database, treasury::get, btcUsd, config.joinPlatforms)
    club(
        ClubStore(database, config.allowTestTiers),
        memberships,
        ClubTokenVerifier.forSupabase(config),
        config.operatorToken
    )
    launch {
        database.applySchemaWhenReachable { log.warn("Club schema not applied yet: ${it.message}") }
        log.info("Rayl Club schema applied")
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
        }
        treasury.set(connected)
        log.info("Rayl Club treasury connected on ${treasuryConfig.network}")
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
