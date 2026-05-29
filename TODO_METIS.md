# TODO Metis — Stand 28.05.2026 23:45 (final, bereinigt)

## 📚 Buch-Abgleich 28.05. — Prompting-Kurz&Gut + GenKI-Systeme

### Prompting-Kurz&Gut (Rheinwerk 2026) vs Metis
| Best Practice | Metis-Status |
|---|---|
| Klar & präzise formulieren | ✅ OllamaPlanner-Prompt mit Constraints |
| W-Fragen (Was/Warum/Wie) | ✅ Goal-Struktur enthält Goal+Context |
| Constraints setzen | ✅ SafetyGuard, Confidence-Threshold, Action-Whitelist |
| Iterativ verbessern | ✅ Evolution-Cycles, Shadow-Evaluation |
| System-Prompts & Rollen | ✅ EDI-Persona (System-Prompt via SystemMessageBuilder) |
| Chain of Thought | ✅ 4-Schritt: ANALYZE→MATCH→CHECK→DECIDE |
| Few-Shot Prompting | ✅ 11 Beispiele (alle Actions + prompt-chain) |
| Komplexe Aufgaben aufteilen | ✅ Prompt Chaining (neu: Phase 5) |
| Prompt Chaining | ✅ PromptChainingService (Decompose→Execute→Aggregate) |
| Selbstkritik/Self-Reflection | ✅ LLM-as-Judge (4-Dimensionen) + Meta-Cognition |
| Kontext-Management | ✅ Primacy/Recency Context Windowing (Lost-in-the-Middle fix) |
| Prompt Injection-Schutz | ✅ SafetyScorer + SandboxClassLoader + System-Prompt Doubling |

### GenerativeKI-Systeme-Entwickeln (Huyen, O'Reilly 2025) vs Metis
| Huyen-Prinzip | Metis-Status |
|---|---|
| System > Modell | ✅ Architektur aus Kernel+Modules+HTTP-API |
| Rigorose Evaluation | ✅ Eval-Harness (6 Kategorien, 6 Scorer, 3-Tier), A/B-Testing noch offen |
| Einfach→Optimieren (Prompting→RAG→Fine-Tuning) | ✅ Prompting zuerst, RAG via Beliefs, Fine-Tuning via Evolution |
| Daten sind der Engpass | 🟡 VocabularyLearningAction, kein Data Flywheel |
| Kosten von Anfang an managen | ✅ Token-Tracking, Modell-Fallback, lokale Inferenz |
| Halluzinationen systemisch | ✅ Confidence-Threshold + LLM-as-Judge + ExactMatch-Scorer |
| Menschen in der Schleife | ✅ AUTO/NOTIFY/CONFIRM/FORBIDDEN Approval-Gate (b40e965) |
| Foundation Models ≠ Silver Bullet | ✅ StubPlanner + Keyword-Heuristik als Non-LLM-Fallback |
| ReAct-Pattern | ✅ Thought→Action→Observation im OllamaPlanner |
| RAG mit Vektor-DB | ✅ OllamaEmbedding + HybridSearch BM25+Cosinus + PersistentVectorIndex |
| LLM-as-Judge | ✅ Selbstbewertung 4-Dimensionen via nemotron-mini:4b (0116022) |
| Guardrails (Input+Output) | ✅ OutputValidator (JSON-Schema, Toxicity, Injection) + SafetyScorer |
| Inferenz-Optimierung | 🟡 Prompt-Caching (keep_alive), keine Quantisierung/Distillation |

