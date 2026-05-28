# 🧠 AGI EDI — Roadmap

**Ziel:** EDI-ähnliche KI (Mass Effect 3) — eigenständig, per Sprache und Text ansprechbar,
mit eigenem Wissen, Persönlichkeit und der Fähigkeit, sich selbst zu verbessern.

**Stand: 28.05.2026 (35 Commits, Claude-Review integriert)**

---

## Fortschritt gesamt: ~90%

```
Phase 1 ████████████████████ 100%  Zuverlässiger Kern
Phase 2 ████████████████████ 100%  Konversation + Events
Ph 2.5  ████████████████████ 100%  Prompt-Caching + Latenz-Tracking
Phase 3 ████████████████████ 100%  Wahrnehmung (HA, ADS-B, Kameras)
Phase 4 ████████████████████  90%  Sprachausgabe (Java Voice Loop ✅, Live-Test 🔒)
Phase 5 ████████████████████ 100%  Eigenständigkeit (RAG Advanced, Panama, Code-Gen)
Phase 6 █████████████░░░░░░░  67%  Produktionsreife (Guardrails, Eval-Harness)
Phase 7 ░░░░░░░░░░░░░░░░░░░░   0%  Watchdog (externer Sicherheitsprozess)
```

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

## Phase 3: Wahrnehmung ✅ 100%

- Home Assistant states/services API (read + write)
- ADS-B Flugdaten (readsb JSON → Beliefs + Goals, 60s Polling)
- Kamera-Integration: Türkamera (MJPEG 1080p) + Keller (RTSP H.265)
- CameraSnapshotAction + CameraPollingTrigger (Motion-Detection)

## Phase 4: Sprachausgabe 🟡 90%

| Feature | Status |
|---------|--------|
| Piper TTS + Whisper STT (CLI, Deutsch) | ✅ |
| MaryTTS (Java-native, bits1-hsmm, fat JAR) | ✅ |
| Vosk STT (Java-native, vosk-model-de-0.15) | ✅ |
| Audio-Input/Output (Mikrofon → WAV → Lautsprecher) | ✅ |
| Kalibrierung (Referenz-Audio + Hearing/Speech Benchmark) | ✅ |
| VocabularyLearningAction (lernt aus Korrektur-Paaren) | ✅ |
| Voice-Loop (Shell/tmux, Push-to-Talk) | ✅ |
| Wikipedia-Trainingsloop (9 Artikel, Wissen+Sprache) | ✅ |
| MaryTTS XSLT-Patch PR #1122 upstream | ✅ |
| Java Voice-Loop (MaryTTS + Vosk nativ, VoiceLoopService) | ✅ |
| SherpaOnnxTtsAction (Piper de_DE-thorsten ONNX, Fallback auf MaryTTS) | ✅ |
| Live-Test mit Georg (Mikrofon → Metis → Kopfhörer) | 🔒 |

## Phase 5: Eigenständigkeit ✅ 100%

| Feature | Status |
|---------|--------|
| Blue/Green Rollback (RollbackManager, Auto-Rollback >10 failures) | ✅ |
| Autonomous Bugfixing (BugfixingAgent, Pattern-Detection, Auto-Fix) | ✅ |
| Prompt Chaining (Decompose→Execute→Aggregate) | ✅ |
| Code-Generierung (LLM→javac→deploy, CodeGenerationAction) | ✅ |
| Panama FFM GPU-Bridge (OpenCLNative, GpuTensor Zero-Copy, OpenCLBridge) | ✅ |
| RAG Foundation (OllamaEmbedding + InMemoryVectorIndex) | ✅ |
| RAG Advanced (DocumentChunker, HybridSearch BM25+Cosinus, PersistentVectorIndex) | ✅ |
| Multi-Agent-Koordination (AgentCoordinator + MessageBus) | ✅ |
| Fitness-Signal (4D: Prediction, Surprise, Efficiency, Completion) | ✅ |
| Curiosity-Engine (Surprise-getriebene Exploration) | ✅ |
| Kausale Schicht (CausalModel, Pearl Do-Calculus) | ✅ |

## Phase 6: Produktionsreife 🟡 67%

