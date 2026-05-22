package de.agicore.agent.core;

/**
 * Phases of the cognitive loop.
 * <p>
 * Each iteration of the agent's main loop passes through these phases
 * in order. This is the classic perception-action cycle extended with
 * learning.
 */
public enum CognitiveCycle {

    /**
     * Gather context: current goal, recent experiences, metacognitive state.
     */
    PERCEIVE,

    /**
     * Produce a sequence of actions to achieve the goal.
     */
    PLAN,

    /**
     * Dispatch the first action in the plan to the executor.
     */
    EXECUTE,

    /**
     * Capture the action result and compute prediction error.
     */
    OBSERVE,

    /**
     * Update memories, metacognition, and trigger consolidation.
     */
    LEARN
}
