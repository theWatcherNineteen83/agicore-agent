package de.metis.kernel.optimize;

/**
 * Interface for prompt template mutation and evaluation.
 * <p>
 * In Phase 2 this is a forward-looking interface — the actual LLM prompt
 * templates will be defined when Ollama integration lands. The interface
 * establishes the contract now so the optimization loop can be wired in
 * without architectural changes later.
 * <p>
 * Concept: the agent maintains a set of prompt templates. Each template
 * maps a goal context → LLM prompt. The mutator generates variations of
 * these templates (rephrasing, adding constraints, changing tone) and
 * the agent evaluates which variations produce better plans.
 * <p>
 * Phase 3 will extend this to meta-representation prompts (the agent's
 * "self-talk" during global workspace attention routing).
 */
public interface PromptMutator {

    /**
     * A named prompt template with a version identifier.
     *
     * @param name     human-readable label (e.g. "planner-system")
     * @param version  monotonically increasing version number
     * @param template the actual prompt text with {@code {{placeholders}}}
     */
    record PromptTemplate(String name, int version, String template) {
        public PromptTemplate withVersion(int newVersion) {
            return new PromptTemplate(name, newVersion, template);
        }

        public PromptTemplate withTemplate(String newTemplate) {
            return new PromptTemplate(name, version + 1, newTemplate);
        }
    }

    /**
     * Mutate a template to produce a variant.
     * <p>
     * The mutation strategy is implementation-defined. Examples:
     * <ul>
     *   <li>Rephrase instructions</li>
     *   <li>Add or remove constraints</li>
     *   <li>Change temperature / tone parameters</li>
     *   <li>Inject few-shot examples from memory</li>
     * </ul>
     *
     * @param original the template to mutate
     * @return a new template with incremented version
     */
    PromptTemplate mutate(PromptTemplate original);

    /**
     * Evaluate a template's effectiveness.
     *
     * @param template the template to score
     * @param successRate how often plans using this template succeeded
     * @param avgLatency  average planning latency in milliseconds
     */
    void evaluate(PromptTemplate template, double successRate, long avgLatency);

    /**
     * Return the best-performing template for a given name.
     */
    PromptTemplate best(String name);
}
