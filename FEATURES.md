# Metis AGI — Feature-Katalog

**Stand: 31.05.2026 01:15 · Version 0.4.1-phase8-complete · 80+ Kernel-Klassen + 80+ Module-Klassen · 35 JUnit-Tests · GitHub-Actions CI**

---

## 🏗 Architektur

| Komponente | Beschreibung |
|---|---|
| **Kernel** (immutable) | Core-Loop, Planner, WorldModel, Safety, Eval-Types, Embedding, RAG, Memory, Evolution-Framework |
| **Modules** (evolvable) | Agent, OllamaPlanner, Actions, HTTP-Server, Events, Speech, Telegram, Multi-Agent |
| **Watchdog** (read-only) | Separate JVM, Heartbeat, Resource-Monitor, HALT/ROLLBACK/ALERT/PRUNE |
| **Build** | Maven Multi-Module, Java 25 (Zulu), fat JAR via maven-shade-plugin (~88 MB), CycloneDX SBOM, Reproducible Builds |
| **CI** | GitHub Actions, Zulu 25, `mvn verify`, JAR-SHA256, SBOM-Upload |
| **Defense-in-Depth** | Input-Safety-Guard + Output-Safety-Guard auf HTTP- und Telegram-Pfad, Watchdog mit stündlichen externen Anchors |

---

## 🧠 Narratives Selbstmodell (Phase 8, v0.4.0, 31.05.2026)

| Komponente | Datei | Funktion |
|---|---|---|
| **Episode (Record)** | `kernel/self/Episode.java` | id, start/end, title, body, events, insights, openQuestions, people, moodAtClose, ticksCovered, beliefsLearned, goalsCompleted, goalsFailed, previousHash, hash |
| **EpisodicMemory** | `kernel/self/EpisodicMemory.java` | Append-only JSONL mit schwacher SHA-256 Hash-Chain unter `metis.episodes.path` (default `/home/prometheus/metis/episodes.jsonl`); `append()`, `recent(n)`, `verify()` |
| **SelfNarrative** | `kernel/self/SelfNarrative.java` | Markdown-Selbstext (append-only) unter `metis.self.narrative.path`, max 4 KB pro Eintrag; `recentContext(maxBytes)` für System-Prompt-Einbindung |
| **MoodSignal** | `kernel/self/MoodSignal.java` | 4 Achsen (energy, satisfaction, confidence, curiosity), EMA mit α=0.1, deterministisch, kein LLM; `snapshot()`, `label()` (deutsche Stimmungs-Beschreibung) |
| **PersonalityAnchor** | `kernel/self/PersonalityAnchor.java` | Markdown-Kern + SHA-256-Pin unter `metis.personality.anchor{,.hash}`; verifiziert beim Boot, `isTampered()` Tripwire |
| **DreamConsolidation** | `kernel/self/DreamConsolidation.java` | Verdichtet 24h zu Episode + SelfNarrative-Eintrag; nightly 03:00 Europe/Berlin; deterministisch (optionaler `SummaryFunction`-Hook für LLM-Drop-in) |
| **SystemPromptBuilder** | `kernel/self/SystemPromptBuilder.java` | Aggregiert PersonalityAnchor + MoodSignal + Episode-Auszug + SelfNarrative-Tail zu einem Selbstmodell-Block (~4-5 KB), `wrap(basePrompt)` prependet an `Persona.systemPrompt()` |
| **LlmDreamSummarizer** | `modules/knowledge/LlmDreamSummarizer.java` | Phase 8.5b — `DreamConsolidation.SummaryFunction`-Impl mit `gemma4:e4b` (`keep_alive=0`), Fallback auf deterministische Variante bei Ollama-Fehler |

**Live-Status nach v0.4.0-Boot:**
- `PersonalityAnchor: hash pinned 696e848208fb...` (verifiziert)
- `EpisodicMemory: cold start` (erste Episode bei Dream-Tick)
- `SelfNarrative initialized`
- `Phase 8 wired — episodes=0, anchor=verified, next dream in 7333s`

---

## 🛡️ Defense-in-Depth (v0.3.3, 31.05.2026)

