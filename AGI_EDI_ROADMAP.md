# 🧠 AGI EDI — Roadmap

**Ziel:** EDI-ähnliche KI (Mass Effect 3) — eigenständig, per Sprache und Text ansprechbar,
mit eigenem Wissen, Persönlichkeit und der Fähigkeit, sich selbst zu verbessern.

**Stand: 27.05.2026 (20 Commits heute)**

---

## Fortschritt gesamt: ~75%

```
Phase 1 ████████████████████ 100%  Zuverlässiger Kern
Phase 2 ████████████████████ 100%  Konversation + Events
Ph 2.5  ████████████████████ 100%  Hardware-Optimierung
Phase 3 ████████░░░░░░░░░░░░  40%  Wahrnehmung (HA ✅, Kamera/ADS-B ⬜)
Phase 4 ████████████░░░░░░░░  60%  Sprachausgabe (Code ✅, Audio-I/O ✅, Test 🔜)
Phase 5 ██████░░░░░░░░░░░░░░  30%  Eigenständigkeit
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
| 3.3 | ADS-B Flugdaten | readsb JSON-Feed → Goals | ⬜ |

## Phase 4: Sprachausgabe & -eingabe 🟡 60%

| # | Feature | Beschreibung | Status |
|---|---------|-------------|--------|
| 4.1 | Piper TTS Action | Neural TTS, Deutsch (thorsten-medium) | ✅ |
| 4.2 | Whisper STT Action | Neural STT, Deutsch (ggml-tiny) | ✅ |
| 4.3 | MaryTTS Action | Java-native evolvable stub | ✅ |
| 4.4 | Sphinx4 Action | Java-native evolvable stub | ✅ |
| 4.5 | Piper CLI installiert | ~/bin/piper + de_DE-thorsten-medium | ✅ |
| 4.6 | Whisper CLI installiert | ~/bin/whisper-cpp + ggml-tiny.bin | ✅ |
| 4.7 | Audio-Input (Mikrofon) | Java Sound API → 16kHz mono WAV | ✅ |
| 4.8 | Audio-Output (Lautsprecher) | Java Sound API ← WAV | ✅ |
| 4.9 | Voice-Loop | Kontinuierliche Sprachinteraktion | 🔜 |

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

1. 🔴 **Piper + Whisper CLI installieren** (miniedi) — macht Phase 4 funktionsfähig
2. 🔴 **Audio-Input/Output** — Mikrofon + Lautsprecher via Java Sound API
3. 🟡 **Voice-Loop** — kontinuierliche Sprachsteuerung
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
| 🟡 **M5: Umgebungswahrnehmung** | Phase 3 | 🔄 40% |
| ⬜ **M6: Autonomie** | Phase 5 | ⬜ 30% |
| ⬜ **M7: EDI-Niveau** | Alle | ⬜ ~75% |

---

*"I enjoy the sight of humans on their knees."* — EDI
