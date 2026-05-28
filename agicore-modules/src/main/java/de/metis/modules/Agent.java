package de.metis.modules;

import de.metis.kernel.action.ActionExecutor;
import de.metis.kernel.action.HttpRequestAction;
import de.metis.kernel.action.MaryTTSSpeakAction;
import de.metis.kernel.action.ShellCommandAction;
import de.metis.kernel.action.VocabularyLearningAction;
import de.metis.kernel.action.WikipediaAction;
import de.metis.kernel.action.VoskListenAction;
import de.metis.modules.action.CameraSnapshotAction;
import de.metis.kernel.core.AgentCoreLoop;
import de.metis.kernel.evolution.EvolutionManager;
import de.metis.kernel.goal.Goal;
import de.metis.kernel.goal.GoalManager;
import de.metis.kernel.memory.LongTermMemory;
import de.metis.kernel.memory.MemoryConsolidator;
import de.metis.kernel.memory.ShortTermMemory;
import de.metis.kernel.meta.MetaCognition;
import de.metis.kernel.meta.MetaRepresentation;
import de.metis.kernel.metrics.PerformanceMetrics;
import de.metis.kernel.optimize.HyperparameterMutator;
import de.metis.kernel.planner.PlanValidator;
import de.metis.kernel.planner.Planner;
import de.metis.kernel.self.SelfModel;
import de.metis.kernel.workspace.AttentionBuffer;
import de.metis.kernel.workspace.GlobalWorkspace;
import de.metis.kernel.world.WorldModel;
import de.metis.modules.evolution.ModelRegistry;
import de.metis.modules.planner.OllamaPlanner;
import de.metis.modules.planner.PromptChainingService;
import de.metis.modules.planner.StubPlanner;
import de.metis.kernel.metrics.FitnessSignal;
import de.metis.modules.CuriosityEngine;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.nio.file.Path;
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
    private PromptChainingService chainService;

    private Agent(AgentCoreLoop core) { this.core = core; }

    public void tick() { core.tick(); }
    public void run(int maxTicks) { core.run(maxTicks); }

    public Goal addGoal(String description, String category, int priority, double reward, int cost) {
        return core.goals().add(new Goal(description, category, priority, reward, cost));
    }

    public Goal addGoal(String description, int priority, double reward, int cost) {
        return core.goals().add(description, priority, reward, cost);
    }

    public void completeGoal(UUID id) {
        core.goals().complete(id);
    }

    public AgentCoreLoop core() { return core; }
    public Planner planner() { return core.planner(); }
    public de.metis.kernel.action.ActionExecutor executor() { return core.executor(); }
    public GoalManager goals() { return core.goals(); }
    public MetaCognition meta() { return core.meta(); }
    public ShortTermMemory stm() { return core.stm(); }
    public MemoryConsolidator memory() { return core.consolidator(); }
    public PerformanceMetrics metrics() { return core.metrics(); }
    public GlobalWorkspace workspace() { return core.workspace(); }
    public SelfModel selfModel() { return core.selfModel(); }
    public WorldModel worldModel() { return core.worldModel(); }
    public MetaRepresentation metaRepr() { return core.metaRepr(); }
    public PromptChainingService chainService() { return chainService; }

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
        private CuriosityEngine curiosityEngine = null;
        private FitnessSignal fitnessSignal = null;
        private PromptChainingService chainService = null;

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
        public Builder registerSpeakTTS(String text) { executor.register(new MaryTTSSpeakAction(text)); return this; }
        public Builder registerSpeakTTS(String text, String voice) { executor.register(new MaryTTSSpeakAction(text, voice)); return this; }
        public Builder registerListenSTT(int durationSeconds) { executor.register(new VoskListenAction(durationSeconds)); return this; }
        public Builder registerListenSTT() { return registerListenSTT(5); }
        public Builder registerLearnVocabulary(String heard, String correct) { executor.register(new VocabularyLearningAction(heard, correct)); return this; }
        public Builder registerWikipedia(String topic) { executor.register(new WikipediaAction(topic)); return this; }
        public Builder registerWikipedia(String topic, String mode, Path dir) { executor.register(new WikipediaAction(topic, mode, dir)); return this; }
        public Builder registerCameraSnapshot(String cameraName, String source) { executor.register(new CameraSnapshotAction(cameraName, source)); return this; }
        public Builder registerCameraSnapshot(String cameraName, String source, java.nio.file.Path dir) { executor.register(new CameraSnapshotAction(cameraName, source, dir)); return this; }
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
        public Builder curiosityEngine(CuriosityEngine c) { this.curiosityEngine = c; return this; }
        public Builder fitnessSignal(FitnessSignal f) { this.fitnessSignal = f; return this; }
        public Builder promptChainingService(PromptChainingService cs) { this.chainService = cs; return this; }
        public Builder promptChainingService(String ollamaUrl, String model, Duration timeout) {
            this.chainService = new PromptChainingService(ollamaUrl, model, timeout); return this;
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
            if (planner instanceof de.metis.kernel.planner.EvolvableModule em) {
                evolutionManager.register(em);
            }
            var loop = new AgentCoreLoop(goalManager, planner, planValidator, executor,
                    consolidator, meta, metrics, hyperMutator,
                    workspace, selfModel, worldModel, metaRepr, evolutionManager,
                    curiosityEngine != null ? () -> curiosityEngine.generateExplorationGoal() : null);
            Agent agent = new Agent(loop);
            agent.chainService = this.chainService;
            return agent;
        }
    }
}
