# TODO Metis — Stand 28.05.2026 23:15 (Claude-Review integriert)

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
| Selbstkritik/Self-Reflection | 🟡 Meta-Cognition vorhanden, keine explizite Self-Critique |
| Kontext-Management | 🟡 SQLite-Sessions, kein "Lost in the Middle"-Handling |
| Prompt Injection-Schutz | 🟡 Input-Validierung basic, kein Sandboxing |

### GenerativeKI-Systeme-Entwickeln (Huyen, O'Reilly 2025) vs Metis
| Huyen-Prinzip | Metis-Status |
|---|---|
| System > Modell | ✅ Architektur aus Kernel+Modules+HTTP-API |
| Rigorose Evaluation | 🟡 Planungs-Metriken + Fitness, kein A/B-Testing |
| Einfach→Optimieren (Prompting→RAG→Fine-Tuning) | ✅ Prompting zuerst, RAG via Beliefs, Fine-Tuning via Evolution |
| Daten sind der Engpass | 🟡 VocabularyLearningAction, kein Data Flywheel |
| Kosten von Anfang an managen | ✅ Token-Tracking, Modell-Fallback, lokale Inferenz |
| Halluzinationen systemisch | 🟡 Confidence-Threshold, kein Factual Grounding |
| Menschen in der Schleife | 🟡 Approval-Gate existiert, aber Read/Write nicht differenziert |
| Foundation Models ≠ Silver Bullet | ✅ StubPlanner + Keyword-Heuristik als Non-LLM-Fallback |
| ReAct-Pattern | ✅ Thought→Action→Observation im OllamaPlanner |
| RAG mit Vektor-DB | ❌ Nur Belief-basiert, keine Embedding/Vector-Search |
| LLM-as-Judge | ❌ Keine LLM-basierte Selbstbewertung |
| Guardrails (Input+Output) | 🟡 SafetyGate + Action-Whitelist, kein Output-Validator |
| Inferenz-Optimierung | 🟡 Prompt-Caching (keep_alive), keine Quantisierung/Distillation |

### 🔴 Gap-Analyse — Was fehlt für Produktionsreife? (Stand 28.05. 22:00)
1. **RAG mit Vector DB** ✅ — Phase 5: RAG Advanced (OllamaEmbedding + HybridSearch BM25+Cosinus + PersistentVectorIndex)
2. **LLM-as-Judge** ✅ — Selbstbewertung 4-Dimensionen (relevance, coherence, actionability, safety), nemotron-mini:4b (0116022)
3. **Output-Validierung** ✅ — JSON-Schema, Toxicity, Injection-Check (ae66cdd)
4. **A/B-Testing** — Prompt-Varianten in Produktion vergleichen
5. **Lost-in-the-Middle** ✅ — Phase 6: Primacy/Recency Context Windowing (8426162)
6. **Human-in-the-Loop verfeinern** 🔴 — Read/Write-Differenzierung im Approval-Gate → NÄCHSTER PUNKT
7. **Data Flywheel** — User-Korrekturen → automatisch Trainingsdaten verbessern

### 🟢 Phase 6 — Was kommt als Nächstes? (Stand 28.05. 22:00)
- **Priorität 1:** Lost-in-the-Middle ✅ — Primacy/Recency Context Windowing (8426162)
- **Priorität 2:** Output-Validierung ✅ — JSON-Schema + Toxicity + Injection (ae66cdd)
- **Priorität 3:** LLM-as-Judge ✅ — Selbstbewertung 4-Dimensionen, Safety Gates (0116022)
- **Priorität 4:** Human-in-the-Loop 🔴 — Read/Write-Differenzierung im Approval-Gate

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

## Phase 4: Sprachausgabe 🟡 90%
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
- [ ] Live-Test mit Georg (Mikrofon → Metis → Kopfhörer)

## Phase 5: Eigenständigkeit ✅ 100%
- [x] Blue/Green Rollback ✅ (RollbackManager, Auto-Rollback bei >10 failures)
- [x] Autonomous Bugfixing ✅ (BugfixingAgent, Pattern-Detection, Auto-Fix)
- [x] Prompt Chaining ✅ (PromptChainingService, Decompose→Execute→Aggregate, 3bbcdf2)
- [x] Selbstständige Code-Generierung ✅ (CodeGenerationAction, LLM→javac→deploy, 5423a08)
- [x] JNI/Panama-Bridge für GPU ✅ (OpenCLNative via Panama FFM, GpuTensor Zero-Copy, OpenCLBridge High-Level, 8edac15 + 3bffa1c)
- [x] RAG Foundation ✅ (OllamaEmbeddingService + InMemoryVectorIndex + WorldModel.query semantic search)
- [x] Multi-Agent-Koordination ✅
- [x] RAG Advanced ✅ (DocumentChunker 3 Strategien, PersistentVectorIndex binary, HybridSearch BM25+Cosinus, WorldModel-Integration via enableRagAdvanced())

