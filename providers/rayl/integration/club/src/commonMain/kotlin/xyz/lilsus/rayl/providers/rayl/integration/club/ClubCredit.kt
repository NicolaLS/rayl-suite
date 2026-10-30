package xyz.lilsus.rayl.providers.rayl.integration.club

/**
 * Paying invoices with Club credit. Rayl tries credit first and pays from the pocket whenever
 * credit can't (D-19); there is nothing for the member to choose.
 */
interface ClubCredit {
    /** Whether credit can pay [invoice] now. Reserves nothing. */
    suspend fun quote(invoice: String, amountMsat: Long?): ClubCreditQuote

    /**
     * Pays a quoted invoice from the treasury and charges the member's line. Retrying with the
     * same [idempotencyKey] never pays twice.
     */
    suspend fun spend(
        invoice: String,
        amountMsat: Long?,
        maxFeeMsat: Long,
        idempotencyKey: String
    ): ClubCreditOutcome
}

sealed interface ClubCreditQuote {
    data class Quoted(val amountMsat: Long, val feeMsat: Long) : ClubCreditQuote

    /**
     * Credit can't pay this invoice now: signed out, not a member, over the limit, a Club
     * invoice, the Club paused or unreachable. The pocket pays instead.
     */
    data object NotUsable : ClubCreditQuote

    /** The invoice itself can't be paid, so the pocket couldn't pay it either. */
    data class InvoiceRejected(val expired: Boolean) : ClubCreditQuote
}

sealed interface ClubCreditOutcome {
    data class Paid(val feeMsat: Long, val preimage: String?) : ClubCreditOutcome

    /** Definitively not paid. */
    data object Failed : ClubCreditOutcome

    /** May or may not have paid; the Club keeps the amount reserved until it knows. */
    data object Unknown : ClubCreditOutcome
}
