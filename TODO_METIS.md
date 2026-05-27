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

## 🔴 Nächstes Todo-Fenster: Buch-Abgleich & ReAct (27.05.)

### Aus GenerativeKI-Systeme-Entwickeln (Huyen Kap. 6):
- [ ] **ReAct-Pattern:** Thought→Action→Observation-Zyklus statt Single-Action-Planning
  - Huyen: "Das Verbinden von Schlussfolgern und Ausführen ist zum verbreiteten Pattern geworden"
  - Aktuell: Metis plant Single-Action, kein expliziter Thought→Observation-Loop
  - Umsetzung: Planner-Prompt um ReAct-Format erweitern (Thought vor Action, Observation nach Response)
- [ ] **Planungs-Metriken systematisch tracken:** gültige Pläne, Tool-Fehler, Parameter-Fehler
  - Huyen: "Ermitteln Sie seine Fehlerzustände und messen Sie, wie oft diese auftreten"
  - Metis trackt aktuell nur llmCalls/llmFailures/fallbackUses
- [ ] **Human-in-the-Loop für Write-Aktionen:** Approval-Gate bei riskanten Aktionen
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