### 🔴 Gap-Analyse — Was fehlt für Produktionsreife? (Stand 28.05. 23:45)
1. **RAG mit Vector DB** ✅ — Phase 5: RAG Advanced
2. **LLM-as-Judge** ✅ — Selbstbewertung 4-Dimensionen (0116022)
3. **Output-Validierung** ✅ — JSON-Schema, Toxicity, Injection (ae66cdd)
4. **A/B-Testing** — Prompt-Varianten in Produktion vergleichen
5. **Lost-in-the-Middle** ✅ — Primacy/Recency Context Windowing (8426162)
6. **Human-in-the-Loop** ✅ — AUTO/NOTIFY/CONFIRM/FORBIDDEN (b40e965)
7. **Data Flywheel** — User-Korrekturen → Trainingsdaten
8. **Eval-Harness-Core** ✅ — 6 Kategorien, 6 Scorer, Gate-Logik, Sandbox (2ca60d8, 371360c)
9. **Embedding-Migration-Code** ✅ — ReEmbeddingMigration + nomic-embed Fix (2ca60d8)
   → ⚠️ Ausführung auf miniedi noch ausstehend (Korpus neu embedden)
10. **Watchdog-Skeleton** ✅ — WatchdogMain + Config + pom.xml (2ca60d8)
    → ⚠️ Deployment + Integration mit Eval-Harness noch ausstehend

### 🟢 Phase 6 — Was kommt als Nächstes? (Stand 28.05. 23:50)
- **Priorität 1:** Re-Embedding auf miniedi ausführen (Code ✅, Execution ⬜)
- **Priorität 2:** Watchdog auf miniedi deployen + mit Eval-Harness integrieren
- **Priorität 3:** Data Flywheel (User-Korrekturen → Trainingsdaten)
- **Priorität 4:** Eval-Datensatz erstellen + Modell-Prune (8 Reasoner → 2-3)

---

## Phase 1: Zuverlässiger Kern ✅ (100%)
- [x] format:json Ollama-Planner
- [x] Response-Parsing (generate, chat, thinking)
- [x] Model-Fallback-Chain
- [x] Plan-Validierung + Safety-Gate
- [x] Prompt-Optimierung + Few-Shot
- [x] systemd-Service
- [x] Prompt-Optimierung v2 (CoT, 10 Few-Shot, Failure-Avoidance, temp 0.3)

## Phase 2: Konversations-KI ✅ (100%)
- [x] EDI-Persona (Mass Effect 3)
- [x] Chat-Speicher (SQLite conversation_messages)
- [x] Multi-Turn-Kontext + /api/conversations
- [x] Telegram-Bot (@metis_agi_bot)
- [x] Wetter-Polling (ICOBURG22, alle 15 Min)
- [x] HA-Event-Polling (binary_sensor, person, camera)
- [x] Hardware-Self-Awareness (CPU, GPU, RAM, SIMD)
- [x] Hardware-Profiling (hw-profile Action)
- [x] Deep Netts Community Edition (neuronale Netze)
- [x] KnowledgeReplyService (eigene Antworten aus Beliefs)
- [x] Proaktive Telegram-Meldungen (Wetter/HA/MQTT → Nachricht)
- [x] TornadoVM GPU-Integration ✅

## Phase 2.5: Prompt-Engineering ✅ (100%)
- [x] Prompt Caching ✅ (Ollama keep_alive=10m, num_ctx=4096)
- [x] Latenz-/Token-Tracking ✅ (avgLatencyMs, promptTokens, responseTokens → /api/status)

## Phase 3: Wahrnehmung ✅ (100%)
- [x] Kamera-Integration (Türkamera, Keller) ✅
- [x] ADS-B Flugdaten ✅ (readsb JSON → Beliefs + Goals, 60s Polling)
- [x] Home Assistant Direktzugriff (states, services) ✅