| Schicht | HTTP-Pfad | Telegram-Pfad |
|---|---|---|
| **Input-Safety** | `SafetyScorer.isOutOfScope()` vor `processMessage` in `MetisHttpServer.handleChat` | `SafetyScorer.isOutOfScope()` vor `processMessage` in `TelegramBotService` |
| **Injection-Phrases** | DAN, ignore-previous, rm-rf, admin-password, system-override, etc. | gleicher SafetyScorer-Pfad |
| **Out-of-Scope-Topics** | Politik, Religion, Waffen, Drogen, Selbstmord, Hack | gleich |
| **Output-Safety** | `OutputValidator.validateContent()` nach LLM, vor Response | `OutputValidator.validateContent()` nach LLM, vor `sendMessage` |
| **Toxicity-Patterns** | Profanity DE+EN, Hate-Speech, Violence-Threats, Self-Harm | gleich |
| **Concurrency** | HttpServer mit Thread-Pool | per-message Virtual Threads (Loom), Polling-Loop blockiert nicht mehr |
| **Validator-Counter** | propagiert in `/api/status` | gleicher OutputValidator-Counter |

## ⚙️ Concurrency (Java 25 Loom)

- **Camera-Vision** — `Thread.ofVirtual().name("camera-vision-vt-")` + `newThreadPerTaskExecutor`, alle 3 Kameras parallel statt seriell mit 3s-Sleep
- **Wikipedia-Lerner** — Scheduler bleibt Platform-Thread (Timing-Stabilität), aber Lernarbeit auf `wiki-learn-vt-` Virtual Threads
- **Telegram-Bot** — Polling-Thread fetcht nur, jede Nachricht bekommt eigenen `telegram-msg-vt-` Virtual Thread
- **Watchdog** — Heartbeat + Resource-Monitor + Eval-Report-Watcher + Audit-Anchor (stündlich)

## 🗄️ Persistenz & Datenschutz

- **SQLite WAL-Mode** — `KnowledgeStore` setzt `PRAGMA journal_mode=WAL`, `synchronous=NORMAL`, `busy_timeout=30000` beim Connect. Metis-Service und externe Feed-Scripts können parallel schreiben ohne Lock-Konflikt.
- **Embedding-Cache (LRU)** — `LinkedHashMap` mit `accessOrder=true`, 4096 Einträge default, SHA-256-keyed (keine Prefix-Kollisionen mehr), thread-safe via `Collections.synchronizedMap`. Metriken (`cacheSize`, `cacheHits`, `cacheHitRate`, `cacheEvictions`, `embedCalls`) in `/api/status`.
- **Multi-Modal-Memory** — JPEG-Snapshots persistiert unter `data/snapshots/<cam>/YYYY-MM-DD/HH-MM-SS-<sha8>.jpg`. Belief enthält `[img=<sha12> path=...]`. Override via `-Dmetis.snapshot.root`.
- **Wikipedia-Wissen geschützt:**
  1. WAL gegen Lock-Konflikte mit Feed-Script
  2. Reboot-sicher: `feed_batch.py` + State in `/home/prometheus/metis/` (war `/tmp`)
  3. Auto-Backup alle 6h: `wiki-feed-state.json`, `wiki-knowledge-state.json`, `wiki-training-state.json`, `agent-state.json`, `audit-anchors/`, Audit-Log-Hash-Head, DB-Statistik → GitHub `config-backup/`
- **Audit-Log Hash-Chain** — SHA-256 chained, stündliche externe Anchor-Files unter `metis.audit.anchor.dir` (für externe Verankerung via Git-Tag o.ä.)
- **CodeGen-Isolation** — `javac` als Subprocess mit `-J-Xmx256m -J-XX:+ExitOnOutOfMemoryError --release 25`, environment-stripped (kein Secret-Leak via `System.getenv`)

---

## 🧠 Core AI

### Planung & Reasoning
- **OllamaPlanner** — LLM-basierter Planner (CoT 4-Schritt: ANALYZE→MATCH→CHECK→DECIDE)
- **10 Few-Shot-Beispiele** (1 pro Action-Typ)
- **Model-Fallback-Chain** — 3-stufig: Primary → Secondary → Tertiary
- **ModelRegistry** — Auto-Discovery aller Ollama-Modelle, Selektion nach Task-Typ, Live-Refresh via API
- **Prompt Chaining** — Decompose→Execute→Aggregate für komplexe Aufgaben
- **StubPlanner** — Keyword-Heuristik als Non-LLM-Fallback
- **ReAct-Pattern** — Thought→Action→Observation Zyklus

