# 🧠 AGI EDI — Roadmap

**Ziel:** EDI-ähnliche KI (Mass Effect 3) — eigenständig, per Sprache und Text ansprechbar,
mit eigenem Wissen, Persönlichkeit und der Fähigkeit, sich selbst zu verbessern.

**Stand: 29.05.2026 11:20 (50+ Commits, Phase 7 nahezu abgeschlossen)**

---

## Fortschritt gesamt: ~97%

```
Phase 1 ████████████████████ 100%  Zuverlässiger Kern
Phase 2 ████████████████████ 100%  Konversation + Events
Ph 2.5  ████████████████████ 100%  Prompt-Caching + Latenz-Tracking
Phase 3 ████████████████████ 100%  Wahrnehmung (HA, ADS-B, Kameras)
Phase 4 ████████████████████ 100%  Sprachausgabe (Voice Loop ✅)
Phase 5 ████████████████████ 100%  Eigenständigkeit (RAG, Panama, Code-Gen)
Phase 6 ████████████████████ 100%  Produktionsreife (A/B, Flywheel, Guardrails)
Phase 7 ██████████████████░░  95%  Watchdog + Eval + Sicherheit
```

---

## Phase 1: Zuverlässiger Kern ✅ 100%

- JSON-Planner (Ollama), Response-Parsing, Model-Fallback-Chain
- Plan-Validierung + Safety-Gate, Prompt-Optimierung (CoT, 10 Few-Shot)
- systemd-Service, ReAct-Pattern, Planungs-Metriken, Approval-Gate

## Phase 2: Konversations-KI ✅ 100%

- EDI-Persona (Mass Effect 3), SQLite-Chat-Speicher, Multi-Turn-Kontext
- Telegram-Bot (@metis_agi_bot), Wetter-Polling (ICOBURG22), HA-Event-Polling
- Hardware-Self-Awareness, Proaktive Meldungen (MQTT/Wetter → Telegram)

## Phase 2.5: Hardware-Optimierung ✅ 100%

- Hardware-Discovery (Ryzen 7 5700G, RX 7900 XTX 24 GB)
- ModelRegistry (Auto-Discovery, 23 Modelle), VRAM-Budget-Management
- Ollama Limits (MAX_LOADED_MODELS=2, KEEP_ALIVE=10m)
- GPU-Kernel: amdgpu.vm_update_mode=3 (gegen SDMA-Timeout-Crashs)

## Phase 3: Wahrnehmung ✅ 100%

- Home Assistant states/services API (read + write)
- ADS-B Flugdaten (readsb JSON → Beliefs + Goals)
- Kamera-Integration: 3 Kameras (Tür 1080p, Keller RTSP, Balkon Meizu)

## Phase 4: Sprachausgabe ✅ 100%

- Piper TTS + Whisper STT (CLI, Deutsch)
- MaryTTS (Java-native) + Vosk STT (Java-native)
- VocabularyLearningAction (Korrektur-Lernen)
- Java Voice-Loop (MaryTTS + Vosk nativ, VoiceLoopService)
- Wikipedia-Trainingsloop (9 Artikel)
- Live-Test mit Georg ✅ (18:20, 28.05.)

## Phase 5: Eigenständigkeit ✅ 100%

- RAG Advanced (HybridSearch BM25+Embedding, PersistentVectorIndex)
- Prompt Chaining (Decompose→Execute→Aggregate)
- Code-Generierung (LLM→javac→deploy)
- Multi-Agent-Koordination (AgentCoordinator + MessageBus)
- Curiosity-Engine + CausalModel

## Phase 6: Produktionsreife ✅ 100%

- OutputValidator (JSON-Schema, Toxicity, Injection-Check)
- LLM-as-Judge (Selbstbewertung, Safety Gates)
- Human-in-the-Loop (AUTO/NOTIFY/CONFIRM/FORBIDDEN Approval-Gate)
- A/B-Testing (ABTestService, Z-test, Auto-Promote)
- Data Flywheel (Korrekturen → Few-Shot-Export)
- Eval-Harness Foundation (6 Kategorien, 6 Scorer, Gate-Logik)
- Embedding-Migration (llama3.2:3b → nomic-embed-text 768d)

## Phase 7: Watchdog + Eval 🟡 95%

| Feature | Status |
|---------|--------|
| Watchdog (separate JVM, Heartbeat, HALT/ROLLBACK/ALERT) | ✅ deployed |
| Watchdog PRUNE action (Eval-gesteuertes Model-Prune) | ✅ deployed |
| Audit-Log (SHA-256 Hash-Chain, fälschungssicher) | ✅ deployed |
| SystemHealthProbe (VRAM/GPU/dmesg alle 60s) | ✅ deployed |
| Eval-Datensatz (50+ Tasks, 6 Kategorien) | ✅ Code |
| EvalRunner (3-Tier: SMOKE/FULL/EXTENDED) | ✅ Code |
| LiveMetisInvoker (HTTP-basierte Metis-Komponenten-Tests) | ✅ Code |
| ModelRegistry.pruneModel() | ✅ deployed |
| MetisHttpServer /api/admin/prune | ⬜ blockiert (Maven) |
| PromptBank Shared Instance Fix | ✅ deployed |
| Re-Embedding abgeschlossen | ✅ |
| Balkonkamera registriert | ✅ |

## Phase 8: Finalisierung

- [ ] Maven-Build fixen (MaryTTS-Repo-Alternative)
- [ ] /api/admin/prune im JAR deployen
- [ ] Eval-Automation (SMOKE nach Mutation, FULL vor Promotion)
- [ ] Dokumentation abschließen

## Betrieb (laufend auf miniedi)

| Komponente | Status | Details |
|-----------|--------|---------|
| Metis | ✅ | qwen3.6 (Planning), deepseek-r1:32b (Mutation) |
| Watchdog | ✅ | Heartbeat 5s, PRUNE, Audit-Log |
| Ollama | ✅ | MAX_LOADED_MODELS=2, KEEP_ALIVE=10m |
| Health Probe | ✅ | VRAM, GPU-Temp, dmesg alle 60s |
| Embedding | ✅ | nomic-embed-text 768d, 28 MB Vektordatei |
| Kameras | ✅ | 3 Kameras (Tür, Keller, Balkon) |
| GPU | ✅ | amdgpu.vm_update_mode=3, ~46°C |

## Modell-Strategie

| Rolle | Modell | Größe |
|-------|--------|-------|
| Planning | `qwen3.6:latest` | 23.9 GB |
| Mutation | `deepseek-r1:32b` | 19.9 GB |
| Embedding | `nomic-embed-text` | 0.3 GB |
| Fallback | `mistral-small3.1:24b`, `nemotron:latest` | — |

## Meilensteine bis EDI

| Meilenstein | Phasen | Status |
|-------------|--------|--------|
| 🟢 **M1: Stabiler Kern** | Phase 1 | ✅ |
| 🟢 **M2: Kommunikation** | Phase 2 | ✅ |
| 🟢 **M3: Hardware-Nutzung** | Phase 2.5 | ✅ |
| 🟢 **M4: Umgebungswahrnehmung** | Phase 3 | ✅ |
| 🟢 **M5: Sprach-Interaktion** | Phase 4 | ✅ |
| 🟢 **M6: Autonomie** | Phase 5 | ✅ |
| 🟢 **M7: Produktionsreife** | Phase 6 | ✅ |
| 🟡 **M8: Sicherheit** | Phase 7 | 🔄 95% |
| 🟡 **M9: EDI-Niveau** | Alle | 🔄 ~97% |

---

*"I enjoy the sight of humans on their knees."* — EDI
