package de.metis.modules.events;

import de.metis.modules.Agent;

/**
 * An event trigger that monitors external systems and generates goals
 * for Metis when something noteworthy happens.
 */
public interface EventTrigger {

    /** Unique name for this trigger (used in logging). */
    String name();

    /** Start monitoring. Called once during agent startup. */
    void start(Agent agent);

    /** Stop monitoring. Called during agent shutdown. */
    void stop();

    /** Human-readable description of what this trigger watches. */
    String description();
}