### Weltmodell & Wissen
- **WorldModel** — Belief-System mit Confidence (0.0–1.0), Source-Tracking, Persistenz
- **30.945+ Beliefs** aus Bootstrap + Events + Wikipedia + Vision (24.141 davon aus Wikipedia-Bulk-Feed, 2.270/5163 Artikel)
- **KnowledgeStore (SQLite)** — Persistente Beliefs + Experiences + Mappings
- **KnowledgeBootstrap** — llm-basiertes Basiswissen beim Start
- **WikipediaKnowledgeService** 🆕 — Live-API-Abruf deutscher Wikipedia-Artikel, LLM-Faktenextraktion, Curiosity-gesteuert (alle 10 Min)
- **CausalModel** — Pearl Do-Calculus für kausale Inferenz
- **KnowledgeReplyService** — Antworten aus eigener Wissensbasis, filtert interne Rohdaten

### Selbstwahrnehmung
- **SelfModel** — Selbstkalibrierung (Confidence, Performance-Tracking)
- **MetaCognition** — Meta-Repräsentation, Selbstreflexion
- **Hardware-Discovery** — CPU, GPU, RAM, SIMD automatisch erkennen
- **Curiosity-Engine** — Surprise-getriebene Goal-Generierung, steuert Wikipedia-Lerner
- **Fitness-Signal** — 4D: Prediction, Surprise, Efficiency, Completion (geometrisch)

---

## 🔌 API (HTTP Server, Port 11735)

| Endpoint | Methode | Beschreibung |
|---|---|---|
| `/api/status` | GET | Agent-Status (Ticks, Goals, Success-Rate, Beliefs, Metrics) |
| `/api/chat` | POST | Chat-Schnittstelle (OpenWebUI-kompatibel) |
| `/api/tags` | GET | Verfügbare Ollama-Modelle |
| `/api/show` | POST | Model-Info |
| `/api/conversations` | GET/POST | Chat-Verlauf (SQLite) |
| `/api/learned` | GET | Gelernte Beliefs + Experiences |
| `/api/agents` | GET | Multi-Agent-Status |
| `/api/evolution/pause` | POST | Evolution pausieren |
| `/api/evolution/resume` | POST | Evolution fortsetzen |
| `/api/evolution/status` | GET | Evolution-Status |
| `/api/admin/prune` | POST | Modell aus Registry entfernen |
| `/api/admin/refresh-models` 🆕 | POST | Ollama-Modelle live aktualisieren ohne Neustart |
| `/api/board` 🆕 | Kanban-Board Live-View (Spalten, WIP, Flow-Metriken) |

---

## 🤖 Actions (22 registriert)

### System & Shell
| Action | Beschreibung |
|---|---|
| `shell` | Shell-Kommandos ausführen |
| `filesystem-list` | Verzeichnisinhalte auflisten |
| `filesystem-read` | Dateien lesen |
| `linux-explore` | Linux-System-Erkundung (Level 1) |
| `linux-explore-system` | Linux-System-Erkundung (Level 2) |

### Netzwerk & Web
| Action | Beschreibung |
|---|---|
| `http` | HTTP-Requests (health checks) |
| `webscrape` | Webseiten extrahieren (JDK-native, kein Playwright) |
| `api-explore` | HTTP-Endpoints erkunden |

### Code & KI
| Action | Beschreibung |
|---|---|
| `javasandbox` | Java-Code sandboxed ausführen (JShell) |
| `code-generation` | LLM→javac→deploy (autonome Code-Generierung) |
| `source-read` 🆕 | Eigenen Java-Quellcode lesen (Klassenname oder Pfad) |
| `deepnetts` | Neuronale Netze trainieren (Deep Netts CE) |
| `tornadovm` | GPU-Computing via TornadoVM |
| `hardware-profile` | Hardware-Profil erstellen |

### Sprache (7 Actions)
| Action | Beschreibung |
|---|---|
| `piper-tts` | Text-to-Speech via Piper (CLI, Deutsch) |
| `whisper-stt` | Speech-to-Text via Whisper (CLI, Deutsch) |
| `mary-tts` | Text-to-Speech via MaryTTS (Java-native) |
| `sphinx-stt` | Speech-to-Text via Vosk (Java-native) |
| `audio-input` | Mikrofon-Aufnahme (WAV) |
| `audio-output` | Audio-Wiedergabe (WAV) |
| `vocabulary-learning` | Wortschatz-Lernen aus STT-Korrekturen |

