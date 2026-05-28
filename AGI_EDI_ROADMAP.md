# 🧠 AGI EDI — Roadmap

**Ziel:** EDI-ähnliche KI (Mass Effect 3) — eigenständig, per Sprache und Text ansprechbar,
mit eigenem Wissen, Persönlichkeit und der Fähigkeit, sich selbst zu verbessern.

**Stand: 28.05.2026 (33 Commits, Phase 5: 55%)**

---

## Fortschritt gesamt: ~88%

```
Phase 1 ████████████████████ 100%  Zuverlässiger Kern
Phase 2 ████████████████████ 100%  Konversation + Events
Ph 2.5  ████████████████████ 100%  Prompt-Caching + Latenz-Tracking
Phase 3 ████████████░░░░░░░░  67%  Wahrnehmung (HA ✅, ADS-B ✅, Webcam ✅, Kamera 🟡)
Phase 4 ██████████████████░░  95%  Sprachausgabe (Java Voice Loop ✅, Wiki 29/29 ✅)
Phase 5 ███████████░░░░░░░░░  55%  Eigenständigkeit (Panama ✅, Rollback ✅, Bugfix ✅)
```

---

## Phase 1: Zuverlässiger Kern ✅ 100%

| # | Feature | Status | Commit |
|---|---------|--------|--------|
| 1.1 | JSON-Planner (Ollama) | ✅ | — |
| 1.2 | Response-Parsing (generate/chat/thinking) | ✅ | — |
| 1.3 | Model-Fallback-Chain | ✅ | — |
| 1.4 | Plan-Validierung + Safety-Gate | ✅ | — |
| 1.5 | Prompt-Optimierung v1 + Few-Shot | ✅ | — |
| 1.6 | systemd-Service | ✅ | — |
| 1.7 | Prompt v2 (CoT, 10 Few-Shot, temp 0.3) | ✅ | `40f718f` |
| 1.8 | ReAct-Pattern (Thought→Action→Observation) | ✅ | `9b45c52` |
| 1.9 | Planungs-Metriken (valid/invalid/empty/errors) | ✅ | `9b45c52` |
| 1.10 | Human-in-the-Loop Approval-Gate | ✅ | `6bf53dd` |

## Phase 2: Konversations-KI ✅ 100%

| # | Feature | Status |
|---|---------|--------|
| 2.1 | EDI-Persona (Mass Effect 3) | ✅ |
| 2.2 | Chat-Speicher (SQLite) | ✅ |
| 2.3 | Multi-Turn-Kontext + /api/conversations | ✅ |
| 2.4 | Telegram-Bot (@metis_agi_bot) | ✅ |
| 2.5 | Wetter-Polling (ICOBURG22) | ✅ |
| 2.6 | HA-Event-Polling | ✅ |
| 2.7 | Hardware-Self-Awareness | ✅ |
| 2.8 | Deep Netts (neuronale Netze) | ✅ |
| 2.9 | KnowledgeReplyService | ✅ |
| 2.10 | Proaktive Meldungen (MQTT/Wetter → Telegram) | ✅ |
| 2.11 | MQTT-Integration (Eclipse Paho) | ✅ |

## Phase 2.5: Hardware-Optimierung ✅ 100%

| # | Feature | Status |
|---|---------|--------|
| 2.5.1 | Hardware-Discovery (Ryzen 7, RX 7900 XTX) | ✅ |
| 2.5.2 | TornadoVM GPU-Integration | ✅ |
| 2.5.3 | ModelRegistry (auto-select models) | ✅ |
| 2.5.4 | VRAM-Budget-Management | ✅ |

## Phase 3: Wahrnehmung 🟡 40%

| # | Feature | Beschreibung | Status |
|---|---------|-------------|--------|
| 3.1 | HA Direktzugriff | states/services API (read + write) | ✅ |
| 3.2 | Kamera-Integration | Türkamera (MJPEG 1080p) + Keller (RTSP) | ⬜ |
| 3.3 | ADS-B Flugdaten | readsb JSON-Feed → Goals | ✅ |

## Phase 4: Sprachausgabe & -eingabe 🟡 80%