## Phase 6: Produktionsreife 🟡 67% (28.05.)

### Gap-Analyse → Implementierung:
- [x] **Lost-in-the-Middle** ✅ (8426162) — Primacy/Recency Context Windowing (Huyen Kap.6)
- [x] **OutputValidator** ✅ (ae66cdd) — JSON-Schema + Toxicity + Injection-Check (Huyen Kap.10)
- [x] **LLM-as-Judge** ✅ (0116022) — Selbstbewertung 4-Dimensionen, Safety Gates (Huyen Kap.3)
- [x] **Human-in-the-Loop** ✅ (b40e965) — Vierstufiges Approval-Gate: AUTO/NOTIFY/CONFIRM/FORBIDDEN
- [ ] A/B-Testing — Prompt-Varianten in Produktion vergleichen
- [ ] Data Flywheel — User-Korrekturen → Trainingsdaten
- [ ] Embedding-Migration (llama3.2:3b 3072d → nomic-embed-text 768d) — Re-Embedding Korpus
- [ ] Modell-Prune via Eval-Harness (8 Reasoner → 2-3 beste)

## Phase 7: Sicherheits-Watchdog 🆕 0% (Claude-Review 28.05.)

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

### Bekannte Lücken (vs bestehendem RollbackManager):
- RollbackManager ist IN-PROCESS (kann von Metis editiert werden)
- Watchdog ist EXTERN — physisch getrennt, kein Zugriff
- Eval-Harness fehlt komplett (kein systematisches A/B-Testing)
- Audit-Log mit Hash-Chain fehlt (Manipulationsschutz)

## 🔧 Claude-Review 28.05. — Offene Punkte aus claude_antwort_2.txt

### 1. Watchdog (siehe Phase 7) 🆕
→ Externer Prozess, Git-gated Mutationen, Eval-Harness read-only

### 2. Embedding-Dimension-Mismatch 🔴
- Aktuell: OllamaEmbeddingService hartkodiert `llama3.2:3b` → 3072d
- nomic-embed-text (768d) ist installiert, besser für Embeddings
- ModelRegistry hat nomic-embed in EMBEDDING_FAMILIES aber auf Prio 3 (hinter llama3.2)
- ⚠️ Dimension-Change: 3072d → 768d → **Korpus muss neu embedded werden!**
- TODO: ModelRegistry Prio fixen, OllamaEmbeddingService auf Registry umstellen

### 3. TTS: ONNX Runtime Java statt MaryTTS 🟡
- MaryTTS HSMM klingt roboterhaft (bits1-hsmm)
- Option A: Piper (de_DE-thorsten) via ONNX Runtime Java
- Option B: sherpa-onnx (k2-fsa) — native Java-Bindings, VITS/Piper/Kokoro
- sherpa-onnx macht auch ASR → könnte Vosk ersetzen
- Evaluation nötig: Latenz, Qualität, Java-Integration

### 4. Modell-Prune via Eval-Harness 🟡
- Aktuell 23 Modelle auf miniedi, davon ~8 Reasoner
- Claude: 2-3 beste Reasoner per Eval bestimmen, Rest prunen
- VRAM-Strategie: 15-GB-Reasoner + minicpm-v (5.5) + nomic-embed (0.3) ≈ 21 GB
  → Passt in 24 GB, keine Swaps im Live-Loop!
- devstral-small-2:24b (15.2 GB) als Code-Gen-Kandidat testen

### 5. VRAM-Optimierung Live-Loop 🟡
- Aktuell: nemotron-cascade-2:30b (24.3 GB) fillt Karte komplett
- Jeder Vision-/Embed-Call zwingt Swap → Latenz-Spikes
- Empfehlung: 15-GB-Reasoner als Default für Multi-Modell-Setups
- minicpm-v:latest (5.5 GB) für Kamera-Vision einbinden

## 🧪 Eval-Harness: Full Spec v1 (Claude-Review 28.05., claude_antwort_3.txt)

### 0. Rolle & Abgrenzung
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