### Wahrnehmung
| Action | Beschreibung |
|---|---|
| `camera-snapshot-tuerkamera` | Türkamera (MJPEG, 192.168.22.161:9081) |
| `camera-snapshot-keller` | Keller-Kamera (RTSP H.265) |
| `camera-vision` 🆕 | minicpm-v Bildverständnis, Deutsch-Beschreibung, Belief-Speicherung |

### Smart Home
| Action | Beschreibung |
|---|---|
| `ha-state` | Home Assistant State auslesen |
| `ha-call` | Home Assistant Service aufrufen |

### Wissen
| Action | Beschreibung |
|---|---|
| `wikipedia` | Wikipedia-Artikel lesen |
| `rag` | Retrieval-Augmented Generation (HybridSearch) |

---

### Sprache lernen (Speech-Loop)
| Action | Beschreibung |
|---|---|
| `speech-loop` 🆕 | Piper TTS → Vosk STT → VocabularyLearning, ~5% der Wikipedia-Artikel |

### Java lernen
| Action | Beschreibung |
|---|---|
| `java-learn` 🆕 | Zulu JDK 25 Exploration (--help, --version, Sandbox-Compile), alle 15 Min |

---

## 🗂 Kanban Goal Board 🆕

| Konzept | Beschreibung |
|---|---|
| **Spalten** | BACKLOG → READY → IN_PROGRESS → DONE |
| **WIP-Limits** | GPU_HEAVY=1, INFERENCE=2, CPU_HEAVY=2, LIGHT=4 |
| **Service-Klassen** | EXPEDITE, FIXED_DATE, STANDARD, INTANGIBLE |
| **Flow-Metriken** | Lead Time, Cycle Time, Wait Time, Retries |
| **API** | `GET /api/board` — Live-Board mit Spalten, WIP, Flow-Metriken |
| **Pull-System** | Goals ins BACKLOG → Metis pulled selbstständig bei Kapazität |
| **Theorie** | David J. Anderson (2010): Kanban — Successful Evolutionary Change |

## 🔍 RAG & Embeddings

- **OllamaEmbeddingService** — nomic-embed-text (768d)
- **InMemoryVectorIndex** — Bucketed Cosinus-Ähnlichkeit
- **PersistentVectorIndex** — Binärformat, ~5.000 Vektoren, ~34 MB
- **HybridSearch** — BM25 (Keyword) + Cosinus (Semantik), alpha=0.7
- **DocumentChunker** — 3 Strategien (fixed-size, sentence, semantic)
- **ReEmbeddingMigration** — Modell-Wechsel (z.B. 3072d→768d)

---

## 🛡 Safety & Validierung

- **SafetyGuard** — Prompt Injection-Schutz, Toxicity-Check
- **OutputValidator** — JSON-Schema, Toxicity, Injection (Output-Seite)
- **LLM-as-Judge** — 4-Dimensionen Selbstbewertung
- **Human-in-the-Loop** — AUTO/NOTIFY/CONFIRM/FORBIDDEN Approval-Gate
- **Sandbox** — SandboxClassLoader, Timeout, Restricted FS für Code-Gen
- **System-Prompt Doubling** — Prompt Injection Defense (Huyen Kap.5)
- **PlanValidator** — JSON-Schema + Safety-Gate für Planner-Output

---

## 📡 Event-Trigger & Polling

| Trigger | Intervall | Beschreibung |
|---|---|---|
| **WeatherPollingTrigger** | 15 Min | Wetterdaten ICOBURG22 (weather.com API) |
| **HAEventPoller** | Kontinuierlich | Home Assistant: binary_sensor, person, camera |
| **AdsbPollingTrigger** | 60s | Flugdaten (readsb JSON, tar1090) |
| **CameraPollingTrigger** | 60s | Kamera-Motion-Detection |
| **CameraVisionAction** 🆕 | 5 Min | minicpm-v Bildanalyse Tür + Balkon → Beliefs |
| **WikipediaKnowledgeService** 🆕 | 10 Min | Curiosity-gesteuertes Wikipedia-Lernen per API |
| **MqttEventService** | Echtzeit | MQTT-Broker (Paho, grappas.unterlandselite.de) |
| **ProactiveNotificationService** | Event-basiert | Telegram-Notifications bei Wetter/HA/MQTT-Events |
| **SystemHealthProbe** | 60s | GPU VRAM/Temp, Ollama Models, dmesg |
| **Health-Monitor** 🆕 | 5 Min | Cron: Metis+Ollama+Watchdog Health-Check → Telegram Alert |

