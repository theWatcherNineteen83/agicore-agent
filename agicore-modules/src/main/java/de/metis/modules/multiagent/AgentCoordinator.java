package de.metis.modules.multiagent;

import de.metis.kernel.core.AgentCoreLoop;
import de.metis.kernel.goal.Goal;
import de.metis.kernel.planner.Planner;
import de.metis.modules.Agent;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Coordinates multiple Metis agent instances with different specializations.
 * <p>
 * Agents communicate by posting goals to each other's queues and
 * sharing a common message bus.
 */
public class AgentCoordinator {

    private static final Logger LOG = Logger.getLogger(AgentCoordinator.class.getName());

    public record SubAgent(String name, String role, Agent agent, ScheduledExecutorService scheduler) {}

    private final List<SubAgent> agents = new CopyOnWriteArrayList<>();
    private final BlockingQueue<String> messageBus = new LinkedBlockingQueue<>(500);

    /** Create a new sub-agent with its own cognitive loop. */
    public SubAgent spawn(String name, String role, Agent agent, Duration tickInterval) {
        var scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-" + name);
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(() -> {
            try {
                agent.core().tick();
            } catch (Exception e) {
                LOG.warning("Agent " + name + " tick error: " + e.getMessage());
            }
        }, 0, tickInterval.toMillis(), TimeUnit.MILLISECONDS);

        var sub = new SubAgent(name, role, agent, scheduler);
        agents.add(sub);
        LOG.info("Spawned agent '" + name + "' (" + role + ") — interval " + tickInterval.toMillis() + "ms");
        return sub;
    }

    /** Send a message from one agent to another. */
    public void sendMessage(String from, String to, String content) {
        String msg = String.format("[%s→%s] %s", from, to, content);
        messageBus.offer(msg);
    }

    /** Broadcast a message to all agents. */
    public void broadcast(String from, String content) {
        String msg = String.format("[%s→ALL] %s", from, content);
        messageBus.offer(msg);
    }

    /** Read pending messages for a specific agent. */
    public List<String> receiveMessages(String agentName, int max) {
        List<String> result = new ArrayList<>();
        var drain = new ArrayList<String>();
        messageBus.drainTo(drain, max * 2);
        for (String msg : drain) {
            if (msg.contains("→" + agentName + "]") || msg.contains("→ALL]")) {
                result.add(msg);
                if (result.size() >= max) break;
            } else {
                // Re-queue messages not for this agent
                messageBus.offer(msg);
            }
        }
        return result;
    }

    /** Get status of all agents. */
    public Map<String, Map<String, Object>> status() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (var sub : agents) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("role", sub.role());
            info.put("activeGoals", sub.agent().goals().activeCount());
            info.put("uptime", "active");
            result.put(sub.name(), info);
        }
        return result;
    }

    /** Shutdown all agents. */
    public void shutdown() {
        for (var sub : agents) {
            sub.scheduler().shutdown();
        }
        agents.clear();
        LOG.info("AgentCoordinator shut down — " + agents.size() + " agents stopped");
    }

    public int agentCount() { return agents.size(); }
    public BlockingQueue<String> messageBus() { return messageBus; }
}
