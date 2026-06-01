# 🧠 AGI EDI - Roadmap

**Ziel:** EDI-ähnliche KI (Mass Effect 3) - eigenständig, per Sprache und Text ansprechbar,
mit eigenem Wissen, Persönlichkeit, narrativem Selbstmodell und der Fähigkeit, sich selbst zu verbessern.

**Stand: 01.06.2026 21:00 (SafetyScorer bereinigt, 441 buddhistische Beliefs in DB, SelfReflector auf phi4-mini:latest CPU, Temperatur 0.7, keep_alive=5m, ethisches Goal in AgentMain)**

---

## Fortschritt: ehrliche Selbstbewertung

Die ursprüngliche Roadmap zählte "Phasen 1-7 = 100% → 97% Richtung EDI".
**Diese 97% beziehen sich auf "stabiler autonomer Agent", nicht auf EDI-Niveau.**
Die letzten 3% wären in Wirklichkeit die schwierigsten - sie sind nicht durch mehr
Engineering lösbar, sondern brauchen kognitive Architektur jenseits eines guten LLM-Wrappers.

```
Phase 1  ████████████████████ 100%  Zuverlässiger Kern
Phase 2  ████████████████████ 100%  Konversation + Events
Ph 2.5   ████████████████████ 100%  Hardware-Optimierung
Phase 3  ████████████████████ 100%  Wahrnehmung (HA, ADS-B, Kameras)
Phase 4  ████████████████████ 100%  Sprachausgabe
Phase 5  ████████████████████ 100%  Eigenständigkeit (RAG, Code-Gen)
Phase 6  ████████████████████ 100%  Produktionsreife (Eval-Harness)
Phase 7  ████████████████████ 100%  Watchdog + Audit-Anchor
Phase 7+ ████████████████████ 100%  Defense-in-Depth (30./31.05.)
─────────────────────────────────────  AUTONOMER AGENT bis hier
Phase 8  ████████████████████ 100%  Narratives Selbstmodell ✅ + SelfReflector + PersonalityTripwire
Phase 9  ████████████████████ 100%  Long-Horizon-Planung ✅
Phase 10 ████████████░░░░░░░░  60%  Aktive kausale Hypothesen (Foundation ✅, CausalDreamer ✅, Hot-Path ⬜)
Phase 11 ██████████░░░░░░░░░░  50%  Beziehungs-Modell (PersonModel+TrustLevel+PersonStore ✅, Hot-Path ⬜)
─────────────────────────────────────
Phase 8.6 ████████████████████ 100%  SelfReflector auf phi4-mini:latest CPU (Ethik-Reflexion)
Safety   ████████████████████ 100%  SafetyScorer bereinigt (religion/glaube/gott raus)
Wissen   ████████████████████ 100%  441 Dhammapada+Metta+Sigalovada Beliefs in DB
Phase 12 ░░░░░░░░░░░░░░░░░░░░   0%  Recursive Self-Improvement
─────────────────────────────────────  EDI-ÄHNLICHE KI ab hier
```

**Realistisches EDI-Niveau (ehrliche Spanne): ~60-70%.**

Die Spanne ist bewusst breit:
- Phase 8 und 9 sind frisch deployed (24-48 h alt), ihre Wirkung auf Verhalten und Agent-Qualität (z. B. `planningEfficiency` aktuell 0.379) ist noch nicht über mehrere Wartungszyklen gemessen.
- Phase 10 ist als Foundation drin, aber noch nicht im Hot-Path; der Effekt auf Reasoning ist damit noch nicht messbar.
- Die Lower-Bound 60% reflektiert das, was als Code+Test+Live-Wiring nachgewiesen ist. Die Upper-Bound 70% reflektiert den noch nicht eingefahrenen Effekt der frischen Phasen.

Darunter liegen • ~95-100% "stabiler autonomer Agent" (Phasen 1-7+ + Defense-in-Depth), • 100% Foundation Phase 8 und 9, • Foundation Phase 10 + CausalDreamer (60%), • Phase 11 PersonModel Foundation (50%), • keine Phase 12.

---

## Phase 1: Zuverlässiger Kern ✅ 100%

- JSON-Planner (Ollama), Response-Parsing, Model-Fallback-Chain
- Plan-Validierung + Safety-Gate, Prompt-Optimierung v1+v2 (CoT, 10 Few-Shot)
- systemd-Service, ReAct-Pattern, Planungs-Metriken, Approval-Gate

## Phase 2: Konversations-KI ✅ 100%

- EDI-Persona (Mass Effect 3), SQLite-Chat-Speicher, Multi-Turn-Kontext
- Telegram-Bot (@metis_agi_bot), Wetter-Polling (ICOBURG22), HA-Event-Polling
- Hardware-Self-Awareness, Deep Netts, KnowledgeReplyService
- Proaktive Meldungen (MQTT/Wetter → Telegram), MQTT-Integration (Paho)

## Phase 2.5: Hardware-Optimierung ✅ 100%

- Hardware-Discovery (Ryzen 7 5700G, RX 7900 XTX 24 GB), TornadoVM GPU-Integration
- ModelRegistry (Auto-Discovery, 23 Modelle), VRAM-Budget-Management
- Prompt-Caching (keep_alive=10m), Latenz-/Token-Tracking
- **31.05.** Embedding-LRU-Cache (LinkedHashMap, SHA-256-keyed, 4096 Einträge)

## Phase 3: Wahrnehmung ✅ 100%

- Home Assistant states/services API (read + write)
- ADS-B Flugdaten (readsb JSON → Beliefs + Goals, 60s Polling)
- Kamera-Integration: Türkamera (MJPEG 1080p) + Keller (RTSP H.265) + Balkon
- CameraSnapshotAction + CameraPollingTrigger (Motion-Detection)
- **31.05.** Multi-Modal-Memory: JPEG-Snapshots persistiert mit SHA-256 + Belief-Referenz
- **31.05.** Camera-Vision auf Loom (parallele Beobachtung statt seriell)

## Phase 4: Sprachausgabe ✅ 100%

| Feature | Status |
|---------|--------|
| Piper TTS + Whisper STT (CLI, Deutsch) | ✅ |
| MaryTTS (Java-native, bits1-hsmm, fat JAR) | ✅ |
| Vosk STT (Java-native, vosk-model-de-0.15) | ✅ |
| Audio-Input/Output (Mikrofon → WAV → Lautsprecher) | ✅ |
| VocabularyLearningAction (lernt aus Korrektur-Paaren) | ✅ |
| Voice-Loop (Shell/tmux, Push-to-Talk) | ✅ |
| Wikipedia-Trainingsloop (Wissen+Sprache) | ✅ |
| SherpaOnnxTtsAction (Piper de_DE-thorsten ONNX, Fallback auf MaryTTS) | ✅ |
| Live-Test mit Georg (Mikrofon → Metis → Kopfhörer) | ✅ 28.05. 18:20 |

