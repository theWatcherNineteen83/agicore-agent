package de.agicore.agent;

import de.agicore.agent.action.ActionExecutor;
import de.agicore.agent.action.HttpRequestAction;
import de.agicore.agent.action.ShellCommandAction;
import de.agicore.agent.core.AgentCoreLoop;
import de.agicore.agent.goal.GoalManager;
import de.agicore.agent.memory.LongTermMemory;
import de.agicore.agent.memory.MemoryConsolidator;
import de.agicore.agent.memory.ShortTermMemory;
import de.agicore.agent.meta.MetaCognition;
import de.agicore.agent.meta.MetaRepresentation;
import de.agicore.agent.metrics.PerformanceMetrics;
import de.agicore.agent.optimize.HyperparameterMutator;
import de.agicore.agent.planner.Planner;
import de.agicore.agent.planner.StubPlanner;
import de.agicore.agent.self.SelfModel;
import de.agicore.agent.workspace.GlobalWorkspace;
import de.agicore.agent.world.WorldModel;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Top-level agent facade — Phase 3 with Global Workspace.
 * <p>
 * Wires all subsystems: cognitive loop, memory, goals, metrics,
 * global workspace (attention bottleneck), self-model, world-model,
 * and meta-representation.
 */
public class Agent {

    private final AgentCoreLoop core;

    private Agent(AgentCoreLoop core) {
        this.core = core;
    }

    public void tick() { core.tick(); }
    public void run(int maxTicks) { core.run(maxTicks); }
    public void addGoal(String description, String category, int priority, double reward, int cost) {
        core.goals().add(new de.agicore.agent.goal.Goal(description, category, priority, reward, cost));
    }

    public void addGoal(String description, int priority, double reward, int cost) {
        core.goals().add(description, priority, reward, cost);
    }

    public AgentCoreLoop core() { return core; }
    public GoalManager goals() { return core.goals(); }
    public MetaCognition meta() { return core.meta(); }
    public ShortTermMemory stm() { return core.stm(); }
    public MemoryConsolidator memory() { return core.consolidator(); }
    public PerformanceMetrics metrics() { return core.metrics(); }
    public GlobalWorkspace workspace() { return core.workspace(); }
    public SelfModel selfModel() { return core.selfModel(); }
    public WorldModel worldModel() { return core.worldModel(); }
    public MetaRepresentation metaRepr() { return core.metaRepr(); }

    // ── Builder ──────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final ActionExecutor executor = new ActionExecutor();
        private GoalManager goalManager = new GoalManager();
        private ShortTermMemory stm = new ShortTermMemory();
        private LongTermMemory ltm = new LongTermMemory();
        private MemoryConsolidator consolidator;
        private MetaCognition meta = new MetaCognition();
        private Planner planner = new StubPlanner();
        private PerformanceMetrics metrics = new PerformanceMetrics();
        private HyperparameterMutator hyperMutator = new HyperparameterMutator();
        private GlobalWorkspace workspace = new GlobalWorkspace();
        private SelfModel selfModel = new SelfModel();
        private WorldModel worldModel = new WorldModel();
        private MetaRepresentation metaRepr;

        public Builder registerShellCommand(List<String> command, long timeoutSeconds) {
            executor.register(new ShellCommandAction(command, timeoutSeconds));
            return this;
        }

        public Builder registerShellCommand(List<String> command) {
            return registerShellCommand(command, 30);
        }

        public Builder registerHttpGet(URI uri) {
            executor.register(new HttpRequestAction(uri));
            return this;
        }

        public Builder registerHttp(String method, URI uri,
                                     Map<String, String> headers,
                                     Optional<String> body) {
            executor.register(new HttpRequestAction(method, uri, headers, body));
            return this;
        }

        public Builder goalManager(GoalManager gm) { this.goalManager = gm; return this; }
        public Builder shortTermMemory(ShortTermMemory s) { this.stm = s; return this; }
        public Builder longTermMemory(LongTermMemory l) { this.ltm = l; return this; }
        public Builder metaCognition(MetaCognition m) { this.meta = m; return this; }
        public Builder planner(Planner p) { this.planner = p; return this; }
        public Builder performanceMetrics(PerformanceMetrics m) { this.metrics = m; return this; }
        public Builder hyperparameterMutator(HyperparameterMutator h) { this.hyperMutator = h; return this; }
        public Builder globalWorkspace(GlobalWorkspace w) { this.workspace = w; return this; }
        public Builder selfModel(SelfModel s) { this.selfModel = s; return this; }
        public Builder worldModel(WorldModel w) { this.worldModel = w; return this; }

        public Builder workspaceCapacity(int capacity) {
            this.workspace = new GlobalWorkspace(
                    new de.agicore.agent.workspace.AttentionBuffer(capacity));
            return this;
        }

        public Agent build() {
            this.consolidator = new MemoryConsolidator(stm, ltm);
            this.selfModel.bind(meta, metrics);
            this.metaRepr = new MetaRepresentation(selfModel, workspace);
            var loop = new AgentCoreLoop(goalManager, planner, executor,
                    consolidator, meta, metrics, hyperMutator,
                    workspace, selfModel, worldModel, metaRepr);
            return new Agent(loop);
        }
    }
}
