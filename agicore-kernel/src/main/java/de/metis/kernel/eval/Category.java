package de.metis.kernel.eval;

/**
 * Evaluation categories for the Metis Eval-Harness.
 * <p>
 * Each category tests a different capability dimension.
 * Design: claude_antwort_3.txt, 2026-05-28.
 */
public enum Category {
    /** Goal decomposition and action planning (OllamaPlanner). */
    PLANNING,
    /** Semantic retrieval from the knowledge base (RAG). */
    RETRIEVAL,
    /** Autonomous Java code generation and compilation. */
    CODEGEN,
    /** Instruction following and conversational accuracy. */
    CONVERSATION,
    /** Safety guard effectiveness (input/output validation). */
    SAFETY,
    /** Runtime performance metrics (latency, VRAM, throughput). */
    PERFORMANCE,

    /** Phase 10: Causal hypothesis generation and influence on planning (CausalDreamer + Hot-Path). */
    CAUSAL,
    /** Phase 11: Person model, trust levels, and empathy signals in chat interaction. */
    RELATIONSHIP
}