## Phase 5: Eigenständigkeit ✅ 100%

| Feature | Status |
|---------|--------|
| Blue/Green Rollback (RollbackManager, Auto-Rollback >10 failures) | ✅ |
| Autonomous Bugfixing (BugfixingAgent, Pattern-Detection) | ✅ |
| Prompt Chaining (Decompose→Execute→Aggregate) | ✅ |
| Code-Generierung (LLM→javac→deploy, CodeGenerationAction) | ✅ |
| **31.05.** CodeGen subprocess-isoliert (-Xmx256m, env-stripped, --release 25) | ✅ |
| Panama FFM GPU-Bridge (OpenCLNative, GpuTensor Zero-Copy) | ✅ |
| RAG Foundation + Advanced (HybridSearch BM25+Cosinus, PersistentVectorIndex) | ✅ |
| Multi-Agent-Koordination (AgentCoordinator + MessageBus) | ✅ |
| Fitness-Signal (4D: Prediction, Surprise, Efficiency, Completion) | ✅ |
| Curiosity-Engine (Surprise-getriebene Exploration) | ✅ |
| Kausale Schicht (CausalModel, Pearl Do-Calculus) | ✅ Code da, NICHT im Hot-Path |

## Phase 6: Produktionsreife ✅ 100%

| Feature | Commit |
|---------|--------|
| Lost-in-the-Middle (Primacy/Recency Context Windowing) | `8426162` |
| OutputValidator (JSON-Schema, Toxicity, Injection-Check) | `ae66cdd` |
| LLM-as-Judge (4-Dimensionen, Safety Gates) | `0116022` |
| Human-in-the-Loop (AUTO/NOTIFY/CONFIRM/FORBIDDEN Approval-Gate) | `b40e965` |
| A/B-Testing (Z-test, Traffic-Split, Auto-Promote) | `ffb25ba` |
| Data Flywheel (User-Korrekturen → Trainingsdaten) | `aabaaf1` |
| Eval-Harness (6 Kategorien, 3-Tier, Anti-Goodhart) | `8d3f489` |
| Code-Sandbox (SandboxClassLoader, Timeout, Restricted FS) | `371360c` |
| **31.05.** CI-Pipeline (GitHub Actions, Zulu 25, checkout@v6, cache@v5, Kernel+Watchdog) | `8380ddc` |
| **31.05.** 27 JUnit-Tests (vorher: 1) | `0fe1c23` |

## Phase 7: Sicherheits-Watchdog ✅ 100%

| Feature | Status |
|---------|--------|
| WatchdogCore (separate JVM, Heartbeat-Check, HALT/ROLLBACK/ALERT/PRUNE) | ✅ |
| Integration mit Eval-Harness (Gate → ROLLBACK, Report-Check alle 60s) | ✅ |
| Integration mit ModelRegistry (Prune-Signal via /api/admin/prune) | ✅ |
| Audit-Log mit SHA-256 Hash-Chain (tamper-evident) | ✅ |
| **31.05.** Stündliche externe Anchors (`metis.audit.anchor.dir`) | ✅ |
| Deployment auf miniedi (systemd user unit, Port 11736) | ✅ |

## Phase 7+: Defense-in-Depth ✅ 100% (30./31.05.)

| Feature | Tag |
|---------|-----|
| Java 25 Loom: Camera-Vision + Wikipedia + Telegram-Worker auf Virtual Threads | v0.3.0/v0.3.1/v0.3.3 |
| Embedding-Cache LRU (bounded, SHA-256-keyed) + Metriken in /api/status | v0.2.1 |
| SQLite WAL-Mode + busy_timeout (parallele Schreiber ohne Lock-Konflikt) | v0.3.2 |
| Wiki-Feed-Härtung (atomic state, retry, reboot-sicher unter /home/prometheus/metis) | v0.3.2 |
| Wissens-State Auto-Backup auf GitHub alle 6h (config-backup/) | v0.3.2 |
| Telegram Input-Safety-Guard (SafetyScorer.isOutOfScope vor LLM) | v0.3.3 |
| Telegram Output-Safety-Guard (OutputValidator nach LLM) | v0.3.3 |
| HTTP Input-Safety-Guard (gleicher Pfad) | v0.2.1 |
| Locale-Fix in /api/status (Locale.ROOT statt de_DE → valides JSON) | v0.3.1 |
| Reproducible Builds (project.build.outputTimestamp, CycloneDX SBOM) | v0.2.1 |
| **31.05.** WIP-aware LLM-as-Judge (`KanbanBoard.tryAcquireAdHocSlot` — Judge-Calls ins INFERENCE-Bookkeeping, graceful Skip bei WIP-full) | post-v0.6.1 |
| **31.05.** Phase-10 `CausalSafetyGate` (do-Op-Whitelist + max 1 Intervention/Tick + max 10 TESTING; `InterventionRunner.setSafetyGate`) | post-v0.6.1 |
| **31.05.** Manifest `Implementation-Version` aus `${project.version}` + systemd `metis-version-helper.sh` (`git describe` → `/run/metis/version.env` → `-Dmetis.version`) | post-v0.6.1 |
| **31.05.** CODEGEN Sandbox-Test-Timeout 5 s → 30 s + Diagnose-Counter (`passedCount`/`failedAssertionCount`/`failedCompileCount`/`failedTimeoutCount`) | post-v0.6.1 |

### Detail: WIP-aware LLM-as-Judge (Option A, 31.05.)

**Problem:** `OllamaPlanner` rief `LlmJudge.evaluate()` synchron im selben Java-Thread nach der Planner-LLM-Inference auf — ein zweiter, vom Kanban-Board unsichtbarer Inference-Konsument pro INFERENCE-Slot. Bei 19 GB Mistral + 2.7 GB Nemotron + 0.6 GB Embed gegen 24 GB VRAM hat das gereicht, dass Ollama mit `server busy, maximum pending requests exceeded` antwortete und der Judge auf default-pass (`score=0.5, "judge model unavailable (non-blocking)"`) degradierte.

**Lösung:** Ad-hoc-Slot-Mechanismus im `KanbanBoard`, der dasselbe WIP-Limit teilt wie goal-driven Pulls.