| # | Feature | Beschreibung | Status |
|---|---------|-------------|--------|
| 4.1 | Piper TTS Action | Neural TTS, Deutsch (thorsten-medium) | ✅ |
| 4.2 | Whisper STT Action | Neural STT, Deutsch (ggml-tiny) | ✅ |
| 4.3 | MaryTTS Action | Java-native TTS (de.dfki.mary:5.2.1, fat JAR) | ✅ |
| 4.4 | Vosk STT Action | Java-native STT (com.alphacephei:vosk:0.3.45) | ✅ |
| 4.5 | ALSA + PipeWire | Audio-Stack auf miniedi (Realtek ALC1220) | ✅ |
| 4.6 | Piper CLI + Model | de_DE-thorsten-medium (63 MB) installiert | ✅ |
| 4.7 | Whisper CLI + Model | openai-whisper (tiny+small) via pipx installiert | ✅ |
| 4.8 | Audio-Input (Mikrofon) | arecord + Java Sound API → 16kHz mono WAV | ✅ |
| 4.9 | Audio-Output (Kopfhörer) | aplay/pw-cat + Java Sound API ← WAV | ✅ |
| 4.10 | Voice-Loop (Shell) | tmux-Session, Push-to-Talk, Metis-Chat | ✅ |
| 4.11 | Kalibrierung | Referenz-Audio (28s) + Hearing/Speech Benchmark | ✅ |
| 4.12 | VocabularyLearning | Java-Action: lernt aus STT-Korrekturen → Vosk-Grammatik | ✅ |
| 4.13 | Vosk deutsches Modell | vosk-model-de downloaden (~92 MB) | ✅ |
| 4.14 | MaryTTS bits1 | Java-native deutsche Stimme (Java-17-Patch eingebaut) | ✅ |
| 4.15 | MaryTTS XSLT-Patch | Pull Request #1122 an upstream (Metis AGI + DeepSeek R1 32B) | ✅ |
| 4.16 | Wikipedia-Trainingsloop | 9 Artikel, Wissen+Sprache über Nacht lernen | ✅ |
| 4.17 | Voice-Loop (Java) | Java-native kontinuierliche Sprachinteraktion | 🔜 |
| 4.18 | Live-Test mit Georg | End-to-End: Mikrofon → Metis → Kopfhörer | 🔜 |

## Phase 5: Eigenständigkeit 🟡 30%

| # | Feature | Beschreibung | Status |
|---|---------|-------------|--------|
| 5.1 | Multi-Agent-Koordination | AgentCoordinator + MessageBus | ✅ |
| 5.2 | Selbstständige Code-Generierung | Metis schreibt eigene Java-Klassen | ⬜ |
| 5.3 | Panama FFM GPU-Bridge | Direkter GPU-Zugriff ohne JNI | ⬜ |
| 5.4 | Evolution-Manager | Code-Mutation + Shadow-Evaluation | ✅ |
| 5.5 | Curiosity-Engine | Surprise-getriebene Exploration | ✅ |
| 5.6 | Fitness-Signal | 4D: Prediction, Surprise, Efficiency, Completion | ✅ |
| 5.7 | Causal Model | Pearl Do-Calculus | ✅ |

---

## Nächste Schritte (priorisiert)

1. 🟡 **Vosk deutsches Modell** — vosk-model-de downloaden (~50 MB)
2. 🟡 **Java Voice-Loop** — MaryTTS + Vosk native integrieren
3. 🟡 **Live-Test** — Mikrofon → Metis → Kopfhörer mit Georg
4. 🟡 **HA Direktzugriff** — Home Assistant states/services API
5. 🟡 **Kamera-Integration** — MJPEG/RTSP Stream-Verarbeitung
6. ⚪ **Selbstständige Code-Generierung** — Metis schreibt eigenen Code
7. ⚪ **Panama FFM** — GPU-Direktzugriff

---

## Meilensteine bis EDI

| Meilenstein | Phasen | Status |
|-------------|--------|--------|
| 🟢 **M1: Stabiler Kern** | Phase 1 | ✅ Erreicht |
| 🟢 **M2: Kommunikation** | Phase 2 | ✅ Erreicht |
| 🟢 **M3: Hardware-Nutzung** | Phase 2.5 | ✅ Erreicht |
| 🟡 **M4: Sprach-Interaktion** | Phase 4 | 🔄 60% |
| 🟡 **M5: Umgebungswahrnehmung** | Phase 3 | 🔄 50% |
| ⬜ **M6: Autonomie** | Phase 5 | ⬜ 30% |
| ⬜ **M7: EDI-Niveau** | Alle | ⬜ ~75% |

---

*"I enjoy the sight of humans on their knees."* — EDI