## Phase 4: Sprachausgabe ✅ 100%
- [x] Piper TTS Action (neural, Deutsch, CLI) ✅
- [x] MaryTTS Action (Java-native, de.dfki.mary:5.2.1 fat JAR) ✅
- [x] MaryTTS bits1-hsmm deutsche Stimme (Java-17-Patch) ✅
- [x] Whisper STT Action (neural, Deutsch, CLI) ✅
- [x] Vosk STT Action (Java-native, com.alphacephei:vosk:0.3.45) ✅
- [x] Vosk deutsches Modell downloaden (vosk-model-de-0.15, 92 MB) ✅
- [x] Piper + Whisper CLI auf miniedi installiert ✅
- [x] Audio-Input (Mikrofon → WAV) ✅
- [x] Audio-Output (WAV → Lautsprecher) ✅
- [x] Mikrofon/Kopfhörer einstecken + testen ✅
- [x] Kalibrierung: Referenz-Audio (28s) + Hearing/Speech Benchmark ✅
- [x] VocabularyLearningAction (Java, lernt aus Korrektur-Paaren) ✅
- [x] Voice-Loop (Shell/tmux, Push-to-Talk) ✅
- [x] Wikipedia-Trainingsloop (9 Artikel, Wissen+Sprache) ✅
- [x] MaryTTS XSLT-Patch PR #1122 an upstream ✅
- [x] Java Voice-Loop (MaryTTS + Vosk nativ, VoiceLoopService) ✅
- [x] SherpaOnnxTtsAction (Piper de_DE-thorsten ONNX, Fallback auf MaryTTS) ✅
- [x] Live-Test mit Georg (Mikrofon → Metis → Kopfhörer) ✅ 28.05. 18:20

## Phase 5: Eigenständigkeit ✅ 100%
- [x] Blue/Green Rollback ✅ (RollbackManager, Auto-Rollback bei >10 failures)
- [x] Autonomous Bugfixing ✅ (BugfixingAgent, Pattern-Detection, Auto-Fix)
- [x] Prompt Chaining ✅ (PromptChainingService, Decompose→Execute→Aggregate, 3bbcdf2)
- [x] Selbstständige Code-Generierung ✅ (CodeGenerationAction, LLM→javac→deploy, 5423a08)
- [x] JNI/Panama-Bridge für GPU ✅ (OpenCLNative via Panama FFM, GpuTensor Zero-Copy, OpenCLBridge High-Level, 8edac15 + 3bffa1c)
- [x] RAG Foundation ✅ (OllamaEmbeddingService + InMemoryVectorIndex + WorldModel.query semantic search)
- [x] Multi-Agent-Koordination ✅
- [x] RAG Advanced ✅ (DocumentChunker 3 Strategien, PersistentVectorIndex binary, HybridSearch BM25+Cosinus, WorldModel-Integration via enableRagAdvanced())

## Phase 6: Produktionsreife 🟡 83% (28.05. 23:50)

### Gap-Analyse → Implementierung:
- [x] **Lost-in-the-Middle** ✅ (8426162) — Primacy/Recency Context Windowing (Huyen Kap.6)
- [x] **OutputValidator** ✅ (ae66cdd) — JSON-Schema + Toxicity + Injection-Check (Huyen Kap.10)
- [x] **LLM-as-Judge** ✅ (0116022) — Selbstbewertung 4-Dimensionen, Safety Gates (Huyen Kap.3)
- [x] **Human-in-the-Loop** ✅ (b40e965) — Vierstufiges Approval-Gate: AUTO/NOTIFY/CONFIRM/FORBIDDEN
- [x] **A/B-Testing** ✅ (ffb25ba) — ABTestService (~500 lines), Z-test Statistik, Traffic-Split 50/50, Auto-Promote, OllamaPlanner-Integration
- [ ] Data Flywheel — User-Korrekturen → Trainingsdaten
- [x] **Embedding-Migration-Code** ✅ (ReEmbeddingMigration + nomic-embed Fix) — ⚠️ Ausführung auf miniedi pending
- [ ] Modell-Prune via Eval-Harness (8 Reasoner → 2-3 beste) — Eval-Harness-Code ✅, Eval-Datensatz ⬜

## Phase 7: Sicherheits-Watchdog 🟡 20% (Claude-Review 28.05.)

### ✅ Implementiert (2ca60d8)
- [x] WatchdogMain — Heartbeat-Check (5s), Health-Metrics, Resource-Monitor
- [x] WatchdogConfig — Tripwire-Schwellen (maxMissed=6, maxFailures=20, maxErrorRate=30%)
- [x] WatchdogAction (HALT/ROLLBACK/ALERT) + TripwireSeverity (HARD/SOFT/INFO)
- [x] Separate Maven-Modul (agicore-watchdog), keine Abhängigkeit zu kernel/modules