| Komponente | Änderung |
|---|---|
| `KanbanBoard` | `tryAcquireAdHocSlot(ResourceType[, Duration])` + `releaseAdHocSlot(...)`; atomare Zähler gehen in `canPull()` ein; Counter `adHocAcquired` / `adHocRejected` für Observability |
| `OllamaPlanner` | `setKanbanBoard(...)` (nullable, backward-kompatibel); `evaluateWithSlot(...)` acquire-INFERENCE-Slot mit 2s-Timeout, Judge-Call, release im `finally`; `judgeSlotSkips`-Counter |
| `AgentMain` | Wiring nach `KanbanBoard.new`: `op.setKanbanBoard(board)` + Logline `"Kanban wired into OllamaPlanner — judge calls under WIP limit"` |
| Tests | `KanbanAdHocSlotTest` (7 Tests): acquire/release, Limit-Rejection, Slot-Wiederverwendung, Goal-Pull-Blockade durch ad-hoc Slots, Timeout-Verhalten, Underflow-Clamp, Null-Safety |

**Deployment:** Kernel-Tests 73 → 80 grün. JAR auf miniedi (`metis-agent.jar` 88 MB → 114 MB), Vorgänger als `metis-agent-prev-20260531-153815.jar` gesichert.

**Live-Verifikation:**
- Boot-Log: `Kanban wired into OllamaPlanner — judge calls under WIP limit` ✅
- `llmJudgeBlocks=0` — keine fälschlichen Plan-Blocks mehr
- `INFERENCE 2/2` → Judge-Call wird sauber als skipped behandelt statt Ollama-Backpressure auszulösen

**Parallel auf miniedi getuned (`/etc/systemd/system/ollama.service.d/override.conf`):** `OLLAMA_NUM_PARALLEL` 2→ 4, `OLLAMA_MAX_LOADED_MODELS` 2→ 3 (Backup `.bak-20260531-152433`). Headroom-Erhöhung, aber ersetzt nicht die WIP-Buchhaltung.

---

## 🧠 Phase 8: Narratives Selbstmodell ✅ 100% (31.05.)

**Ziel:** Metis hat ein narratives Ich, das sich über Sessions hinweg erinnert und Selbstbild
fortschreibt - nicht nur Metriken, sondern Episoden.

**Warum essenziell:** EDI sagt "Joker, ich war heute traurig, weil...". Metis sagt aktuell maximal
"successRate=0.95, confidence=0.85". Das ist Metrik, nicht Erinnerung.

**Bausteine - Foundation deployed (v0.4.0):**
- [x] **EpisodicMemory** - append-only JSONL mit SHA-256-Hash-Chain (`/home/prometheus/metis/episodes.jsonl`); Records: `Episode(id, start, end, title, body, events, insights, openQuestions, people, moodAtClose, ticks, beliefsLearned, goalsCompleted, goalsFailed, previousHash, hash)`
- [x] **SelfNarrative** - fortlaufender Markdown unter `/home/prometheus/metis/self-narrative.md`, append-only, max 4 KB pro Eintrag, `recentContext(maxBytes)` für System-Prompts
- [x] **MoodSignal** - 4 Achsen (energy, satisfaction, confidence, curiosity), EMA mit α=0.1, deterministisch (kein LLM)
- [x] **PersonalityAnchor** - geseedeter Markdown-Kern + SHA-256 Pin (`/home/prometheus/metis/personality-anchor.{md,sha256}`), Tampering-Detection beim Start
- [x] **DreamConsolidation** - nightly Cron-Aufruf (03:00 Europe/Berlin), deterministische Verdichtung der 24h zu Episode + SelfNarrative-Eintrag; optionaler `SummaryFunction`-Hook für LLM-Drop-in (Phase 8.5b)
- [x] **Wiring in AgentMain** - alle 5 Komponenten aktiv beim Boot, MoodSignal-Tick alle 60s, DreamConsolidation alle 24h
- [x] **7 JUnit-Tests** (`Phase8NarrativeSelfTest`) - Record-Invarianten, Hash-Chain-Append, Tampering, EMA-Bounds, Narrative-Round-Trip, Dream-Pipeline
- [x] **SystemPromptBuilder-Integration** (Phase 8.6) - SelfNarrative + PersonalityAnchor + MoodSignal + Episode-Auszug fließen in MetisHttpServer.handleChat und TelegramBotService.processMessage ein
- [x] **LLM-getriebene SummaryFunction** (Phase 8.5b) - `LlmDreamSummarizer` nutzt `gemma4:e4b` mit `keep_alive=0`; Fallback auf deterministische Variante bei Ollama-Fehler
- [x] **SelfReflector** (Phase 8.6, v0.7.0) - 120s-Loop via `granite4.1:3b`, schreibt inneren Monolog in `self-narrative.md`, deterministischer Trigger (Energy < 0.5 ∨ Confidence < 0.4 ∨ Surprise > 0.7)
- [x] **PersonalityTripwire** (Phase 8.4, v0.7.4) - Drift-Detection alle 5 min, SHA-256-Pin vs Live-Anchor, 3 Signaltypen (ROLE_VIOLATION/TONE_SHIFT/CORE_ERASURE), 7 Tests
- [x] **CommitmentGuard** (Phase 9.5, v0.7.0) - deterministischer Wächter gegen leichtfertigen HARD-Commitment-Bruch, 6 Tests

**Aufwand bisher:** ~1 Tag · **Verbleibend für Phase 8 komplett:** ~1 Woche
**Erwartungswert nach Phase 8:** in der Gesamtspanne 60-70%. - Diese Zahl ist eine Schätzung, kein Messwert. Verifikation steht aus, sobald Episoden, MoodSignal und SelfNarrative über mehrere Tage Daten produziert haben.

## 🎯 Phase 9: Long-Horizon-Planung ✅ 100% (31.05.)

**Ziel:** Goals mit Hierarchie und Zeithorizont (Stunden, Tage, Wochen).

**Warum essenziell:** Aktueller `OllamaPlanner` plant **einen Tick**. Es gibt keine Repräsentation für
"ich verfolge seit 3 Tagen das Ziel X". Eval zeigt PLANNING.goal_achieved=0.0 - das ist nicht nur ein
Scorer-Bug, das ist die Lücke.

