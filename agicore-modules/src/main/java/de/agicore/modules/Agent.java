package de.agicore.modules;

import de.agicore.kernel.action.ActionExecutor;
import de.agicore.kernel.action.HttpRequestAction;
import de.agicore.kernel.action.ShellCommandAction;
import de.agicore.kernel.core.AgentCoreLoop;
import de.agicore.kernel.evolution.EvolutionManager;
import de.agicore.kernel.goal.Goal;
import de.agicore.kernel.goal.GoalManager;
import de.agicore.kernel.memory.LongTermMemory;
import de.agicore.kernel.memory.MemoryConsolidator;
import de.agicore.kernel.memory.ShortTermMemory;
import de.agicore.kernel.meta.MetaCognition;
import de.agicore.kernel.meta.MetaRepresentation;
import de.agicore.kernel.metrics.PerformanceMetrics;
import de.agicore.kernel.optimize.HyperparameterMutator;
import de.agicore.kernel.planner.PlanValidator;
import de.agicore.kernel.planner.Planner;
import de.agicore.kernel.self.SelfModel;
import de.agicore.kernel.workspace.AttentionBuffer;
import de.agicore.kernel.workspace.GlobalWorkspace;
import de.agicore.kernel.world.WorldModel;
import de.agicore.modules.evolution.ModelRegistry;
import de.agicore.modules.planner.OllamaPlanner;
import de.agicore.modules.planner.StubPlanner;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Agent facade — wires kernel (immutable) + modules (evolvable).
 * <p>
 * This class lives in modules because it depends on concrete module
 * implementations (StubPlanner). The kernel depends only on interfaces.
 */
public class Agent {

    private final AgentCoreLoop core;

    private Agent(AgentCoreLoop core) { this.core = core; }

    public void tick() { core.tick(); }
    public void run(int maxTicks) { core.run(maxTicks); }

    public void addGoal(String description, String category, int priority, double reward, int cost) {
        core.goals().add(new Goal(description, category, priority, reward, cost));
    }

    public void addGoal(String description, int priority, double reward, int cost) {
        core.goals().add(description, priority, reward, cost);
    }

    public AgentCoreLoop core() { return core; }
    public Planner planner() { return core.planner(); }
    public de.agicore.kernel.action.ActionExecutor executor() { return core.executor(); }
    public GoalManager goals() { return core.goals(); }
    public MetaCognition meta() { return core.meta(); }
    public ShortTermMemory stm() { return core.stm(); }
    public MemoryConsolidator memory() { return core.consolidator(); }
    public PerformanceMetrics metrics() { return core.metrics(); }
    public GlobalWorkspace workspace() { return core.workspace(); }
    public SelfModel selfModel() { return core.selfModel(); }
    public WorldModel worldModel() { return core.worldModel(); }
    public MetaRepresentation metaRepr() { return core.metaRepr(); }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final ActionExecutor executor = new ActionExecutor();
        private GoalManager goalManager = new GoalManager();
        private ShortTermMemory stm = new ShortTermMemory();
        private LongTermMemory ltm = new LongTermMemory();
        private MetaCognition meta = new MetaCognition();
        private Planner planner = new OllamaPlanner();  // Step D: LLM planner by default
        private PerformanceMetrics metrics = new PerformanceMetrics();
        private HyperparameterMutator hyperMutator = new HyperparameterMutator();
        private GlobalWorkspace workspace = new GlobalWorkspace();
        private SelfModel selfModel = new SelfModel();
        private WorldModel worldModel = new WorldModel();
        private EvolutionManager evolutionManager = new EvolutionManager();

        public Builder registerShellCommand(List<String> command, long timeoutSeconds) {
            executor.register(new ShellCommandAction(command, timeoutSeconds)); return this;
        }
        public Builder registerShellCommand(List<String> command) {
            return registerShellCommand(command, 30);
        }
        public Builder registerHttpGet(URI uri) {
            executor.register(new HttpRequestAction(uri)); return this;
        }
        public Builder registerHttp(String method, URI uri, Map<String, String> headers, Optional<String> body) {
            executor.register(new HttpRequestAction(method, uri, headers, body)); return this;
        }
        public Builder workspaceCapacity(int capacity) {
            this.workspace = new GlobalWorkspace(new AttentionBuffer(capacity)); return this;
        }
        public Builder planner(Planner p) { this.planner = p; return this; }
        public Builder evolutionManager(EvolutionManager e) { this.evolutionManager = e; return this; }

        /** Use the Ollama LLM planner with custom configuration. */
        public Builder ollamaPlanner(String ollamaUrl, String model, Duration timeout) {
            this.planner = new OllamaPlanner(ollamaUrl, model, timeout);
            return this;
        }

        /** Use the Ollama LLM planner with ModelRegistry for auto model selection. */
        public Builder ollamaPlanner(String ollamaUrl, ModelRegistry registry, Duration timeout) {
            this.planner = new OllamaPlanner(ollamaUrl, registry, timeout);
            return this;
        }

        /** Use the keyword-based stub planner (for testing/fallback). */
        public Builder stubPlanner() {
            this.planner = new StubPlanner();
            return this;
        }

        public Agent build() {
            var consolidator = new MemoryConsolidator(stm, ltm);
            selfModel.bind(meta, metrics);
            var metaRepr = new MetaRepresentation(selfModel, workspace);
            var planValidator = new PlanValidator(executor);

            // Inject world model into OllamaPlanner for context building
            if (planner instanceof OllamaPlanner op) {
                op.withWorldModel(worldModel)
                  .withAvailableActions(executor.availableActions());
            }

            // Register evolvable modules
            if (planner instanceof de.agicore.kernel.planner.EvolvableModule em) {
                evolutionManager.register(em);
            }
            var loop = new AgentCoreLoop(goalManager, planner, planValidator, executor,
                    consolidator, meta, metrics, hyperMutator,
                    workspace, selfModel, worldModel, metaRepr, evolutionManager);
            return new Agent(loop);
        }
    }
}
