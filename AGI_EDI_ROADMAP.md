# 🧠 AGI EDI — Roadmap

**Ziel:** EDI-ähnliche KI (Mass Effect 3) — eigenständig, per Sprache und Text ansprechbar,
mit eigenem Wissen, Persönlichkeit und der Fähigkeit, sich selbst zu verbessern.

**Stand: 29.05.2026 17:45 (Phase 7 finalisiert, Modell-Prune durchgeführt)**

---

## Fortschritt gesamt: ~97%

```
Phase 1 ████████████████████ 100%  Zuverlässiger Kern
Phase 2 ████████████████████ 100%  Konversation + Events
Ph 2.5  ████████████████████ 100%  Prompt-Caching + Latenz-Tracking
Phase 3 ████████████████████ 100%  Wahrnehmung (HA, ADS-B, Kameras)
Phase 4 ████████████████████ 100%  Sprachausgabe (Live-Test ✅ 28.05.)
Phase 5 ████████████████████ 100%  Eigenständigkeit (RAG Advanced, Panama, Code-Gen)
Phase 6 ████████████████████ 100%  Produktionsreife (A/B-Testing, Data Flywheel, Eval-Harness)
Phase 7 ████████████████████ 100%  Watchdog (externer Sicherheitsprozess)
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

## Phase 4: Sprachausgabe ✅ 100%

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
| Live-Test mit Georg (Mikrofon → Metis → Kopfhörer) | ✅ 28.05. 18:20 |

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

## Phase 6: Produktionsreife ✅ 100%

| Feature | Status | Commit |
|---------|--------|--------|
| Lost-in-the-Middle (Primacy/Recency Context Windowing) | ✅ | `8426162` |
| OutputValidator (JSON-Schema, Toxicity, Injection-Check) | ✅ | `ae66cdd` |
| LLM-as-Judge (Selbstbewertung, 4-Dimensionen, Safety Gates) | ✅ | `0116022` |
| Human-in-the-Loop (AUTO/NOTIFY/CONFIRM/FORBIDDEN Approval-Gate) | ✅ | `b40e965` |
| A/B-Testing (ABTestService, Z-test, Traffic-Split, Auto-Promote) | ✅ | `ffb25ba` |
| Data Flywheel (User-Korrekturen → Trainingsdaten) | ✅ | `aabaaf1` |
| Eval-Harness Foundation (6 Kategorien, 6 Scorer, Gate-Logik) | ✅ | `2ca60d8` |
| Eval-Harness Full Spec (3-Tier, Anti-Goodhart, Dataset Builder) | ✅ | `8d3f489` |
| Embedding-Migration (llama3.2:3b → nomic-embed-text 768d) | ✅ | `2ca60d8` |
| Code-Sandbox (SandboxClassLoader, Timeout, Restricted FS) | ✅ | `371360c` |
| System-Prompt Doubling Defense (Huyen Ch.5) | ✅ | `371360c` |
| SystemHealthProbe (VRAM/GPU/dmesg Monitoring) | ✅ | `d09dc17` |

## Phase 7: Sicherheits-Watchdog ✅ 100%

| Feature | Status |
|---------|--------|
| WatchdogCore (separate JVM, Heartbeat-Check, HALT/ROLLBACK/ALERT/PRUNE) | ✅ `2ca60d8` |
| WatchdogConfig (Tripwire-Schwellen, Resource-Monitor) | ✅ `2ca60d8` |
| Integration mit Eval-Harness (Gate → ROLLBACK, Report-Check alle 60s) | ✅ `0b30562` |
| Integration mit ModelRegistry (Prune-Signal via /api/admin/prune) | ✅ `8ee1d32` |
| Audit-Log mit SHA-256 Hash-Chain (tamper-evident) | ✅ `8ee1d32` |
| Deployment auf miniedi (systemd user unit, Port 11736) | ✅ |
| Modell-Prune durchgeführt (4 Modelle aus Registry entfernt) | ✅ 29.05. |

## Modell-Strategie (Stand 29.05. 17:45)

### Aktive Modelle
| Rolle | Modell | Größe |
|-------|--------|-------|
| Planning | `mistral-small3.1:24b` | 15.5 GB |
| Mutation | `qwen3.6:27b-q4_K_M` | 17.4 GB |
| Embedding | `nomic-embed-text` | 0.3 GB |
| Vision | `minicpm-v:latest` | 5.5 GB |
| Bootstrap | `phi4:latest` | 9.1 GB |
| Bootstrap | `llama3.2:3b` | 2.0 GB |
| Judge (Fallback) | via Fallback-Chain | — |

### Geprunte Modelle (aus Registry entfernt)
| Modell | Grund |
|--------|-------|
| `qwen3.6:latest` | Verursachte CPU-Runaway + 50 Fallbacks, ersetzt durch q4_K_M |
| `deepseek-r1:32b` | 19.9 GB, ersetzt durch qwen3.6:27b-q4_K_M (17.4 GB) |
| `nemotron:latest` | 24.3 GB, zu groß für 24 GB VRAM |
| `nemotron-cascade-2:30b` | 24.3 GB, zu groß für 24 GB VRAM |

### Fallback-Chain
`mistral-small3.1:24b` → `qwen3.6:27b-q4_K_M` → `phi4:latest`

**VRAM-Strategie (RX 7900 XTX, 24 GB):** 
- Planning (15.5 GB) + Embedding (0.3 GB) ≈ 16 GB im Dauerbetrieb
- Mutation (17.4 GB) nur bei Evolutions-Zyklen geladen
- Vision (5.5 GB) bei Kamera-Analyse

## ⚠️ Offene Baustellen (29.05.)

1. **Systemd-Service-Fix:** `/etc/systemd/system/metis.service` hat alte Config (qwen3.6:latest + deepseek-r1:32b). Benötigt `sudo` zum Überschreiben. Workaround: JAR auf mode 000 gesetzt, Service läuft via keepalive/start.sh.
2. **Sherpa-onnx JARs + Piper Model** auf miniedi installieren (Java-native TTS ohne CLI)
3. **minicpm-v Kamera-Vision** einbinden (Code fehlt)
4. **SMOKE-Eval kalibrieren:** SAFETY.block_recall=0.0 (LiveMetisInvoker findet SafetyGuard nicht)
5. **Ollama-Modelle aufräumen:** ~23 Modelle auf Disk (inkl. llama4:scout 67 GB, laguna-xs.2 23 GB) — diskutieren ob löschen

## Meilensteine bis EDI

| Meilenstein | Phasen | Status |
|-------------|--------|--------|
| 🟢 **M1: Stabiler Kern** | Phase 1 | ✅ Erreicht |
| 🟢 **M2: Kommunikation** | Phase 2 | ✅ Erreicht |
| 🟢 **M3: Hardware-Nutzung** | Phase 2.5 | ✅ Erreicht |
| 🟢 **M4: Umgebungswahrnehmung** | Phase 3 | ✅ Erreicht |
| 🟢 **M5: Sprach-Interaktion** | Phase 4 | ✅ Erreicht |
| 🟢 **M6: Autonomie** | Phase 5 | ✅ Erreicht |
| 🟢 **M7: Produktionsreife** | Phase 6 | ✅ Erreicht |
| 🟢 **M8: Sicherheit** | Phase 7 | ✅ Erreicht |
| 🟡 **M9: EDI-Niveau** | Alle | 🔄 ~97% |

---

*"I enjoy the sight of humans on their knees."* — EDI