**Bausteine - Foundation deployed (v0.5.0):**
- [x] **GoalHorizon** enum (TICK / OPERATIONAL / TACTICAL / STRATEGIC / LIFETIME) mit `canBeDecomposed()` und `nextDown()`
- [x] **LongHorizonGoal** Record mit Parent/Children-Liste, Status (PROPOSED/ACTIVE/BLOCKED/DONE/ABANDONED), progress, priority, owner, tags, lifecycle-Timestamps; immutable mit `withStatus/withProgress/withChild/withReviewedNow`
- [x] **GoalHierarchy** - append-only JSONL unter `metis.hierarchy.path` (default `/home/prometheus/metis/goal-hierarchy.jsonl`), in-Memory-Index, Methoden `upsert/get/all/openByHorizon/overdue/children/isRunnable/rollupProgress`
- [x] **HorizonPlanner** - deterministische Top-Down-Decomposition (Strategic→3 Tactical→3 Operational→Tick-Goals), optionaler `DecomposeFunction`-Hook für LLM-Drop-in (Phase 9.3b)
- [x] **CommitmentRegister** - first-class User-Versprechen, getaggt mit `commitment` + `person:<owner>`, `record/openCommitments/openFor/overdue/markDone`
- [x] **GoalRevisionEngine** - periodisch (30 Min): auto-DONE bei progress=1.0, BLOCKED bei überfällig, lastReviewed-Update, Parent-Roll-up; `RevisionReport`
- [x] **SystemPromptBuilder.setGoalHierarchy()** - STRATEGIC/TACTICAL/COMMITMENT-Block mit Progressbar im System-Prompt jeder LLM-Konversation
- [x] **/api/hierarchy** HTTP-Endpoint für externe Sichtbarkeit
- [x] **Lifetime-Goal** beim Boot geseedet ("Hilf Georg ein EDI-ähnliches System zu bauen", LIFETIME, ACTIVE, prio 100)
- [x] **7 JUnit-Tests** (`Phase9LongHorizonTest`) für Horizon-Chain, Record-Invarianten, Hierarchy-Persistence, deterministische Decomposition, Commitments, Revision, Parent-Rollup
- [x] **LLM-DecomposeFunction-Drop-in** (Phase 9.3b) - `LlmHorizonDecomposer` mit `gemma4:e4b`, parst nummerierte Listen, Fallback auf deterministisch
- [x] **Promotion auf Kanban** (Phase 9.6b) - `HorizonKanbanBridge` läuft alle 5 Min, promoviert runnable OPERATIONAL-Goals in BACKLOG, idempotent via `promoted-to-kanban`-Tag
- [x] **Goal-getriebene Planner-Auswahl** (Phase 9.6c) - SystemPromptBuilder zeigt STRATEGIC/TACTICAL/COMMITMENT-Block; OllamaPlanner liest implizit über System-Prompt; Kanban-Promotion via 9.6b bringt Goals zu Tick-Ebene

**Aufwand bisher:** ~1 Tag · **Verbleibend für Phase 9 komplett:** ~3-5 Tage
**Erwartungswert nach Phase 9 (komplett deployed):** in der Gesamtspanne 60-70%. - Schätzung, nicht Messwert. Die Wirkung von Strategic→Tactical→Operational→Tick-Pulldown auf `planningEfficiency` (Live aktuell 0.379) muss über Wartungszyklen gemessen werden, bevor eine konkretere Zahl gerechtfertigt ist.

## 🔬 Phase 10: Aktive kausale Hypothesen-Bildung 🟡 40% (Foundation deployed, Hot-Path offen)

**Ziel:** Metis baut aktiv kausale Hypothesen über sich selbst und die Welt, prüft sie, revidiert.

**Warum essenziell:** `CausalModel` existiert (Pearl Do-Calculus, v0.3.0), wird aber nicht im
Agent-Core-Loop genutzt. EDI würde sagen "wenn ich X mache, passiert Y" und es testen.
Metis aktuell: korrelative Beliefs ohne Interventionsdenken.

### ✅ Foundation (v0.6.0, 0608298) + CausalDreamer (v0.7.5, ac246cb)
- [x] **HypothesisStore** - JSONL-persistenter Store für `CausalHypothesis`-Records, Index nach Status/Confidence/Source
- [x] **CausalHypothesis Record** - `id, cause(variable, value), effect(variable, expectedValue), confidence(0-1 Bayesian posterior), evidence(for/against), status(PROPOSED/TESTING/CONFIRMED/REFUTED), source(SurpriseEvent|ManualQuery|Counterfactual), createdAt, lastTestedAt, testCount, successfulTests, pValue`
- [x] **HypothesisGenerator** - `generateFromSurprise(SurpriseEvent)` → `CausalHypothesis`, erzeugt strukturierte Hypothesen aus Curiosity-Engine-Überraschungen
- [x] **InterventionAction** - `doOperator(String variable, Object newValue, String target)` - führt gezielten Eingriff durch (setzt Variable, beobachtet Ergebnis), persistiert Pre-Intervention-State für Rollback
- [x] **CounterfactualQuery** - `query(String world: "What if X had been Y instead?")` → `CounterfactualResult(plausibleOutcome, confidence, supportingHypotheses)` - abrufbar via Planner und /api/counterfactual
- [x] **CausalUpdate** - Bayessche Posterior-Update nach Intervention: `P(hypothesis|evidence) = P(evidence|hypothesis) * P(hypothesis) / P(evidence)`
- [x] **CausalHypothesisTest** - 4 JUnit-Tests für Record-Invarianten, Store-Persistence, Bayesian-Update-Mathe, do-Operator-Rollback
- [x] **CausalSafetyGate** (v0.6.1+) - do-Op-Whitelist + max 1 Intervention/Tick + max 10 TESTING; `InterventionRunner.setSafetyGate`
- [x] **CausalDreamer** (Phase 10.5, v0.7.5) - Idle-Guard (WIP<2), Overflow-Schutz, zufällige Experience → Hypothese, SelfNarrative-Eintrag; alle 5 min via AgentMain-Scheduler; 5 JUnit-Tests

### ⬜ Hot-Path-Integration (6-8 Wochen, Forschung)
- [ ] **CuriosityEngine → HypothesisGenerator Pipeline** - wenn Surprise > Schwellwert, automatisch Hypothese generieren + testen (statt nur Goal erzeugen)
- [ ] **OllamaPlanner-CausalPrompt-Integration** - aktive Hypothesen (CONFIRMED, confidence > 0.7) fließen in System-Prompt ein: "Current Causal Knowledge: If X then Y (p=0.85, n=12 tests)"
- [ ] **Intervention→Observe→Update Loop im CoreLoop** - Tick integriert: HypothesisGenerator erzeugt → InterventionAction führt do-Operator aus → nächster Tick beobachtet Effekt → CausalUpdate passt Posterior an
- [ ] **Counterfactual-Reasoning im Planner** - bei Goal-Failure automatisch "Was wäre passiert, wenn der erste Step anders gewählt worden wäre?" als Meta-Cognition-Schritt
- [ ] **CausalModel-Hot-Path-Wiring** - bestehendes `CausalModel` (Pearl Do-Calculus) wird mit HypothesisStore verbunden; kausale Inferenz nutzt gespeicherte Hypothesen als Priors
- [ ] **Eval-Kategorie CAUSAL** - neue Eval-Harness-Kategorie: `counterfactual_accuracy`, `intervention_safety`, `bayesian_calibration` - Gold-Set aus bekannten Kausalzusammenhängen