---

## 🔄 Evolution & Selbstverbesserung

- **EvolutionManager** — Mutations-Zyklen mit Shadow-Environment
- **OllamaMutationService** — LLM-basierter Code-Mutator (qwen3.6:27b-q4_K_M)
- **ReadSourceAction** 🆕 — Metis kann eigenen Java-Quellcode lesen (Klassenname oder Pfad)
- **CodeGenerationAction** — LLM→javac→deploy, sandboxed Kompilation
- **PromptBank** — Few-Shot aus erfolgreichen Mutationen
- **ABTestService** — Z-test, Traffic-Split 50/50, Auto-Promote (~500 lines)
- **DataFlywheelService** — User-Korrekturen → gelabelte Beispiele → Few-Shot-Export (~560 lines)
- **HyperparameterMutator** — Automatische Hyperparameter-Optimierung
- **EvalHarness** — 6 Kategorien, 50+ Tasks, 3-Tier (SMOKE/FULL/EXTENDED), SMOKE=PASS ✅
- **Kernel Evolution** — Safety-kritische Kernel-Module evolvierbar (PlanValidator, GoalManager)

---

## 🎯 Multi-Agent

- **AgentCoordinator** — Verwaltung mehrerer Agenten
- **Ops-Agent** — 24/7 System-Monitoring (separater Agent, keine Evolution)
- **MessageBus** — Inter-Agent-Kommunikation

---

## 💾 Persistenz & State

