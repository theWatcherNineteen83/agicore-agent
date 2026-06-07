package de.metis.kernel.safety;

import de.metis.kernel.world.Belief;

import java.util.*;
import java.util.function.Supplier;

/**
 * Phase 11.5 — Ethics Retriever (Sprint #2, 07.06.2026).
 *
 * <p>Filtert Beliefs nach dem {@link EthicsCore#SOURCE_PREFIX}-Tag und
 * liefert sie an SelfReflector / EthicsScorer / SystemPromptBuilder.
 *
 * <p>Bewusst minimal: kein Embedding-Lookup, keine LLM-Calls. Der
 * Retriever ist eine reine View auf den Belief-Store, damit
 * Bootstrapping (Sutta-Ingest fertig?) und Self-Reflection
 * (welche Verse sind relevant?) auch ohne aktive Ollama-Verbindung
 * funktionieren.
 *
 * <p>Wer semantische Suche \u00fcber Suttas will, nutzt den vorhandenen
 * {@code HybridSearchService} mit Filter auf {@code source} startsWith
 * {@link EthicsCore#SOURCE_PREFIX}.
 */
public class EthicsRetriever {

    private final Supplier<List<Belief>> allBeliefsSupplier;

    /**
     * @param allBeliefsSupplier Lieferant aller Beliefs (z.B.
     *                           {@code knowledgeStore::allBeliefs}).
     *                           Wird bei jedem Call aufgerufen \u2014
     *                           der Retriever cached nichts.
     */
    public EthicsRetriever(Supplier<List<Belief>> allBeliefsSupplier) {
        this.allBeliefsSupplier = Objects.requireNonNull(allBeliefsSupplier);
    }

    /** Alle ingesteten Ethik-Beliefs (Source startsWith "ethics:"). */
    public List<Belief> allEthicsBeliefs() {
        return allBeliefsSupplier.get().stream()
                .filter(b -> b.source() != null
                        && b.source().startsWith(EthicsCore.SOURCE_PREFIX))
                .toList();
    }

    /** Anzahl ingesteter Ethik-Beliefs. Schnell, ohne Materialisierung. */
    public int count() {
        return (int) allBeliefsSupplier.get().stream()
                .filter(b -> b.source() != null
                        && b.source().startsWith(EthicsCore.SOURCE_PREFIX))
                .count();
    }

    /**
     * Ethik-Beliefs nach Sutta-Sub-Source gruppiert.
     * Sub-Source ist alles zwischen {@code "ethics:"} und {@code '#'} im Source-Tag.
     */
    public Map<String, List<Belief>> bySource() {
        Map<String, List<Belief>> out = new LinkedHashMap<>();
        for (Belief b : allEthicsBeliefs()) {
            String sub = subSourceOf(b.source());
            out.computeIfAbsent(sub, k -> new ArrayList<>()).add(b);
        }
        return out;
    }

    /**
     * Liefert bis zu {@code k} per (deterministischem) Confidence-Ranking
     * relevante Ethik-Beliefs f\u00fcr ein Stichwort.
     *
     * <p>Score = (1.0 wenn Statement enth\u00e4lt keyword case-insensitive, sonst 0)
     *           * confidence. Stabile Sortierung absteigend.
     *
     * <p>Wird vom SelfReflector statt der hartcodierten "G\u00fcte/Mitgef\u00fchl/..."
     * -Strings genutzt.
     */
    public List<Belief> topRelevant(String keyword, int k) {
        if (keyword == null || keyword.isBlank() || k <= 0) return List.of();
        String needle = keyword.toLowerCase(Locale.ROOT);

        var ranked = new ArrayList<>(allEthicsBeliefs());
        ranked.removeIf(b -> b.statement() == null
                || !b.statement().toLowerCase(Locale.ROOT).contains(needle));
        ranked.sort(Comparator.comparingDouble(Belief::confidence).reversed());
        if (ranked.size() > k) {
            return List.copyOf(ranked.subList(0, k));
        }
        return List.copyOf(ranked);
    }

    static String subSourceOf(String source) {
        if (source == null) return "(unknown)";
        if (!source.startsWith(EthicsCore.SOURCE_PREFIX)) return source;
        String rest = source.substring(EthicsCore.SOURCE_PREFIX.length());
        int hash = rest.indexOf('#');
        return hash >= 0 ? rest.substring(0, hash) : rest;
    }
}