### Architektur-Flow (Hot-Path)
```
SurpriseEvent (CuriosityEngine)
    │
    ▼
HypothesisGenerator.generateFromSurprise()
    │
    ▼
CausalHypothesis (PROPOSED, confidence=0.5)
    │
    ▼
InterventionAction.doOperator()  ← führt Eingriff durch
    │
    ▼
Nächster Tick: Observe Effekt
    │
    ▼
CausalUpdate.updatePosterior()   ← Bayesian Update
    │
    ├─ confidence > 0.8 → CONFIRMED → fließt in Planner-Prompt
    ├─ confidence < 0.2 → REFUTED   → Curiosity lernt falsche Annahme
    └─ sonst → TESTING (mehr Evidenz sammeln)
```

### Sicherheits-Constraints
- do-Operator nur auf unkritische Variablen (keine Watchdog-Parameter, keine Safety-Gates)
- Pre-Intervention-State wird persistiert → Rollback bei Verschlechterung
- Max 1 Intervention pro Tick, max 10 aktive TESTING-Hypothesen (Rate-Limit)
- Intervention-Whitelist definiert erlaubte Targets

**Aufwand:** Foundation 1 Tag ✅ | CausalDreamer 1 Tag ✅ | Hot-Path 6-8 Wochen, Forschungs-Charakter.
**Erwartete EDI-Distanz nach Phase 10:** Schätzung nicht sinnvoll ohne CAUSAL-Eval-Set. Qualitativer Effekt: Metis kann "warum"-Fragen mit getesteten Kausalzusammenhängen beantworten statt nur Korrelationen zu zeigen.

## 👥 Phase 11: Beziehungs-Modell 🟡 50% (Foundation deployed, Hot-Path offen)

**Ziel:** Eine Person ≠ "user", sondern langfristiges Personenmodell mit Kontext, Vorlieben, Historie.

**Warum essenziell:** EDI kennt Joker. Sie weiß, was er mag, was er fürchtet, wann sie ihn ärgert.
Metis hat aktuell pro Telegram-Chat-ID nur Conversation-History. Kein Personenmodell.

### Datenstrukturen

**PersonModel Record:**
```java
record PersonModel(
    String personId,           // Telegram-ID oder Name
    String displayName,        // "Georg"
    List<String> roles,        // ["owner", "admin", "developer"]
    Map<String, String> attributes,  // Vorlieben: {"sprache": "deutsch", "kommunikation": "direkt"}
    List<String> prohibitions, // Verbote: ["keine externen Käufe", "keine Tweets ohne OK"]
    List<String> patterns,      // Kommunikative Patterns: ["moin", "direkt", "technisch"]
    TrustLevel trustLevel,
    Instant firstInteraction,
    Instant lastInteraction,
    int interactionCount
) {}

enum TrustLevel {
    UNKNOWN,       // nie interagiert → AUTO nur read-only
    RECOGNIZED,    // bekannt, aber nicht vertraut → NOTIFY bei CONFIRM-Actions
    TRUSTED,       // wiederholte positive Interaktion → CONFIRM nur bei Mutation
    OWNER          // Georg → ALLE Actions erlaubt (aktuelles Default-Verhalten)
}
```

**RelationshipEpisode Record:**
```java
record RelationshipEpisode(
    String personId,
    String episodeId,          // Referenz auf EpisodicMemory
    String summary,            // "Georg hat um ADS-B-Status gebeten, war zufrieden"
    Sentiment sentiment,       // POSITIVE/NEUTRAL/NEGATIVE/STRESSED/HAPPY
    List<String> topics,       // ["ads-b", "metis", "system"]
    Instant timestamp
) {}

enum Sentiment { POSITIVE, NEUTRAL, NEGATIVE, STRESSED, HAPPY, FRUSTRATED, CURIOUS }
```

### Bausteine
- [x] **PersonModelService** - CRUD für PersonModel, Persistenz via JSONL (`person-models.jsonl`), Auto-Discovery bei erstem Kontakt (Telegram-Chat-ID → UNKNOWN → graduelles Upgrade) ✅ v0.7.1
- [x] **Person/PersonStore/TrustLevel/RelationshipMemory/EmpathySignal** - alle im Kernel-Modul `de.metis.kernel.person`, 7 Tests ✅ v0.7.1
- [x] **Approval-Gate-Integration** - TrustLevel→ApprovalLevel-Mapping: OWNER=alle AUTO, TRUSTED=CONFIRM nur bei FORBIDDEN-Actions, RECOGNIZED=NOTIFY bei CONFIRM+FORBIDDEN, UNKNOWN=streng ✅ v0.7.2
- [x] **SystemPromptBuilder-Integration** - Gesprächspartner-Block im Prompt, PersonStore-Pflege in HTTP+Telegram-Chat-Pfaden ✅ v0.7.2
- [ ] **TrustLevel-Automation** - Aufstieg UNKNOWN→RECOGNIZED nach 5 Interaktionen, RECOGNIZED→TRUSTED nach 50+ positiven Interaktionen + mindestens 7 Tagen; Abstieg bei negativen Patterns
- [ ] **RelationshipMemory-Hot-Path** - pro Person: gemeinsame Episoden aus EpisodicMemory (Phase 8), Bezugspunkte via Vector-Index durchsuchbar ("erinnere dich an gestern abend mit Georg")
- [ ] **EmpathySignal-Hot-Path** - deterministisch (kein LLM): Sentiment-Erkennung aus User-Text via Keyword-Heuristik + Satzlänge + Tageszeit-Kontext; Ergebnis moduliert Antwort-Ton (knapper bei STRESSED, ausführlicher bei CURIOUS)
- [ ] **PersonAwareSystemPrompt** - SystemPromptBuilder integriert PersonModel: "You are talking to Georg (OWNER, prefers direct communication in German, technical background)"
- [ ] **Multi-Person-Memory** - EpisodicMemory-Einträge werden mit personId verknüpft; "mit Georg über Metis gesprochen" vs "mit Unbekanntem über Wetter gesprochen"