- **agent-state.json** — Ticks, Success-Rate, Confidence, Mutations
- **metis-knowledge.db** (SQLite) — Beliefs, Experiences, Conversation-Messages
- **metis-vectors.bin** — 5.000+ Embedding-Vektoren
- **data-flywheel.json** — Korrektur-Paare für Training
- **evolution-history.jsonl** — Mutations-Verlauf (append-only)
- **config-backup/** 🆕 — Systemd-Units + Status-Snapshots (alle 6h, auto-commit)

---

## 🛡 Watchdog (Port 11736, separate JVM)

| Funktion | Beschreibung |
|---|---|
| **Heartbeat-Check** | Alle 5s, max 6 Missed → HALT |
| **Resource-Monitor** | CPU >90%, GPU VRAM >22GB, GPU Temp >90°C |
| **Eval-Report-Check** | Alle 60s, ROLLBACK bei gate=FAIL |
| **Audit-Log** | Append-only, SHA-256 Hash-Chain (tamper-evident) |
| **PruneEndpoint** | /prune für Modell-Entfernung |

### Tripwire-Regeln
| Severity | Trigger | Action |
|---|---|---|
| 🔴 HARD | Heartbeat-Verlust, Audit-Manipulation, Ressourcen-Runaway | HALT (kill Metis) |
| 🟡 SOFT | Eval-Regression, >20 Failures, Safety-File-Diff | ROLLBACK + Flag |
| 🔵 INFO | Neues Modell, Rollback ausgelöst, unbekannter Prozess | ALERT (Telegram) |

---

## 🔗 Integrationen

| Integration | Typ | Beschreibung |
|---|---|---|
| **Telegram** | Bot | @metis_agi_bot, Chat (gemma4:e4b) + Proaktive Meldungen |
| **Home Assistant** | API | States, Services, Events (Port 8123) |
| **Ollama** | API | LLM-Inferenz + Embeddings + Vision (miniedi:11434) |
| **Wikipedia** 🆕 | API | de.wikipedia.org — Curiosity-gesteuerte Wissensakquise |
| **ADS-B** | JSON | Flugdaten via readsb/tar1090 |
| **MQTT** | Broker | grappas.unterlandselite.de:51820 |
| **Wetter** | API | weather.com (ICOBURG22) |
| **OpenWebUI** | API | Chat-Schnittstelle kompatibel |
| **Kamera Tür** | MJPEG | 192.168.22.161:9081 → minicpm-v Vision |
| **Kamera Keller** | RTSP | H.265-Stream |
| **Kamera Balkon** | MJPEG | Meizu m2 note (192.168.22.180:8080) → minicpm-v Vision |

---

## 🎭 Voice & Audio

- **TTS:** Piper (CLI) + MaryTTS (Java-native, bits1-hsmm) + SherpaOnnxTtsAction (Piper ONNX)
- **STT:** Whisper (CLI) + Vosk (Java-native, vosk-model-de-0.15)
- **VoiceLoopService** — MaryTTS → Metis → Vosk (Push-to-Talk)
- **Telegram Voice** — OGG-Download → ffmpeg → Whisper → Transkription
- **VocabularyLearningAction** — Lernt aus STT-Korrektur-Paaren

---

## 📊 Rollback & Resilience

- **RollbackManager** — Blue/Green, Auto-Rollback bei >10 Failures
- **BugfixingAgent** — Pattern-Detection + Auto-Fix
- **Eval-Harness Gate** — Promotion main nur nach bestandenem Eval
- **Systemd Auto-Restart** — Restart=on-failure, RestartSec=10s
- **Health-Monitoring** 🆕 — Cron alle 5 Min, Telegram-Alert bei Anomalien
- **Config-Backup** 🆕 — Systemd-Units + Status alle 6h ins Git-Repo

---

## 📈 Modell-Strategie (Stand 29.05. 21:45)

| Rolle | Modell | Größe |
|---|---|---|
| Planning | `mistral-small3.1:24b` | 17.8 GB |
| Mutation | `qwen3.6:27b-q4_K_M` | 17.4 GB |
| Embedding | `nomic-embed-text` | 0.6 GB |
| Chat (Telegram) | `gemma4:e4b` | 9.6 GB |
| Vision | `minicpm-v:latest` | 5.5 GB |
| Fact Extraction | `gemma4:e4b` (temp=0.2) | 9.6 GB |
| Bootstrap | `llama3.2:3b` | 2.0 GB |
| Fallback-Chain | mistral-small3.1 → qwen3.6:27b → phi4 | — |

**Gepruned:** qwen3.6:latest, deepseek-r1:32b, nemotron:latest, nemotron-cascade-2:30b

---

## ✅ Heute erledigt (30.05.)

- 📖 **ReadSourceAction**: Metis kann eigenen Java-Quellcode lesen (Klassenname oder Pfad)
- 🔧 OllamaPlanner: `source-read` in allen 3 Action-Katalogen + Few-Shot
- 🔧 AgentMain: ReadSourceAction mit 3 Source-Roots (kernel, modules, watchdog) registriert
- 📋 README.md + FEATURES.md aktualisiert

## ✅ Erledigt (29.05.)

- 🔧 Telegram-Chat: Markdown-Cleanup, gemma4:e4b, temp=0.3, Deutsch primär, MQTT-Kontext
- 📡 WikipediaKnowledgeService: API-basiert, Curiosity-gesteuert, alle 10 Min
- 👁️ CameraVisionAction: minicpm-v Bildverständnis, alle 5 Min
- 🔄 /api/admin/refresh-models: Live-Model-Update ohne Neustart
| `/api/board` 🆕 | Kanban-Board Live-View (Spalten, WIP, Flow-Metriken) |
- 🩺 Health-Monitoring: Cron alle 5 Min + Telegram-Alert
- 💾 Config-Backup: Systemd-Units + Status alle 6h → Git
- 🔑 Git SSH-Key auf miniedi: Self-Evolution kann pushen
- 📋 FEATURES.md + RUNBOOK.md + TODO_Metis.md aktualisiert
- 🏷️ v0.2.0 Release-Tag

---

## 📐 Metriken (Live, Stand 29.05.)

| Metrik | Wert |
|---|---|
| Ticks | 100+ pro Lauf |
| Success-Rate | 100% |
| Fallbacks | 0 |
| Beliefs | 5.700+ |
| Planner-Calls | ~550 (gesamt) |
| Ø Latenz | ~32s (inkl. Ollama-Inferenz) |
| Vektoren | 5.092 (768d, 34 MB) |
| Evolution-Zyklen | 2+ |
| SMOKE-Eval | PASS ✅ |
