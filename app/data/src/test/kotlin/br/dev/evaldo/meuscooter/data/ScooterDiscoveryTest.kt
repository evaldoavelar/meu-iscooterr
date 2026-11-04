package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Etapa 4.1: só varredura. O nome do anúncio do patinete é `[DESCONHECIDO]` (docs/RESUME.md, test-plan H9): os nomes abaixo são FICTÍCIOS de teste. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScooterDiscoveryTest {
    private fun r(name: String?, addr: String, rssi: Int) = ScanResult(name, addr, rssi)

    @Test fun configuredFilterKeepsOnlyMatchingNamesIgnoringCase() {
        val c = ScooterDiscovery.candidates(
            listOf(r("TESTSC-01", "A", -60), r("testsc-02", "B", -50), r("Headphones", "C", -40), r(null, "D", -30)),
            NameFilter(prefixes = listOf("TESTSC")),
        )
        assertEquals(listOf("B", "A"), c.map { it.address }) // mais forte primeiro
        assertTrue(c.all { it.recognized })
    }

    @Test fun unknownFilterListsEveryNamedDeviceAsUnrecognised() {
        val c = ScooterDiscovery.candidates(listOf(r("X", "A", -70), r("", "B", -20), r(null, "C", -10), r("Y", "D", -50)), NameFilter())
        assertEquals(listOf("D", "A"), c.map { it.address })
        assertTrue(c.none { it.recognized })
    }

    @Test fun repeatedAdvertisementsAreMergedByAddressKeepingTheLatest() {
        val c = ScooterDiscovery.candidates(
            listOf(r("TESTSC", "A", -80), r(null, "A", -60), r("TESTSC", "A", -55)),
            NameFilter(listOf("TESTSC")),
        )
        assertEquals(listOf(ScanCandidate("TESTSC", "A", -55, recognized = true)), c)
    }

    @Test fun anonymousLaterAdvertisementDoesNotEraseTheName() {
        val c = ScooterDiscovery.candidates(listOf(r("TESTSC", "A", -80), r(null, "A", -60)), NameFilter(listOf("TESTSC")))
        assertEquals("TESTSC", c.single().name)
        assertEquals(-60, c.single().rssi)
    }

    @Test fun ownerMustChooseUnlessExactlyOneRecognisedCandidate() {
        val f = NameFilter(listOf("TESTSC"))
        assertFalse(DiscoveryOutcome(ScooterDiscovery.candidates(listOf(r("TESTSC", "A", -50)), f), null).needsOwnerChoice)
        assertTrue(DiscoveryOutcome(ScooterDiscovery.candidates(listOf(r("TESTSC", "A", -50), r("TESTSC2", "B", -60)), f), null).needsOwnerChoice)
        assertTrue(DiscoveryOutcome(ScooterDiscovery.candidates(listOf(r("Qualquer", "A", -50)), NameFilter()), null).needsOwnerChoice)
        assertTrue(DiscoveryOutcome(emptyList(), null).none)
    }

    @Test fun discoverCollectsForTheWindowAndStopsTheScan() = runTest {
        val scanner = FakeScooterScanner(listOf(1000L to r("TESTSC", "A", -60), 1000L to r("Outro", "Z", -10), 1000L to r("TESTSC", "B", -50)))
        val out = ScooterDiscovery.discover(scanner, NameFilter(listOf("TESTSC")), Clock { currentTime }, windowMs = 5000)
        assertEquals(5000L, currentTime)
        assertEquals(listOf("B", "A"), out.candidates.map { it.address })
        assertEquals(1000L, out.timeToFirstCandidateMs)
        assertNull(out.error)
        assertEquals(1, scanner.starts)
        assertEquals(1, scanner.stops)
    }

    @Test fun noCandidateMeansNoTimeToFirst() = runTest {
        val out = ScooterDiscovery.discover(FakeScooterScanner(emptyList()), NameFilter(listOf("TESTSC")), Clock { currentTime }, windowMs = 2000)
        assertTrue(out.none)
        assertNull(out.timeToFirstCandidateMs)
    }

    @Test fun scanFailureIsTyped() = runTest {
        val scanner = FakeScooterScanner(emptyList(), failWith = ScanException(ScanError.PermissionDenied))
        val out = ScooterDiscovery.discover(scanner, NameFilter(), Clock { currentTime }, windowMs = 2000)
        assertEquals(ScanError.PermissionDenied, out.error)
        assertTrue(out.none)
        assertEquals(1, scanner.stops)
    }
}