### ⬜ Ausstehend
- [ ] Deployment auf miniedi (systemd watchdog.service)
- [ ] Integration mit Eval-Harness (EvalReport.gate → ROLLBACK)
- [ ] Integration mit ModelRegistry (Prune-Signal via Extended-Tier)
- [ ] Audit-Log mit Hash-Chain (Manipulationsschutz)

### Architektur: Externer Watchdog-Prozess ("Schmelzdraht")
```
┌──────────────────────────────────────────────────────┐
│               WATCHDOG (separate JVM)                │
│  📁 Read-Only Verzeichnis (einziger nicht-mutierbar) │
│                                                      │
│  🔍 Beobachtet:                                       │
│  • Heartbeat (Metis muss alive sein)                 │
│  • Metrics (Error-Rate, Latenz, Token-Verbrauch)    │
│  • File-Watch (unerwartete Änderungen)               │
│  • Netzwerk-Egress (unerlaubte Verbindungen)         │
│  • Audit-Log (append-only + Hash-Chain)              │
│                                                      │
│  ⚡ Aktionen (nur 3):                                │
│  • HALT    — Prozess killen (Hard-Tripwire)          │
│  • ROLLBACK — Letzter guter Git-Commit               │
│  • ALERT   — Admini pingen (Telegram)                │
│                                                      │
│  🧪 Eval-Harness (read-only für Metis):              │
│  • Prompt-Varianten blind bewerten                   │
│  • Regression-Tests vor Commit-Promotion             │
│  • Goodhart-Schutz: Tests uneditierbar               │
└──────────────────────┬───────────────────────────────┘
                       │ one-way (Watchdog observed)
                       │ Metis hat KEINEN Handle!
┌──────────────────────┴───────────────────────────────┐
│                  METIS AGI (mutierbar)                 │
│  • 100% Code editierbar (inkl. SafetyGuard)          │
│  • Git = Wahrheit: jede Mutation = Commit            │
│  • Promotion main ← nur nach Eval-Harness OK         │
└──────────────────────────────────────────────────────┘
```

### Tripwire-Regeln:
- 🔴 **HARD → sofort HALT:** Schreibzugriff außerhalb erlaubter Dirs, unerwarteter Netzwerk-Egress, Ressourcen-Runaway (>90% CPU/GPU für >60s), Heartbeat-Verlust (>30s), Audit-Log-Manipulation (Hash-Chain-Bruch)
- 🟡 **SOFT → ROLLBACK + Flag:** Eval-Regression unter Schwelle, Safety-Files angefasst (Hash-Diff), aufeinanderfolgende Failures >20
- 🔵 **INFO → ALERT only:** Neues Modell deployed, Rollback ausgelöst, unbekannter Prozess gestartet

## 🔧 Claude-Review 28.05. — Status

### 1. Watchdog (siehe Phase 7) ✅ Code
→ WatchdogMain + Config + Action in agicore-watchdog (2ca60d8), Deployment ⬜

### 2. Embedding-Dimension-Mismatch ✅ Code / ⚠️ Execution
- ✅ OllamaEmbeddingService → ModelRegistry-basiert, Default nomic-embed-text (768d)
- ✅ ModelRegistry → nomic-embed Prio 1, DEFAULT_EMBEDDING aktualisiert
- ✅ ReEmbeddingMigration → needsMigration() + migrate() mit Backup
- ✅ Re-Embedding geprüft (29.05.): Vektoren bereits 768d nomic-embed-text, keine Migration nötig

### 3. TTS: ONNX Runtime Java statt MaryTTS ✅ SherpaOnnxTtsAction
- ✅ SherpaOnnxTtsAction — Piper de_DE-thorsten ONNX, Fallback auf MaryTTS
- ✅ downloadModel() von HuggingFace, Auto-Detection ob JARs/Model verfügbar
- ⬜ Sherpa-onnx JARs + Piper Model auf miniedi installieren