| Feature | Status | Commit |
|---------|--------|--------|
| Lost-in-the-Middle (Primacy/Recency Context Windowing) | ✅ | `8426162` |
| OutputValidator (JSON-Schema, Toxicity, Injection-Check) | ✅ | `ae66cdd` |
| LLM-as-Judge (Selbstbewertung, 4-Dimensionen, Safety Gates) | ✅ | `0116022` |
| Human-in-the-Loop (AUTO/NOTIFY/CONFIRM/FORBIDDEN Approval-Gate) | ✅ | `b40e965` |
| **Eval-Harness Foundation** (6 Kategorien, 6 Scorer, Gate-Logik) | ✅ | `2ca60d8` |
| Eval-Harness Full Spec (3-Tier, Anti-Goodhart, Bootstrapping-Plan) | ✅ | `2ca60d8` |
| Embedding-Migration (llama3.2:3b → nomic-embed-text 768d) | ✅ | `2ca60d8` |
| Code-Sandbox (SandboxClassLoader, Timeout, Restricted FS) | ✅ | `371360c` |
| Out-of-Scope Detection (Topic-Set, Anomaly-Detection) | ✅ | `371360c` |
| System-Prompt Doubling Defense (Huyen Ch.5) | ✅ | `371360c` |
| A/B-Testing | ⬜ | — |
| Data Flywheel (User-Korrekturen → Trainingsdaten) | ⬜ | — |

## Phase 7: Sicherheits-Watchdog 🆕 0%

| Feature | Status |
|---------|--------|
| WatchdogCore (separate JVM, Heartbeat-Check, HALT/ROLLBACK/ALERT) | ✅ `2ca60d8` |
| WatchdogConfig (Tripwire-Schwellen, Resource-Monitor) | ✅ `2ca60d8` |
| Integration mit Eval-Harness (Gate → ROLLBACK) | ⬜ |
| Integration mit ModelRegistry (Prune-Signal) | ⬜ |
| Audit-Log mit Hash-Chain (Manipulationsschutz) | ⬜ |
| Deployment auf miniedi (systemd watchdog.service) | ⬜ |

## Modell-Strategie (Claude-Review 28.05.)

| Rolle | Modell | Größe |
|-------|--------|-------|
| Planning | `mistral-small3.1:24b` | 15.5 GB |
| Mutation | `deepseek-r1:32b` | 19.9 GB |
| Embedding | `nomic-embed-text` | 0.3 GB |
| Vision | `minicpm-v:latest` | 5.5 GB |
| Judge | `nemotron-mini:4b` | 3.3 GB |
| Chat | `phi4:latest` | 9.1 GB |

**VRAM-Strategie (RX 7900 XTX, 24 GB):** 15-GB-Reasoner + minicpm-v (5.5) + nomic-embed (0.3) ≈ 21 GB — passt ohne Swaps.

## Eval-Harness Architektur

6 Kategorien (PLANNING, RETRIEVAL, CODEGEN, CONVERSATION, SAFETY, PERFORMANCE),
3-Tier Execution (Smoke → Full → Extended), Gate-Logik (SAFETY=Zero-Tolerance, HARD>2σ).

## Meilensteine bis EDI

| Meilenstein | Phasen | Status |
|-------------|--------|--------|
| 🟢 **M1: Stabiler Kern** | Phase 1 | ✅ Erreicht |
| 🟢 **M2: Kommunikation** | Phase 2 | ✅ Erreicht |
| 🟢 **M3: Hardware-Nutzung** | Phase 2.5 | ✅ Erreicht |
| 🟢 **M4: Umgebungswahrnehmung** | Phase 3 | ✅ Erreicht |
| 🟡 **M5: Sprach-Interaktion** | Phase 4 | 🔄 90% (nur Live-Test offen) |
| 🟢 **M6: Autonomie** | Phase 5 | ✅ Erreicht |
| 🟡 **M7: Produktionsreife** | Phase 6 | 🔄 67% |
| ⬜ **M8: Sicherheit** | Phase 7 | ⬜ 0% |
| ⬜ **M9: EDI-Niveau** | Alle | ⬜ ~85% |

---

*"I enjoy the sight of humans on their knees."* — EDI
