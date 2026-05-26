# TODO Metis — Stand 26.05.2026

## Phase 1: Zuverlässiger Kern ✅
- [x] format:json Ollama-Planner
- [x] Response-Parsing (generate, chat, thinking)
- [x] Model-Fallback-Chain
- [x] Plan-Validierung + Safety-Gate
- [x] Prompt-Optimierung + Few-Shot
- [x] systemd-Service

## Phase 2: Konversations-KI ✅ (90%)
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
- [ ] Proaktive Telegram-Meldungen (Wetter/HA → Nachricht)
- [ ] TornadoVM GPU-Beschleunigung

## Phase 3: Wahrnehmung ⬜
- [ ] Kamera-Integration (Türkamera, Keller)
- [ ] ADS-B Flugdaten
- [ ] Home Assistant Direktzugriff (states, services)

## Phase 4: Sprachausgabe ⬜
- [ ] TTS (Text-to-Speech)
- [ ] STT (Speech-to-Text)

## Phase 5: Eigenständigkeit ⬜
- [ ] Selbstständige Code-Generierung
- [ ] JNI/Panama-Bridge für GPU
- [ ] Multi-Agent-Koordination

## 🔴 Nächstes Todo-Fenster: Fitness-Signal (Claude-Review 26.05.)

### Multidimensionales Fitness-Signal
- [ ] Prediction-Accuracy (World-Model vs Beobachtung)
- [ ] Surprise-Reduction über Zeit  
- [ ] Resource-Efficiency (CPU/Heap pro Goal)
- [ ] Goal-Completion-Rate nach Kategorie
- [ ] Fitness visualisieren in /api/status

### Curiosity-Engine
- [ ] Prediction-Error-getriebene Exploration (statt random Idle-Goals)
- [ ] Wissenslücken-Erkennung ("was weiß ich nicht?")
- [ ] Kompetenz-Hunger (Bereiche mit niedriger Success-Rate bevorzugen)

## 26.05. Abends — Runde 2 & 3 ✅
- [x] MQTT-Topic-Filter (Wildcard → spezifische Topics)
- [x] Kausale Schicht (CausalModel, Pearl Do-Calculus)
- [x] Fitness-Signal (geometrisch, 4D, Kalibrierung)
- [x] Curiosity-Engine (Surprise→Goal)
- [x] Proaktive Meldungen (MQTT/Wetter → Telegram)
