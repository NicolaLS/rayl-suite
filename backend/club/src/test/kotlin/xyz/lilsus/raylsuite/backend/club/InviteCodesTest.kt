package xyz.lilsus.raylsuite.backend.club

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InviteCodesTest {
    @Test
    fun generatedCodesAreNormalized() {
        repeat(100) {
            val code = InviteCodes.generate()
            assertEquals(code, InviteCodes.normalize(code))
            assertEquals(code, InviteCodes.normalize(InviteCodes.format(code)))
        }
    }

    @Test
    fun normalizeAcceptsHowPeopleTypeCodes() {
        assertEquals("ABCDEFGHJKMN", InviteCodes.normalize(" abcd-efgh jkmn "))
        assertEquals("ABCD-EFGH-JKMN", InviteCodes.format("ABCDEFGHJKMN"))
    }

    @Test
    fun normalizeRejectsWhatCannotBeACode() {
        assertNull(InviteCodes.normalize("ABCD-EFGH-JKM"))
        assertNull(InviteCodes.normalize("ABCD-EFGH-JKMNP"))
        assertNull(InviteCodes.normalize("ABCD-EFGH-JKM0"))
        assertNull(InviteCodes.normalize("ABCD-EFGH-JKMI"))
        assertTrue(listOf('0', '1', 'I', 'L', 'O', 'U').none { it in InviteCodes.ALPHABET })
    }
}

class ClubTiersTest {
    @Test
    fun theTestTierIsOnlyOfferedWhereTestTiersAreAllowed() {
        assertEquals(setOf("member", "plus"), inviteTierCodes(allowTestTiers = false))
        assertEquals(setOf("member", "plus", "test"), inviteTierCodes(allowTestTiers = true))
        assertEquals(1_000, feeSats(clubTier("test")!!.feeUsdCents, BigDecimal(100_000)))
    }
}