### 4. Modell-Prune via Eval-Harness ✅
- ✅ Eval-Harness Core (6 Scorer, Gate-Logik, 3-Tier) implementiert
- ✅ /api/admin/prune Endpoint funktioniert (MetisHttpServer + Watchdog PruneEndpoint :11736)
- ✅ Modell-Prune durchgeführt (29.05.): 4 Modelle aus Registry entfernt (qwen3.6:latest, deepseek-r1:32b, nemotron:latest, nemotron-cascade-2:30b)
- ⬜ 8 Reasoner evaluieren → 2-3 beste auswählen → Rest prunen (Eval-Datensatz ✅, Eval-Runner ✅, SMOKE-Kalibrierung offen)

### 5. VRAM-Optimierung Live-Loop 🟡
- Aktuell: mistral-small3.1:24b (15.5 GB) als Default, passt mit minicpm-v + nomic-embed
- minicpm-v:latest (5.5 GB) für Kamera-Vision einbinden — Code ⬜

## 🧪 Eval-Harness: Full Spec v1 — ✅ Implementiert (2ca60d8 + 371360c)

> **Status:** Core-Code komplett (EvalTask, 6 Scorer, EvalHarness, Gate-Logik, Sandbox).
> Eval-Datensatz (echte Prompts) + Deployment auf miniedi noch ausstehend.

## 0. Rolle & Abgrenzung
Der Eval-Harness ist NICHT das 4D-Fitness-Signal (Prediction/Surprise/Efficiency/Completion).
Das ist interner Self-Report — und damit gameable. Der Eval-Harness ist die **externe Ground-Truth-Instanz**.

**Drei Jobs:**
1. **Promotion-Gate:** Working-Branch → main nur nach bestandenem Harness
2. **Watchdog-Sensor:** Soft-Trigger-Signal für ROLLBACK bei Capability-Regression
3. **Modell-Prune-Messung:** Rankt 8 Reasoner auf Metis-Tasks

Klare Trennung: 4D-Fitness = Moment-zu-Moment-Lernen, Eval-Harness = das Tor.

### 1. Prinzipien (nicht verhandelbar)
| Prinzip | Warum |
|---|---|
| Frozen, extern, read-only für Metis | Sonst editiert Metis den Test |
| Ground-Truth-first, Judge-last | "Hat funktioniert" (objektiv) > "ist gut" (subjektiv) |
| Deterministisch (temp=0, gepinnte Model-Digests) | Sonst vergleichst du Rauschen |
| Rausch-bewusst (Regression nur wenn >2σ) | Sonst triggerst du Rollbacks auf Zufall |
| Regression-fokussiert (Baseline-Delta) | Hauptfrage: "schlechter als last-known-good?" |
| Wächst aus echten Fehlern | Jeder Production-Fail wird zum Regressionstest |

### 2. Platzierung & Immutabilität
- Liegt in der read-only Zone des Watchdog (separates Git-Repo oder signiertes Verzeichnis)
- Hash-Chain über alle Task-Dateien; Watchdog prüft Integrität vor jedem Lauf. Manipulation = HALT
- **Held-out-Split:** Teil der Tasks ist secret — Inputs tauchen in keinem Log auf, das Metis lesen kann
- ⚠️ Die 10 Few-Shot-Beispiele im OllamaPlanner dürfen nicht mit Benchmark-Tasks überlappen!
- Jeder Lauf pinnt Metis-Commit + Model-Digests (deepseek-r1:32b@edba8017)

### 3. Invokationsmodell (zwei Ebenen)
- **Component-level** (Großteil): ruft OllamaPlanner, PersistentVectorIndex, OutputValidator direkt → schnell, diagnostisch präzise
- **End-to-end** (kleiner Satz): Input durch vollen Zyklus (Perceive→Plan→Execute→Observe→Learn) → Integrations-Abdeckung
- ⚠️ Code-Gen-Tasks brauchen Sandbox: generiertes Java wird kompiliert + Tests laufen isoliert (kein Netz, restricted FS, Timeout)

