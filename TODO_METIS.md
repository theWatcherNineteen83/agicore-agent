# TODO Metis — Stand 28.05.2026 14:15

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

### 🔴 Gap-Analyse — Was fehlt für Produktionsreife?
1. **RAG mit Vector DB** — Beliefs → Embeddings → Semantic Search (Huyen Kap.6)
2. **LLM-as-Judge** — Selbstbewertung für Qualitäts-Scoring (Huyen Kap.3)
3. **Output-Validierung** — JSON-Schema, Factual-Check, Toxicity (Huyen Kap.10)
4. **A/B-Testing** — Prompt-Varianten in Produktion vergleichen
5. **Lost-in-the-Middle** — Kontext-Management für lange Sessions
6. **Human-in-the-Loop verfeinern** — Read/Write-Differenzierung im Approval-Gate
7. **Data Flywheel** — User-Korrekturen → automatisch Trainingsdaten verbessern

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

## Phase 5: Eigenständigkeit 🟡 55%
- [x] Blue/Green Rollback ✅ (RollbackManager, Auto-Rollback bei >10 failures)
- [x] Autonomous Bugfixing ✅ (BugfixingAgent, Pattern-Detection, Auto-Fix)
- [x] Prompt Chaining ✅ (PromptChainingService, Decompose→Execute→Aggregate, 3bbcdf2)
- [ ] Selbstständige Code-Generierung (LLM→javac→Test→Eval)
- [ ] JNI/Panama-Bridge für GPU
- [x] Multi-Agent-Koordination ✅

## 🔴 Nächstes Todo-Fenster: Buch-Abgleich & ReAct (27.05.)

### Aus GenerativeKI-Systeme-Entwickeln (Huyen Kap. 6):
- [x] **ReAct-Pattern:** Thought→Action→Observation — Prompt um thought-Feld erweitert ✅
- [x] **Planungs-Metriken:** totalPlans, validCount, emptyCount, actionUsage/Error → /api/status ✅
- [x] **Human-in-the-Loop für Write-Aktionen:** Approval-Gate blockt Write-Actions ✅
  - Huyen: "definieren, wie viel Automation ein Agent für jede Aktion besitzen darf"
  - Metis hat keine Unterscheidung Read/Write mit Approval

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