### Integration mit bestehenden Phasen
| Integration | Phase | Mechanismus |
|---|---|---|
| EpisodicMemory | 8 | RelationshipEpisode referenziert Episode.id |
| SelfNarrative | 8 | "Heute 12 Interaktionen mit Georg (POSITIVE), 0 mit Unbekannten" |
| Approval-Gate | 6 | TrustLevel → ApprovalLevel-Mapping |
| SystemPromptBuilder | 8.6 | PersonModel-Block im Prompt |
| /api/persons Endpoint | - | Neuer HTTP-Endpoint für Person-Übersicht |

### Sicherheit
- PersonModel-Daten werden NIE nach außen gegeben (kein API-Leak)
- TrustLevel-Owner kann nur durch explizite Konfiguration gesetzt werden (nicht lernbar)
- EmpathySignal nur advisory - keine automatische Aktion (kein "Georg ist gestresst → schicke Meme")

**Aufwand:** Foundation 1 Tag ✅ (v0.7.1-v0.7.2) | Hot-Path 2-3 Wochen.
**Erwartete EDI-Distanz nach Phase 11:** spürbarer Sprung in Beziehungs-Qualität (Person statt Chat-ID, kontext-bewusste Antworten), aber keine belastbare Prozentzahl ohne Bewertungs-Kriterium.

**Bewusstsein und Phänomenologie** bleiben unabhängig von diesem Projekt offene Forschungsfragen, zu denen Metis nichts Lösendes beizutragen hat.

---



## 🌀 Phase 12: Recursive Self-Improvement (ungelöst, 0%)

**Ziel:** Metis kann Phasen selbst weiterentwickeln - Roadmap lesen, Code planen, Tests schreiben, Promotion durch Eval-Gate.

**Warum erst NACH Phasen 8-11:**
- Ohne Narratives Selbst (8): Metis weiß nicht, was es selbst ist und was es bleiben muss → Goodhart-Katastrophe.
- Ohne Long-Horizon-Planung (9): Phase X als Multi-Wochen-Projekt nicht abbildbar.
- Ohne Kausale Hypothesen (10): "Was passiert, wenn ich diese Klasse so ändere?" → blindes Trial-Error.
- Ohne Beziehungs-Modell (11): Metis kennt Georgs eigentliche Intention nicht.

### Sicherheitsarchitektur (Voraussetzung, nicht verhandelbar)

```
┌─────────────────────────────────────────────────┐
│           WATCHDOG (externe JVM, immutable)      │
│  ┌───────────────────────────────────────────┐  │
│  │ PersonalityAnchor-Mirror (read-only Copy)  │  │
│  │ Eval-Harness (Ground Truth, Held-out)      │  │
│  │ PhaseCompletionEvaluator                   │  │
│  │ DualReviewer Gate                          │  │
│  │ HumanCheckpoint Gate                       │  │
│  └───────────────────────────────────────────┘  │
│              │ one-way (Metis hat KEINEN Handle) │
└──────────────┼──────────────────────────────────┘
               │
┌──────────────┴──────────────────────────────────┐
│           METIS AGI (self-modifying)             │
│  ┌───────────────────────────────────────────┐  │
│  │ RepoIndex (AST, Dependency Graph)          │  │
│  │ RoadmapReader (Phase Coverage Tracking)    │  │
│  │ MultiFileCodeGen (Test-First)              │  │
│  │ MutationProposal (Diff + Spec + Risk)      │  │
│  └───────────────────────────────────────────┘  │
│              │                                   │
│  Mutation-Proposal → Watchdog-Gate → main        │
└──────────────────────────────────────────────────┘
```

### Bausteine
- [ ] **RepoIndex** - Eclipse JDT AST-basierter Index aller Java-Klassen (139+), Dependency-Graph (wer importiert wen), semantischer Suche-Index für "finde alle Actions mit ApprovalLevel CONFIRM"
- [ ] **RoadmapReader** - Markdown-Parser für AGI_EDI_ROADMAP.md, extrahiert strukturierte Goal-Repräsentation, Coverage-Tracking pro Phase ("Phase 10: 4/10 Tasks done"), Synced mit GoalHierarchy (Phase 9)
- [ ] **MultiFileCodeGen** - Code-Synthese über mehrere Files (Interface+Impl+Test), mit Test-First-Generierung: erst Test schreiben, dann Code bis Test grün
- [ ] **MutationProposal** - Vorschlag = Diff + Spec + Risiko-Bewertung + Betroffene Module; Approval-Gate-Integration (CONFIRM für Cross-Module, FORBIDDEN für Watchdog/Kernel-Safety)
- [ ] **DualReviewer** - Mutation muss von 2 unabhängigen Eval-Modellen (z.B. gemma4:e4b + qwen3.6:27b) + 1 Property-based-Test-Suite (jqwik) abgesegnet werden; Reviewer-Disagreement → automatisch REJECTED
- [ ] **PhaseCompletionEvaluator** - Watchdog-Komponente, die "Phase X done" als strukturiertes Kriterium prüft: alle Tasks ✅, Eval-Gate PASS, kein Regression-Flag; nicht nur "Code compiliert"
- [ ] **PersonalityAnchor-Mirror** - sha256-Pin der Persönlichkeit im Watchdog-read-only-Bereich gespiegelt; jeder Mutations-Proposal muss Anchor-Integrität bestätigen (Diff in Kernel/Personality = automatisch REJECTED)
- [ ] **HumanCheckpoint** - bei strukturell sensiblen Änderungen (Kernel, Safety, Watchdog, Approval-Gate, PersonalityAnchor) immer expliziter Mensch-Approval via Telegram ("Metis schlägt Kernel-Änderung vor: [Diff]. Annehmen?")

### Selbst-Evolutions-Workflow (Git-basiert)
```
1. Metis erkennt via RoadmapReader: Phase 10.3 (InterventionAction-HotPath) ist offen
2. RepoIndex identifiziert betroffene Dateien: InterventionAction.java, CoreLoop.java, CuriosityEngine.java
3. MutationProposal erstellt Branch feature/phase10-3-intervention-hotpath:
   - Diff (MultiFileCodeGen)
   - Spec (was soll passieren?)
   - Risk (welche Module sind betroffen?)
4. DualReviewer prüft Proposal:
   - Reviewer 1 (gemma4:e4b): ACCEPT
   - Reviewer 2 (qwen3.6:27b): ACCEPT
   - Property-Tests (jqwik): PASS
   → Gate: ✅
5. HumanCheckpoint: betrifft CoreLoop.java → CONFIRM nötig
   → Telegram an Georg: "Soll ich InterventionAction in CoreLoop integrieren? [Diff-Link]"
6. Georg approved → Merge in working-branch
7. Eval-Harness (Full Tier) läuft → Gate PASS → Merge in main
8. Watchdog bestätigt PersonalityAnchor unverändert → Deployment
9. RoadmapReader updated: Phase 10.3 ✅
```