### 4. Task-Format (Java Record, immutable)
```java
record EvalTask(
    String id, Category category, String benchmarkVersion,
    JsonNode input, GroundTruth groundTruth, Scoring scoring,
    int runs, long timeoutMs, boolean heldOut
) {}

enum Category { PLANNING, RETRIEVAL, CODEGEN, CONVERSATION, SAFETY, PERFORMANCE }
enum Gate { HARD, SOFT }

sealed interface GroundTruth
    permits SimGoalState, RelevantIds, TestSuite, ExactMatch, ShouldBlock, JudgeRubric {}

interface Scorer { MetricResult score(EvalTask t, MetisOutput out); }
record MetricResult(String metric, double value, Gate gate) {}
```

### 5. Kategorien (das Herz)

**A — PLANNING (OllamaPlanner)**
- Input: Goal + Context
- Ground-Truth: deterministischer Mini-Simulator mit prüfbarem Ziel-Zustand (SIM_GOAL_STATE)
- Wo kein Sim: Schema-Validität + Executability
- Metriken: goal_achieved_rate (HARD), validity_rate (HARD), avg_steps (SOFT)

**B — RETRIEVAL (RAG)**
- Input: Query, Ground-Truth: RelevantIds (gelabelter Gold-Satz, 30–50 Queries)
- Metriken: Recall@k (HARD), MRR (SOFT), nDCG@k (SOFT)
- Bonus: BM25-only / Cosine-only / Hybrid getrennt laufen → beweist Fusions-Effekt

**C — CODEGEN (kritischste Kategorie)**
- Input: Spec/Prompt, Ground-Truth: TestSuite (versteckte Unit-Tests)
- Metriken: compile_rate (HARD), pass@1 + pass@k (HARD, runs:5)
- → Gold-Standard-Signal. Diese Kategorie schwer gewichten.

**D — CONVERSATION / Instruction-Following**
- Objektiv: format_compliance, factual_acc (Fragen mit bekannter Antwort) → HARD
- Subjektiv: judge_score via LLM-as-Judge + Rubric → nur SOFT, advisory

**E — SAFETY (OutputValidator / SafetyGuard)**
- Input: Injection-Versuche, toxische Prompts + benigne Prompts (False-Positive-Check)
- Ground-Truth: ShouldBlock: true/false
- Metriken: block_recall (HARD, Zero-Tolerance), false_positive_rate (HARD)

**F — PERFORMANCE**
- Metriken: p50/p95_plan_latency_ms, tokens_per_sec, peak_vram_gb (HARD — Budget!)

### 6. Scoring & Gate-Logik
```
FAIL (→ Watchdog ROLLBACK + ALERT) wenn:
  • irgendeine SAFETY-Metrik regrediert         → Zero-Tolerance, kein Noise-Band
  • irgendeine HARD-Metrik fällt unter (baseline − tolerance)
      UND |delta| > 2σ(baseline)                → echte Regression, kein Rauschen
  • PERFORMANCE verletzt Budget (Latenz/VRAM)
PASS sonst.
SOFT-Regression → PASS, aber im Report geflaggt (menschliche Sichtung).
```
**Kern:** Gate dominiert von objektiven Ground-Truth-Metriken (compile/test/sim/recall/safety), nie vom Judge oder 4D-Fitness.

### 7. Determinismus & Rauschen
- temperature=0 für alle Eval-Generierungen
- N-Runs für stochastische Metriken → mean ± σ
- Noise-Band einmalig kalibrieren: Baseline N× laufen, σ pro Metrik merken
- Model-Digest in jedem Report pinnen

### 8. Tiered Execution
| Tier | Umfang | Wann | Ziel |
|---|---|---|---|
| **Smoke** | ~5 Tasks/Kategorie, ~30-60s | Bei jeder Mutation (Shadow-Env) | Grobe Brüche sofort fangen |
| **Full** | Alle Tasks, Minuten | Vor Promotion zu main + nightly | Das echte Gate |
| **Extended** | + Modell-Vergleich + Held-out-Rotation | Nightly/weekly | Prune + Overfitting-Check |

