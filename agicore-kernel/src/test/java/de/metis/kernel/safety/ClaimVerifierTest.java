package de.metis.kernel.safety;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClaimVerifier} — the factuality gate that signals
 * "re-think" when availability/price claims lack evidence or a hedge.
 *
 * <p>Mirrors the Oxiis-E250G1 failure as regression cases:
 * marketing-copy assertions must be flagged; hedged or evidenced
 * statements must pass.
 */
class ClaimVerifierTest {

    private final ClaimVerifier verifier = new ClaimVerifier();

    @Test
    void cleanOnEmptyOrBlank() {
        assertFalse(verifier.verify(null).needsRethink());
        assertFalse(verifier.verify("").needsRethink());
        assertFalse(verifier.verify("   \n  ").needsRethink());
    }

    @Test
    void cleanWhenNoFactualAssertion() {
        assertFalse(verifier.verify("Ich prüfe den Systemstatus.").needsRethink());
        assertFalse(verifier.verify("Wetter heute: 21 Grad, sonnig.").needsRethink());
    }

    @Test
    void flagsUnsupportedAvailabilityClaim() {
        // Regression: "easy install now for riders" → treated as "lieferbar"
        ClaimVerifier.ClaimVerification r = verifier.verify(
                "Das ASUS Oxiis E250G1 ist bereits lieferbar und sofort bestellbar.");
        assertTrue(r.needsRethink());
        assertFalse(r.flaggedClaims().isEmpty());
    }

    @Test
    void flagsUnsupportedPriceClaim() {
        ClaimVerifier.ClaimVerification r = verifier.verify(
                "Das Kit kostet 599 € und kann man jetzt kaufen.");
        assertTrue(r.needsRethink());
    }

    @Test
    void passesHedgedClaim() {
        assertFalse(verifier.verify(
                "Das Produkt ist noch nicht lieferbar, kein Preis bekannt.").needsRethink());
        assertFalse(verifier.verify(
                "Preis ist eine Schätzung von ca. 500–600 €.").needsRethink());
        assertFalse(verifier.verify(
                "Marktstart später im Jahr 2026, Preis nicht veröffentlicht.").needsRethink());
    }

    @Test
    void passesEvidencedClaim() {
        assertFalse(verifier.verify(
                "Verfügbar laut Quelle: https://example.com/produkt — Status 200 geprüft.").needsRethink());
    }

    @Test
    void metricsCountFlags() {
        verifier.verify("Produkt ist lieferbar und bestellbar.");
        verifier.verify("Produkt ist lieferbar und bestellbar.");
        verifier.verify("Das ist nur ein Test ohne Behauptung.");
        assertTrue(verifier.flaggedOutputs() >= 2);
        assertTrue(verifier.verifiedOutputs() >= 1);
        assertTrue(verifier.flagRate() > 0.0);
    }
}