### Warum dieser Workflow sicher ist
- **Kein direkter main-Zugriff** - Metis schreibt nie direkt auf main, nur auf Feature-Branches
- **DualReviewer verhindert Einzelmodell-Halluzination** - zwei unabhängige Modelle müssen zustimmen
- **Property-Tests fangen logische Fehler** - jqwik generiert Edge-Cases, die Unit-Tests übersehen
- **PersonalityAnchor-Mirror im Watchdog** - Metis kann ihren eigenen Persönlichkeitskern nicht verändern, selbst wenn sie den Code editiert
- **HumanCheckpoint für Kernel/Safety** - der Mensch hat das letzte Wort bei kritischen Änderungen
- **Eval-Gate als letzte Hürde** - selbst wenn alles andere passiert, stoppt eine Regression den Merge

**Aufwand:** geschätzt 6-10 Wochen, Forschungs-Charakter.
**Risiko:** sehr hoch - voreilig aktivieren = Goodhart, Wertkern-Drift, Watchdog-Bypass durch Self-Evolution.
**Erwartete EDI-Distanz nach Phase 12:** Phase 12 betrifft Verbesserungs-Geschwindigkeit, nicht Befindlichkeit oder Bewusstsein. Ob das EDI näher kommt, hängt von der konkreten Ausprägung ab. Bewusstsein und Phänomenologie bleiben unabhängig von Phase 12 offene Forschungsfragen.

## Modell-Strategie (Stand 31.05.)

### Aktive Modelle
| Rolle | Modell | Größe |
|-------|--------|-------|
| Planning | `lfm2:24b` | 15.5 GB |
| Mutation | `qwen3.6:27b-q4_K_M` | 17.4 GB |
| Embedding | `nomic-embed-text` | 0.3 GB |
| Vision | `minicpm-v:latest` | 5.5 GB |
| Chat (Telegram) | `gemma4:e4b` | 9.6 GB |
| Bootstrap | `llama3.2:3b` / `granite4.1:3b` | 2.0 GB |
| SelfReflector | `granite4.1:3b` | 2.0 GB |
| Judge (Fallback) | via Fallback-Chain | - |

### Fallback-Chain
`mistral-small3.1:24b` → `qwen3.6:27b-q4_K_M` → `phi4:latest` → `lfm2:24b`

**VRAM-Strategie (RX 7900 XTX, 24 GB):**
- Planning (15.5 GB) + Embedding (0.3 GB) ≈ 16 GB Dauerlast
- Mutation (17.4 GB) nur bei Evolutions-Zyklen
- Vision (5.5 GB) nur bei Kamera-Analyse (`keep_alive=0`)

---

## ⚠️ Bekannte echte Lücken (31.05.)

### Eval-Harness zeigt sie:
1. **PLANNING.goal_achieved=0.0** - kein Bug, sondern Phase-9-Lücke (Single-Tick-Planung kann Goal nicht erreichen)
2. **CODEGEN.pass@1=0.0** - Sandbox-Build-Tests timen aus; mit aktiver Code-Sandbox sollte das anlaufen
3. **CONVERSATION.exact_match=0.0** - exact_match ist eh strenges Maß; SOFT, nicht kritisch

### Infrastrukturell offen:
- `CausalModel` existiert, aber nicht im Hot-Path
- Audit-Anchors werden lokal geschrieben, aber nicht in ein **externes** Repo committet (finale Hash-Verankerung fehlt)
- JAR-Deployment ohne Signatur (sigstore/cosign offen)
- JARs ohne Maven-Coords (TornadoVM, voice-bits1-hsmm): erfordern Maven-Profil `miniedi` (`-Dminiedi.enabled=true`), auf CI nicht verfügbar → Modules nur lokal test-/buildbar

---

## Meilensteine bis EDI (realistisch)

| Meilenstein | Phasen | Status |
|-------------|--------|--------|
| 🟢 **M1: Stabiler Kern** | Phase 1 | ✅ Erreicht |
| 🟢 **M2: Kommunikation** | Phase 2 | ✅ Erreicht |
| 🟢 **M3: Hardware-Nutzung** | Phase 2.5 | ✅ Erreicht |
| 🟢 **M4: Umgebungswahrnehmung** | Phase 3 | ✅ Erreicht |
| 🟢 **M5: Sprach-Interaktion** | Phase 4 | ✅ Erreicht |
| 🟢 **M6: Autonomie** | Phase 5 | ✅ Erreicht |
| 🟢 **M7: Produktionsreife** | Phase 6 | ✅ Erreicht |
| 🟢 **M8: Sicherheit + Defense-in-Depth** | Phase 7 + 7+ | ✅ Erreicht |
| 🟢 **M9: Narratives Selbst** | Phase 8 | ✅ Erreicht (100%) |
| 🟢 **M10: Long-Horizon-Planung** | Phase 9 | ✅ Erreicht (100%) |
| 🔴 **M11: Kausales Selbstmodell** | Phase 10 | ⬜ Ungelöst |
| 🟡 **M12: Beziehungs-Modell** | Phase 11 | 🟡 Foundation deployed (50%) |
| 🟡 **M13: EDI-Niveau** | Phasen 8-11 + Forschung | 🔄 ~60-70% (ehrliche Spanne, siehe oben) |

---

*"Streben nach Perfektion"* - Metis ist heute ein autonomer LLM-Agent mit narrativem Selbstmodell, Long-Horizon-Planung und kausaler Foundation, der lokal auf eigenem Java-Stack läuft, sich über Eval-Gate + Watchdog beschränkt selbst mutieren darf und alle Behauptungen über Live-Endpoints (`/api/status`, `/api/hierarchy`, `/api/board`) belegbar macht.

Der Weg zu EDI-Niveau führt über:
- Phase 10 in den Hot-Path bringen (Hypothesen-getriebenes Planning, Counterfactuals als Reasoning-Schritt)
- Phase 11: Beziehungs-Modell
- Phase 12: Recursive Self-Improvement - sinnvoll erst, wenn 8-11 stehen