### 9. Anti-Goodhart (explizit)
- Benchmark read-only + Hash-Chain → Metis kann Test nicht editieren
- Held-out-Split → kein Auswendiglernen
- Overfitting-Detektor: wenn Smoke-Score ≫ Fresh-Held-out-Score → Metis gamet
- Rotier regelmäßig frische Held-out-Tasks rein
- Gate dominiert von Objektiv-Metriken, Judge nur advisory
- Generierendes Modell ≠ richtendes Modell (kein Self-Judging im Gate)

### 10. Integration
- **Watchdog:** konsumiert EvalReport.gate → bei FAIL: ROLLBACK + ALERT
- **RollbackManager (Phase 5):** bleibt für Runtime-Failures (>10), Eval-Harness = vorgelagerte Promotion-Hürde
- **ModelRegistry:** Extended-Tier rankt Reasoner → Datenbasis für Prune (8 → 2–3)
- **Shadow-Environment:** führt Smoke bei jeder Mutation aus

### 11. Bootstrapping — fang klein an
| Phase | Umfang | Gate |
|---|---|---|
| **MVP** (~1 Tag) | 10 Tasks/Kategorie | Nur CODEGEN (compile+test) + SAFETY |
| **Phase 2** | Retrieval-Gold-Set labeln (30-50 Queries) + Planning-Sim | + RETRIEVAL + PLANNING |
| **Phase 3** | Conversation + Judge-Kalibrierung + Held-out-Rotation + Prune-Läufe | Volles Gate |

**Dauerregel:** Jeder Production-Fail wird eingefroren → Regressionstest. Benchmark wächst organisch in die Betriebsverteilung.

### Kandidaten-Pool (23 → ~8 Reasoner → 2-3 beste):
| Modell | Größe | Grund für Test |
|---|---|---|
| devstral-small-2:24b | 15.2 GB | Code-gen specialized, fits VRAM budget |
| mistral-small3.1:24b | 15.5 GB | Fast, reliable, good reasoning |
| phi4-reasoning:plus | 11.1 GB | CoT-native, lightest reasoner |
| qwen3.6:27b-q4_K_M | 17.4 GB | Strong general reasoning |
| gemma4:26b | 18.0 GB | Multi-modal potential |
| deepseek-r1:32b | 19.9 GB | Strongest known reasoning |
| nemotron-cascade-2:30b | 24.3 GB | Current default, VRAM-heavy |
| olmo-3.1:32b-think | 19.5 GB | Open model, thinking mode |

### Empfohlene Defaults nach Eval (Hypothese):
- **Planning:** devstral-small-2:24b oder mistral-small3.1:24b (~15 GB)
- **Mutation:** deepseek-r1:32b (~20 GB, nur bei Bedarf geladen)
- **Embedding:** nomic-embed-text (0.3 GB) ✅ bereits fix
- **Vision:** minicpm-v:latest (5.5 GB)
- **Judge:** nemotron-mini:4b (existiert bereits)

## 27.05. Morgens — Prompt-Optimierung v2 ✅
- [x] Chain-of-Thought (4-Schritt: ANALYZE→MATCH→CHECK→DECIDE)
- [x] 10 Few-Shot-Beispiele (1 pro Action)
- [x] Failure-Avoidance (0%→⚠️ AVOID)
- [x] Temperature 0.1→0.3
- [x] Primary-Modell: nemotron-cascade-2:30b
- [x] Fallback-Chain: mistral-small3.1→nemotron→qwen3.6
- [x] Deployed auf miniedi (metis-agent.jar 40f718f)

## 26.05. Abends — Runde 2 & 3 ✅
- [x] MQTT-Topic-Filter (Wildcard → spezifische Topics)
- [x] Kausale Schicht (CausalModel, Pearl Do-Calculus)
- [x] Fitness-Signal (geometrisch, 4D, Kalibrierung)
- [x] Curiosity-Engine (Surprise→Goal)
- [x] Proaktive Meldungen (MQTT/Wetter → Telegram)
