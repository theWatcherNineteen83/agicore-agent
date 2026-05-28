# TODO Metis — Stand 27.05.2026

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

## Phase 3: Wahrnehmung ✅ 100%
- [x] Kamera-Integration ✅ (Türkamera MJPEG, Keller RTSP/H.265, ffmpeg-Snapshot, Motion-Detection, 5min-Polling)
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
- [x] Java Voice-Loop ✅ (Piper+Whisper CLI, arecord+aplay, PipeWire)
- [ ] Live-Test mit Georg (Mikrofon → Metis → Kopfhörer)

## Phase 5: Eigenständigkeit 🟡 40%
- [x] Blue/Green Rollback ✅ (RollbackManager, Auto-Rollback bei >10 failures)
- [x] Autonomous Bugfixing ✅ (BugfixingAgent, Pattern-Detection, Auto-Fix)
- [ ] Prompt Chaining (multi-step reasoning chains)
- [ ] Selbstständige Code-Generierung
- [ ] JNI/Panama-Bridge für GPU
- [x] Multi-Agent-Koordination ✅

## 🔴 Nächstes Todo-Fenster: Phase 4 abschließen (28.05.)

### Buch-Abgleich 28.05. — Prompting Kurz & Gut + Huyen
| Buch-Konzept | Status |
|---|---|
| Prompt-Aufbau (Rolle, Kontext, Aufgabe, Constraints) | ✅ System-Prompt + Few-Shot |
| Chain of Thought | ✅ 4-Schritt: ANALYZE→MATCH→CHECK→DECIDE |
| Few-Shot Prompting | ✅ 10 Beispiele (1 pro Action) |
| Prompt Chaining | ⬜ Phase 5 (multi-step reasoning) |
| Selbstkritik/Self-Reflection | ✅ PlanValidator + MetaCognition |
| Kontextfenster-Management | ⚠️ Kein "Lost in the Middle" Awareness |
| RAG / Vektor-DB | ⬜ Kein echtes RAG (nur WorldModel Substring) |
| Systematische Eval-Pipeline | ⬜ Keine CI/CD für KI-Qualität |
| Guardrails (Input + Output) | ✅ SafetyGate + Approval-Gate |
| Daten-Flywheel | ✅ VocabularyLearningAction + Wikipedia-Training |

### Huyen Kap. 6 (erledigt in früheren Zyklen):
- [x] **ReAct-Pattern:** Thought→Action→Observation ✅
- [x] **Planungs-Metriken:** /api/status ✅
- [x] **Human-in-the-Loop:** Approval-Gate blockt Write-Actions ✅

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