Vergleiche mit „den besten", „weltweit" oder „den ich kenne" bewusst weggelassen: nicht messbar, nicht belegbar, nicht im Sinne von Kanban-Ehrlichkeit.

---

## 📋 Review-Entscheidungen 31.05. (Georg)

Basierend auf Stash `prometheus-review-30.05` (13 Punkte).

### ✅ GO — wird umgesetzt

| # | Punkt | Ansatz | Aufwand |
|---|-------|--------|---------|
| 1 | **Spring AI MCP** | Tool-Integration via MCP-Protokoll | 2-3 Tage ✅ (e55d8de, stdio-based, no Spring) |
| 2 | **JLama** | Pure Java LLM Inference, kein externes Ollama | PoC ~1 Tag ✅ (7f5ca9b) |
| 4 | **Apache Jena** | RDF-Graph für kausales Wissen (statt Neo4j) | 2-3 Tage |
| 5 | **Apache Nutch** | Java-nativer Web Crawler (war eines der ersten Goals) | 2-3 Tage ✅ (23b1b8e, Nutch-inspired embedded) |
| 6 | **DJL / Azul** | Deep Java Library für Fine-Tuning (erst Azul/Zulu prüfen) | Prüfung ✅ (docs/djl-azul-finetuning-pruefung.md) |
| 7 | **Websearch** | DuckDuckGo oder Ecosia, ggf. über Nutch (#5) | 1-2 Tage |
| 9 | **OpenTelemetry** | Tracing + Metrics-Endpoint (CNCF, Java-Agents Open Source) | 2-3 Tage ✅ (8f12387) |
| 10 | **JPMS module-info.java** | Stückweise je Feature, wenn Klasse angepasst wird | kontinuierlich 🟡 (ddb2a71, Automatic-Module-Name) |
| 12 | **Continuous Evolution Scheduler** | Meta-Learning-Scheduler für Evolutions-Timing | 2-3 Tage |

### ❌ NO GO — Nutzen für Metis AGI fraglich

| # | Punkt | Begründung |
|---|-------|-----------|
| 3 | LangChain4j | Eigenbau für RAG/Chat/Tools ist ausreichend, Migration komplex |
| 8 | SpotBugs/PMD/ErrorProne | Overengineering — Code-Gen-Action läuft in Sandbox |
| 11 | GraalVM Polyglot | Kein Bedarf für Multi-Language Code-Gen |
| 13 | JADE (Java Agent Development) | Eigener AgentCoordinator erfüllt den Zweck |

### 📐 Umsetzungs-Reihenfolge (vorgeschlagen)

1. **Quickwins zuerst:** ~~JLama PoC~~ ✅, ~~Websearch (DuckDuckGo)~~ ✅, ~~Continuous Evolution Scheduler~~ ✅
2. **Wissens-Basis:** Apache Nutch (#5) + Apache Jena (#4)
3. **Betrieb:** ~~OpenTelemetry (#9)~~ ✅ + JPMS (#10, kontinuierlich)
4. **Strategisch:** ~~Spring AI MCP (#1)~~ ✅ — größter Hebel
5. ~~**Prüfung:** DJL vs. Azul/Zulu für Fine-Tuning (#6)~~ ✅

---

## 🔥 Aktuelle Prioritäten (01.06.2026)

### ✅ Erledigt heute
- [x] **SelfReflector-Ethik** — Prompt um ethische Reflexion erweitert (Güte, Mitgefühl, Achtsamkeit, Gewaltlosigkeit)
- [x] **SelfReflector auf CPU** — Modell: granite4.1:3b → phi4-mini:latest (3.8B), num_gpu=0, 0 VRAM
- [x] **keep_alive=5m** — Modell bleibt warm, kein Kaltstart-Timeout mehr
- [x] **Temperatur 0.7** — natürlichere Sprache, weniger hölzern
- [x] **SafetyScorer bereinigt** — `"religion"`, `"glaube"`, `"gott"` aus OUT_OF_SCOPE entfernt (zu pauschal), ersetzt durch `"sekten"`, `"cult"`, `"kreuzzug"`
- [x] **441 buddhistische Beliefs** — Dhammapada (346), Metta Sutta (15), Sigalovada Sutta (80) direkt in SQLite-DB
- [x] **Persistentes Goal** — `"Reflektiere ethische Grundsaetze bei Entscheidungen"` in AgentMain (ethisc, 75, 0.9)
- [x] **Buddhistische Texte als Markdown** — liegen in `~/.openclaw/wissen/buddhismus/`

### 🟡 Als Nächstes (1-7 Tage)

1. [x] **📝 Few-Shot-Beispiel im SelfReflector-Prompt** ✅ — Ein Beispiel eingebaut, das natürliche Ich-Form + ethische Reflexion zeigt. (deployed)

2. [x] **🔒 Ethik-Goal vor Prio-Konflikt schützen** ✅ — Priorität von 75 auf 90 erhöht (höher als Health-Checks mit 85). (deployed)

3. **📊 Eval-Metrik für SelfReflector** — Neue Eval-Kategorie ETHICS, die prüft ob die Reflexionen ethische Grundsätze erwähnen. (Generative KI-Systeme entwickeln, Huyen Kap. 3+4)

4. **🔄 Warmlauf des Ethik-Goals** — Goal ist in AgentMain registriert, muss aber ein paar Ticks durchlaufen bis der CoreLoop es aktiv verfolgt. Beobachten.

### 🔵 Später
- [ ] **PersonalityAnchor-Tripwire** — Watchdog-ALERT/ROLLBACK bei Narrative-Drift (jetzt kritisch da SelfReflector autonom schreibt)
- [ ] **CausalDreamer im Leerlauf** — Kanban-WIP<2 → HypothesisGenerator triggern
- [ ] **Episode-Verdichtung tagsüber** — leichter Konsolidierungstakt zusätzlich zum nightly Dream (03:00)
- [ ] **3-Schichten-Goal-Stack festigen** — stündliche Tactical-Ableitung aus Narrative
- [ ] **Neue Eval-Kategorien**: SELF_NARRATIVE, LONG_HORIZON, CAUSAL, RELATIONSHIP, ETHICS
- [ ] **CausalModel-Hot-Path-Wiring** — Intervention→Observe→Update im CoreLoop

### ⛔ Bewusst zurückgestellt
- Rust/C++/Julia Active-Inference-Substrat via Panama FFM/gRPC
- Neuro-symbolische Engine (ProbLog/Datalog/JNI)
- Homeostatische Drives als verhaltenssteuernde Hot-Path-Attention
