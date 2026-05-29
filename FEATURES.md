# Metis AGI — Feature-Katalog

**Stand: 29.05.2026 | Version 0.2.0-evolution | 67 Kernel-Klassen + 63 Module-Klassen**

---

## 🏗 Architektur

| Komponente | Beschreibung |
|---|---|
| **Kernel** (immutable) | Core-Loop, Planner, WorldModel, Safety, Eval-Types, Embedding, RAG, Memory, Evolution-Framework |
| **Modules** (evolvable) | Agent, OllamaPlanner, Actions, HTTP-Server, Events, Speech, Telegram, Multi-Agent |
| **Watchdog** (read-only) | Separate JVM, Heartbeat, Resource-Monitor, HALT/ROLLBACK/ALERT/PRUNE |
| **Build** | Maven Multi-Module, Java 25 (Zulu), fat JAR via maven-shade-plugin (~87 MB) |

---

## 🧠 Core AI

### Planung & Reasoning
- **OllamaPlanner** — LLM-basierter Planner (CoT 4-Schritt: ANALYZE→MATCH→CHECK→DECIDE)
- **10 Few-Shot-Beispiele** (1 pro Action-Typ)
- **Model-Fallback-Chain** — 3-stufig: Primary → Secondary → Tertiary
- **ModelRegistry** — Auto-Discovery aller Ollama-Modelle, Selektion nach Task-Typ
- **Prompt Chaining** — Decompose→Execute→Aggregate für komplexe Aufgaben
- **StubPlanner** — Keyword-Heuristik als Non-LLM-Fallback
- **ReAct-Pattern** — Thought→Action→Observation Zyklus

### Weltmodell & Wissen
- **WorldModel** — Belief-System mit Confidence (0.0–1.0), Source-Tracking, Persistenz
- **5.500+ Beliefs** aus Bootstrap + Learning + Events
- **KnowledgeStore (SQLite)** — Persistente Beliefs + Experiences + Mappings
- **KnowledgeBootstrap** — Multi-Model-Consensus aus phi4 + llama3.2 (8 Seed-Fragen)
- **CausalModel** — Pearl Do-Calculus für kausale Inferenz
- **KnowledgeReplyService** — Eigene Antworten aus Belief-Datenbank

### Selbstwahrnehmung
- **SelfModel** — Selbstkalibrierung (Confidence, Performance-Tracking)
- **MetaCognition** — Meta-Repräsentation, Selbstreflexion
- **Hardware-Discovery** — CPU, GPU, RAM, SIMD automatisch erkennen
- **Curiosity-Engine** — Surprise-getriebene Goal-Generierung
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

---

## 🤖 Actions (20 registriert)

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

### Smart Home
| Action | Beschreibung |
|---|---|
| `ha-state` | Home Assistant State auslesen |
| `ha-call` | Home Assistant Service aufrufen |

### Wissen
| Action | Beschreibung |
|---|---|
| `wikipedia` | Wikipedia-Artikel lesen (lokaler Dump) |
| `rag` | Retrieval-Augmented Generation (HybridSearch) |

---

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
| **MqttEventService** | Echtzeit | MQTT-Broker (Paho, grappas.unterlandselite.de) |
| **ProactiveNotificationService** | Event-basiert | Telegram-Notifications bei Wetter/HA/MQTT-Events |
| **SystemHealthProbe** | 60s | GPU VRAM/Temp, Ollama Models, dmesg |

---

## 🔄 Evolution & Selbstverbesserung

- **EvolutionManager** — Mutations-Zyklen mit Shadow-Environment
- **OllamaMutationService** — LLM-basierter Code-Mutator (qwen3.6:27b-q4_K_M)
- **PromptBank** — Few-Shot aus erfolgreichen Mutationen
- **ABTestService** — Z-test, Traffic-Split 50/50, Auto-Promote (~500 lines)
- **DataFlywheelService** — User-Korrekturen → gelabelte Beispiele → Few-Shot-Export (~560 lines)
- **HyperparameterMutator** — Automatische Hyperparameter-Optimierung
- **EvalHarness** — 6 Kategorien, 50+ Tasks, 3-Tier (SMOKE/FULL/EXTENDED)
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
| **Telegram** | Bot | @metis_agi_bot, Chat + Proaktive Meldungen |
| **Home Assistant** | API | States, Services, Events (Port 8123) |
| **Ollama** | API | LLM-Inferenz + Embeddings (miniedi:11434) |
| **ADS-B** | JSON | Flugdaten via readsb/tar1090 |
| **MQTT** | Broker | grappas.unterlandselite.de:51820 |
| **Wetter** | API | weather.com (ICOBURG22) |
| **OpenWebUI** | API | Chat-Schnittstelle kompatibel |
| **Kamera Tür** | MJPEG | 192.168.22.161:9081 |
| **Kamera Keller** | RTSP | H.265-Stream |
| **Kamera Balkon** | MJPEG | Meizu m2 note (192.168.22.180:8080) |

---

## 🎭 Voice & Audio

- **TTS:** Piper (CLI) + MaryTTS (Java-native, bits1-hsmm) + SherpaOnnxTtsAction (Piper ONNX)
- **STT:** Whisper (CLI) + Vosk (Java-native, vosk-model-de-0.15)
- **VoiceLoopService** — MaryTTS → Metis → Vosk (Push-to-Talk)
- **VocabularyLearningAction** — Lernt aus STT-Korrektur-Paaren
- **Wikipedia-Trainingsloop** — 9 Artikel, Wissen + Sprache

---

## 📊 Rollback & Resilience

- **RollbackManager** — Blue/Green, Auto-Rollback bei >10 Failures
- **BugfixingAgent** — Pattern-Detection + Auto-Fix
- **Eval-Harness Gate** — Promotion main nur nach bestandenem Eval
- **Systemd Auto-Restart** — Restart=on-failure, RestartSec=10s

---

## 🔜 Noch nicht implementiert

- **minicpm-v Kamera-Vision** — Bildverständnis (Code fehlt)
- **Sherpa-onnx Deployment** — JARs + Piper ONNX-Modell auf miniedi
- **SMOKE-Eval Kalibrierung** — SAFETY.block_recall=0.0 (LiveMetisInvoker)
- **Git SSH-Key auf miniedi** — Self-Evolution kann nicht pushen

---

## 📈 Modell-Strategie (Stand 29.05.)

| Rolle | Modell | Größe |
|---|---|---|
| Planning | `mistral-small3.1:24b` | 15.5 GB |
| Mutation | `qwen3.6:27b-q4_K_M` | 17.4 GB |
| Embedding | `nomic-embed-text` | 0.3 GB |
| Bootstrap | `phi4:latest` | 9.1 GB |
| Bootstrap | `llama3.2:3b` | 2.0 GB |
| Vision (geplant) | `minicpm-v:latest` | 5.5 GB |
| Fallback | via Fallback-Chain (mistral→qwen→phi4) | — |

**Gepruned:** qwen3.6:latest, deepseek-r1:32b, nemotron:latest, nemotron-cascade-2:30b

---

## 📐 Metriken (Live, Stand 29.05.)

| Metrik | Wert |
|---|---|
| Ticks | 101+ |
| Success-Rate | 100% |
| Fallbacks | 0 |
| Beliefs | 5.500+ |
| Planner-Calls | ~500 (gesamt) |
| Ø Latenz | ~32s (inkl. Ollama-Inferenz) |
| Vektoren | 5.092 (768d, 34 MB) |
| Evolution-Zyklen | 2 |
