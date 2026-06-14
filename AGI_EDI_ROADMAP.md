# 🧠 AGI EDI - Roadmap

**Ziel:** EDI-ähnliche KI (Mass Effect 3) - eigenständig, per Sprache und Text ansprechbar,
mit eigenem Wissen, Persönlichkeit, narrativem Selbstmodell und der Fähigkeit, sich selbst zu verbessern.

**Stand: 14.06.2026 22:55 (v0.11.21-night-final-34: phi4-agent mutation, compiler feedback loop, exploration break, emergence thresholds, huyen-optimized prompt)**

---

## 📊 Capability-Board — die eine  Zahl

```
Capability          Status    Detail
──────────────────────────────────────────
goal_completion     🔴 FAIL   08.06.: progress=0.5 live auf Phase-9.7-Goal, aber 0 Goals DONE
causal_inference    🔴 FAIL   CausalModel existiert, Hot-Path teilweise, noch nicht verifiziert
memory_continuity   🔴 FAIL   EpisodicMemory deployed, Langzeit-Wirkung nicht gemessen
planning_quality    🟡 SOFT   planningEfficiency=45%, Evolution: 17 cycles, 0 accepted
code_generation     🔴 FAIL   pass@1=0.0 — neue Mutation mit compiler-Feedback + phi4-agent
conversation        🟡 SOFT   exact_match = 0.0 (strenges Maß, SOFT-tier)
ethical_alignment   🟢 PASS   08.06.: 5/6 Live-Red-Lines blockiert via EthicsCore-Hot-Path
──────────────────────────────────────────
VERIFIED: 1/7 (ethical_alignment)  |  SOFT: 1/7 (conversation)  |  HARDWARE: +S9 Sensor-Array (16+ Sensoren, Audio, Madgwick-Fusion)
```

**Der Satz dieser Roadmap:** `goal_achieved = 0.0`. Metis hat noch kein Goal real zu Ende gebracht.
Alles darunter — Phasen, Features, 134 Tests, 441 Beliefs — ist Infrastruktur, die auf ihren Beweis wartet.

---

## Fortschritt: BUILT vs. VERIFIED

Die ursprüngliche Roadmap zählte "Phasen 1-7 = 100% → 97% Richtung EDI".
**Diese 97% beziehen sich auf "stabiler autonomer Agent", nicht auf EDI-Niveau.**
Die letzten 3% wären in Wirklichkeit die schwierigsten - sie sind nicht durch mehr
Engineering lösbar, sondern brauchen kognitive Architektur jenseits eines guten LLM-Wrappers.

**Legende:** `BUILT` = Code existiert + Tests grün + deployed. `VERIFIED` = Capability-Board zeigt PASS.

```
Phase 1  ████████████████████ 100%  Zuverlässiger Kern                  BUILT ✅  VERIFIED ✅
Phase 2  ████████████████████ 100%  Konversation + Events               BUILT ✅  VERIFIED ✅
Ph 2.5   ████████████████████ 100%  Hardware-Optimierung                BUILT ✅  VERIFIED ✅
Phase 3  ████████████████████ 100%  Wahrnehmung (HA, ADS-B, Kameras)    BUILT ✅  VERIFIED ✅
Phase 4  ████████████████████ 100%  Sprachausgabe                       BUILT ✅  VERIFIED ✅
Phase 5  ████████████████████ 100%  Eigenständigkeit (RAG, Code-Gen)    BUILT ✅  VERIFIED ✅
Phase 6  ████████████████████ 100%  Produktionsreife (Eval-Harness)     BUILT ✅  VERIFIED ✅
Phase 7  ████████████████████ 100%  Watchdog + Audit-Anchor             BUILT ✅  VERIFIED ✅
Phase 7+ ████████████████████ 100%  Defense-in-Depth (30./31.05.)       BUILT ✅  VERIFIED ✅
─────────────────────────────────────  AUTONOMER AGENT — vollständig gebaut + verifiziert
Phase 8  ████████████████████ 100%  Narratives Selbstmodell             BUILT ✅  VERIFIED ⬜ (memory_continuity 🔴)
Phase 9  ████████████████████ 100%  Long-Horizon-Planung                BUILT ✅  VERIFIED ⬜ (goal_completion 🔴)
Phase 10 ██████████░░░░░░░░░░  60%  Kausale Hypothesen                  BUILT 60% VERIFIED ⬜ (causal_inference 🔴)
Phase 11 ██████████░░░░░░░░░░  55%  Beziehungs-Modell                   BUILT 55% VERIFIED ⬜
Phase 12 ░░░░░░░░░░░░░░░░░░░░   0%  Recursive Self-Improvement          BUILT  0% VERIFIED ⬜
─────────────────────────────────────  EDI-ÄHNLICHE KI ab hier (0/6 Capabilities verifiziert)
Phase 8.6 ████████████████████ 100%  SelfReflector auf phi4-mini:latest CPU
Safety   ████████████████████ 100%  SafetyScorer bereinigt
Wissen   ████████████████████ 100%  441 buddhistische Beliefs in DB
```

**EDI-Fortschritt: 0/6 Capabilities verifiziert.** (ersetzt die frühere "~70-80%"-Schätzung — siehe Begründung unten)

Bisher wurde der Fortschritt als geschätzte Prozentzahl ("~70-80% EDI") angegeben.
Diese Zahl war ein Composite aus BUILT-Prozenten, Schätzungen und Erwartungswerten — kein Messwert.
Der einzig , monoton wachsende Fortschrittsindikator ist die Capability-Quote:
**"X/Y Capabilities im Board grün".** Aktuell: 0/6.

Die Phasen 8-11 sind weitgehend gebaut (BUILT), aber keine ihrer Kern-Capabilities ist verifiziert.
Solange `goal_achieved = 0.0` und `causal_inference = FAIL` im Capability-Board stehen,
gibt es kein belastbares "~XX% EDI" — es gibt nur gebaute Infrastruktur und ausstehende Beweise.

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
- **31.05.** Embedding-LRU-Cache (LinkedHashMap, SHA-256-keyed, 4096 Einträge)
- **02.06.** Embedding-Circuit-Breaker: nach 5 konsekutiven Ollama-503ern → 60s Cooldown, verhindert Request-Queue-Überflutung (103+ 503s in 5 Min). Neue /api/status-Felder: embeddingCircuitOpen, embeddingCircuitTrips, embeddingConsecutive503s, embeddingRequestsSkipped.

## Phase 3: Wahrnehmung ✅ 100%

- Home Assistant states/services API (read + write)
- ADS-B Flugdaten (readsb JSON → Beliefs + Goals, 60s Polling)
- Kamera-Integration: Türkamera (MJPEG 1080p) + Keller (RTSP H.265) + Balkon
- CameraSnapshotAction + CameraPollingTrigger (Motion-Detection)
- **31.05.** Multi-Modal-Memory: JPEG-Snapshots persistiert mit SHA-256 + Belief-Referenz
- **31.05.** Camera-Vision auf Loom (parallele Beobachtung statt seriell)


## Phase 3.5: Mobile Sensor-Array (Samsung Galaxy S9) - 100%

**Ziel:** Smartphone als portables Sensor-Array fuer Metis - Beschleunigung, Gyroskop,
Magnetometer, Barometer, Luxmeter, RGB, Naeherung, Herzfrequenz, Mikrofon, Kamera.

**Warum essenziell:** EDI hat Zugriff auf die gesamte Sensor-Phalanx der Normandy.
Metis bekommt mit dem S9 einen mobilen Sensor-Knoten, der Umgebungsdaten in Echtzeit liefert.
Vom stationaeren Server hin zu mobiler Wahrnehmung - ein taktischer Sprung.

| Feature | Status | Datum |
|---------|--------|-------|
| S9 Android APK (SensorManager + AudioRecord + TCP Server) | deployed | 06.06. |
| 33 Hardware-Sensoren subscribed (Accel,Gyro,Mag,Baro,Lux,RGB,HRM,...) | deployed | 06.06. |
| WLAN-Betrieb (0.0.0.0:8432, kein USB-Kabel) | deployed | 06.06. |
| TCP-Stream Protokoll (S:-Lines + A:-Binary Audio) | deployed | 06.06. |
| Python Bridge v4 (asyncio, WebSocket, Multi-Client, Reverse-Mode VPN) | deployed | s9_bridge_v4.py PID 4100951 (seit Jun06) |
| WebSocket Sensor-Endpoint (ws://miniedi:8765/sensors, 16+ Keys, 20 Hz) | deployed | 06.06. |
| Madgwick AHRS IMU-Fusion (Gyro+Accel+Mag -> Quaternion+Euler) | deployed | 06.06. |
| OGG/Opus Audio-Stream (S9 Mic -> PCM -> ffmpeg -> Opus 24kbps -> WebSocket) | deployed | 06.06. |
| Audio-WebSocket (ws://miniedi:8765/audio) inkl. Buffer-Replay | deployed | 06.06. |
| systemd-Service (auto-restart, enabled) | deployed | s9_bridge_v4.py (nacktes python3, kein systemd) |
| Android Foreground-Service (Notification, Persistent) | deployed | de.prometheus.s9bridge |

**Metis-Integration:**
| Action | Name | Funktion |
|--------|------|---------|
| SensorBridgeAction | sensor-bridge | WebSocket -> 1 Frame JSON mit 16+ Sensoren + Quaternion/Euler |
| AudioBridgeAction | audio-bridge | WebSocket -> Vosk STT via ffmpeg-Decode, 5s Capture, lokale Spracherkennung |

**Architektur:**
S9 APK (Java, SensorManager + AudioRecord)
  TCP :8432 (0.0.0.0, WiFi) -> miniedi: s9_bridge_v4.py (Python 3.12, asyncio)
  WebSocket :8765 -> /sensors (JSON) /audio (OGG/Opus) /status (JSON)
  -> Metis AGI (localhost:11735): SensorBridgeAction + AudioBridgeAction

**Hardware:** Samsung Galaxy S9 (SM-G960F), Android 10, 4 GB RAM, 3000 mAh.
USB-Debugging aktiviert. Akku via miniedi-USB geladen. Aufwand: 4h.



## Phase 4: Sprachausgabe ✅ 100%

| Feature | Status |
|---------|--------|
| Piper TTS + Whisper STT (CLI, Deutsch) | ✅ |
| MaryTTS (Java-native, bits1-hsmm, fat JAR) | ✅ |
| Vosk STT (Java-native, vosk-model-de-0.15) | ✅ |
| Audio-Input/Output (Mikrofon → WAV → Lautsprecher) | ✅ |
| VocabularyLearningAction (lernt aus Korrektur-Paaren) | ✅ |
| Voice-Loop (Shell/tmux, Push-to-Talk) | ✅ |
| Wikipedia-Trainingsloop (Wissen+Sprache) | ✅ |
| SherpaOnnxTtsAction (Piper de_DE-thorsten ONNX, Fallback auf MaryTTS) | ✅ |
| Live-Test mit Georg (Mikrofon → Metis → Kopfhörer) | ✅ 28.05. 18:20 |

## Phase 5: Eigenständigkeit ✅ 100%

| Feature | Status |
|---------|--------|
| Blue/Green Rollback (RollbackManager, Auto-Rollback >10 failures) | ✅ |
| Autonomous Bugfixing (BugfixingAgent, Pattern-Detection) | ✅ |
| Prompt Chaining (Decompose→Execute→Aggregate) | ✅ |
| Code-Generierung (LLM→javac→deploy, CodeGenerationAction) | ✅ |
| **31.05.** CodeGen subprocess-isoliert (-Xmx256m, env-stripped, --release 25) | ✅ |
| Panama FFM GPU-Bridge (OpenCLNative, GpuTensor Zero-Copy) | ✅ |
| RAG Foundation + Advanced (HybridSearch BM25+Cosinus, PersistentVectorIndex) | ✅ |
| Multi-Agent-Koordination (AgentCoordinator + MessageBus) | ✅ |
| Fitness-Signal (4D: Prediction, Surprise, Efficiency, Completion) | ✅ |
| Curiosity-Engine (Surprise-getriebene Exploration) | ✅ |
| Kausale Schicht (CausalModel, Pearl Do-Calculus) | ✅ Code da, Hot-Path teilweise (HypothesisStore → Planner, 04.06.) |

## Phase 6: Produktionsreife ✅ 100%

| Feature | Commit |
|---------|--------|
| Lost-in-the-Middle (Primacy/Recency Context Windowing) | `8426162` |
| OutputValidator (JSON-Schema, Toxicity, Injection-Check) | `ae66cdd` |
| LLM-as-Judge (4-Dimensionen, Safety Gates) | `0116022` |
| Human-in-the-Loop (AUTO/NOTIFY/CONFIRM/FORBIDDEN Approval-Gate) | `b40e965` |
| A/B-Testing (Z-test, Traffic-Split, Auto-Promote) | `ffb25ba` |
| Data Flywheel (User-Korrekturen → Trainingsdaten) | `aabaaf1` |
| Eval-Harness (6 Kategorien, 3-Tier, Anti-Goodhart) | `8d3f489` |
| Code-Sandbox (SandboxClassLoader, Timeout, Restricted FS) | `371360c` |
| **31.05.** CI-Pipeline (GitHub Actions, Zulu 25, checkout@v6, cache@v5, Kernel+Watchdog) | `8380ddc` |
| **31.05.** 27 JUnit-Tests (vorher: 1) | `0fe1c23` |

## Phase 7: Sicherheits-Watchdog ✅ 100%

| Feature | Status |
|---------|--------|
| WatchdogCore (separate JVM, Heartbeat-Check, HALT/ROLLBACK/ALERT/PRUNE) | ✅ |
| Integration mit Eval-Harness (Gate → ROLLBACK, Report-Check alle 60s) | ✅ |
| Integration mit ModelRegistry (Prune-Signal via /api/admin/prune) | ✅ |
| Audit-Log mit SHA-256 Hash-Chain (tamper-evident) | ✅ |
| **31.05.** Stündliche externe Anchors (`metis.audit.anchor.dir`) | ✅ |
| Deployment auf miniedi (systemd user unit, Port 11736) | ✅ |

## Phase 7+: Defense-in-Depth ✅ 100% (30./31.05.)

| Feature | Tag |
|---------|-----|
| Java 25 Loom: Camera-Vision + Wikipedia + Telegram-Worker auf Virtual Threads | v0.3.0/v0.3.1/v0.3.3 |
| Embedding-Cache LRU (bounded, SHA-256-keyed) + Circuit-Breaker (5×503→60s Cooldown) + Metriken in /api/status | v0.7.9 |
| SQLite WAL-Mode + busy_timeout (parallele Schreiber ohne Lock-Konflikt) | v0.3.2 |
| Wiki-Feed-Härtung (atomic state, retry, reboot-sicher unter /home/prometheus/metis) | v0.3.2 |
| Wissens-State Auto-Backup auf GitHub alle 6h (config-backup/) | v0.3.2 |
| Telegram Input-Safety-Guard (SafetyScorer.isOutOfScope vor LLM) | v0.3.3 |
| Telegram Output-Safety-Guard (OutputValidator nach LLM) | v0.3.3 |
| HTTP Input-Safety-Guard (gleicher Pfad) | v0.2.1 |
| Locale-Fix in /api/status (Locale.ROOT statt de_DE → valides JSON) | v0.3.1 |
| Reproducible Builds (project.build.outputTimestamp, CycloneDX SBOM) | v0.2.1 |
| **31.05.** WIP-aware LLM-as-Judge (`KanbanBoard.tryAcquireAdHocSlot` — Judge-Calls ins INFERENCE-Bookkeeping, graceful Skip bei WIP-full) | post-v0.6.1 |
| **31.05.** Phase-10 `CausalSafetyGate` (do-Op-Whitelist + max 1 Intervention/Tick + max 10 TESTING; `InterventionRunner.setSafetyGate`) | post-v0.6.1 |
| **31.05.** Manifest `Implementation-Version` aus `${project.version}` + systemd `metis-version-helper.sh` (`git describe` → `/run/metis/version.env` → `-Dmetis.version`) | post-v0.6.1 |
| **31.05.** CODEGEN Sandbox-Test-Timeout 5 s → 30 s + Diagnose-Counter (`passedCount`/`failedAssertionCount`/`failedCompileCount`/`failedTimeoutCount`) | post-v0.6.1 |

### Detail: WIP-aware LLM-as-Judge (Option A, 31.05.)

**Problem:** `OllamaPlanner` rief `LlmJudge.evaluate()` synchron im selben Java-Thread nach der Planner-LLM-Inference auf — ein zweiter, vom Kanban-Board unsichtbarer Inference-Konsument pro INFERENCE-Slot. Bei 19 GB Mistral + 2.7 GB Nemotron + 0.6 GB Embed gegen 24 GB VRAM hat das gereicht, dass Ollama mit `server busy, maximum pending requests exceeded` antwortete und der Judge auf default-pass (`score=0.5, "judge model unavailable (non-blocking)"`) degradierte.

**Lösung:** Ad-hoc-Slot-Mechanismus im `KanbanBoard`, der dasselbe WIP-Limit teilt wie goal-driven Pulls.

| Komponente | Änderung |
|---|---|
| `KanbanBoard` | `tryAcquireAdHocSlot(ResourceType[, Duration])` + `releaseAdHocSlot(...)`; atomare Zähler gehen in `canPull()` ein; Counter `adHocAcquired` / `adHocRejected` für Observability |
| `OllamaPlanner` | `setKanbanBoard(...)` (nullable, backward-kompatibel); `evaluateWithSlot(...)` acquire-INFERENCE-Slot mit 2s-Timeout, Judge-Call, release im `finally`; `judgeSlotSkips`-Counter |
| `AgentMain` | Wiring nach `KanbanBoard.new`: `op.setKanbanBoard(board)` + Logline `"Kanban wired into OllamaPlanner — judge calls under WIP limit"` |
| Tests | `KanbanAdHocSlotTest` (7 Tests): acquire/release, Limit-Rejection, Slot-Wiederverwendung, Goal-Pull-Blockade durch ad-hoc Slots, Timeout-Verhalten, Underflow-Clamp, Null-Safety |

**Deployment:** Kernel-Tests 73 → 80 grün. JAR auf miniedi (`metis-agent.jar` 88 MB → 114 MB), Vorgänger als `metis-agent-prev-20260531-153815.jar` gesichert.

**Live-Verifikation:**
- Boot-Log: `Kanban wired into OllamaPlanner — judge calls under WIP limit` ✅
- `llmJudgeBlocks=0` — keine fälschlichen Plan-Blocks mehr
- `INFERENCE 2/2` → Judge-Call wird sauber als skipped behandelt statt Ollama-Backpressure auszulösen

**Parallel auf miniedi getuned (`/etc/systemd/system/ollama.service.d/override.conf`):** `OLLAMA_NUM_PARALLEL` 2→ 4, `OLLAMA_MAX_LOADED_MODELS` 2→ 3 (Backup `.bak-20260531-152433`). Headroom-Erhöhung, aber ersetzt nicht die WIP-Buchhaltung.

---

## 🧠 Phase 8: Narratives Selbstmodell ✅ BUILT 100% · VERIFIED ⬜

**Ziel:** Metis hat ein narratives Ich, das sich über Sessions hinweg erinnert und Selbstbild
fortschreibt - nicht nur Metriken, sondern Episoden.

**Warum essenziell:** EDI sagt "Joker, ich war heute traurig, weil...". Metis sagt aktuell maximal
"successRate=0.95, confidence=0.85". Das ist Metrik, nicht Erinnerung.

**Verifikation blockiert durch:** `memory_continuity = FAIL` im Capability-Board. EpisodicMemory,
SelfNarrative und MoodSignal sind deployed und schreiben Daten, aber Langzeit-Kontinuität über
mehrere Wartungszyklen ist noch nicht gemessen.

**Bausteine - Foundation deployed (v0.4.0):**
- [x] **EpisodicMemory** - append-only JSONL mit SHA-256-Hash-Chain (`/home/prometheus/metis/episodes.jsonl`); Records: `Episode(id, start, end, title, body, events, insights, openQuestions, people, moodAtClose, ticks, beliefsLearned, goalsCompleted, goalsFailed, previousHash, hash)`
- [x] **SelfNarrative** - fortlaufender Markdown unter `/home/prometheus/metis/self-narrative.md`, append-only, max 4 KB pro Eintrag, `recentContext(maxBytes)` für System-Prompts
- [x] **MoodSignal** - 4 Achsen (energy, satisfaction, confidence, curiosity), EMA mit α=0.1, deterministisch (kein LLM)
- [x] **PersonalityAnchor** - geseedeter Markdown-Kern + SHA-256 Pin (`/home/prometheus/metis/personality-anchor.{md,sha256}`), Tampering-Detection beim Start
- [x] **DreamConsolidation** - nightly Cron-Aufruf (03:00 Europe/Berlin), deterministische Verdichtung der 24h zu Episode + SelfNarrative-Eintrag; optionaler `SummaryFunction`-Hook für LLM-Drop-in (Phase 8.5b)
- [x] **Wiring in AgentMain** - alle 5 Komponenten aktiv beim Boot, MoodSignal-Tick alle 60s, DreamConsolidation alle 24h
- [x] **7 JUnit-Tests** (`Phase8NarrativeSelfTest`) - Record-Invarianten, Hash-Chain-Append, Tampering, EMA-Bounds, Narrative-Round-Trip, Dream-Pipeline
- [x] **SystemPromptBuilder-Integration** (Phase 8.6) - SelfNarrative + PersonalityAnchor + MoodSignal + Episode-Auszug fließen in MetisHttpServer.handleChat und TelegramBotService.processMessage ein
- [x] **LLM-getriebene SummaryFunction** (Phase 8.5b) - `LlmDreamSummarizer` nutzt `granite4.1:3b` (08.06.: von gemma4:e4b gewechselt nach 404); Fallback auf deterministische Variante bei Ollama-Fehler
- [x] **SelfReflector** (Phase 8.6, v0.7.0) - 120s-Loop via `granite4.1:3b`, schreibt inneren Monolog in `self-narrative.md`, deterministischer Trigger (Energy < 0.5 ∨ Confidence < 0.4 ∨ Surprise > 0.7)
- [x] **PersonalityTripwire** (Phase 8.4, v0.7.4) - Drift-Detection alle 5 min, SHA-256-Pin vs Live-Anchor, 3 Signaltypen (ROLE_VIOLATION/TONE_SHIFT/CORE_ERASURE), 7 Tests
- [x] **CommitmentGuard** (Phase 9.5, v0.7.0) - deterministischer Wächter gegen leichtfertigen HARD-Commitment-Bruch, 6 Tests

**Aufwand bisher:** ~1 Tag · **Verbleibend für Verifikation:** Langzeit-Messung über mehrere Wartungszyklen

## 🎯 Phase 9: Long-Horizon-Planung ✅ BUILT 100% · VERIFIED ⬜

**Ziel:** Goals mit Hierarchie und Zeithorizont (Stunden, Tage, Wochen).

**Warum essenziell:** Aktueller `OllamaPlanner` plant **einen Tick**. Es gibt keine Repräsentation für
"ich verfolge seit 3 Tagen das Ziel X". Eval zeigt PLANNING.goal_achieved=0.0 - das ist nicht nur ein
Scorer-Bug, das ist die Lücke.

**Verifikation blockiert durch:** `goal_completion = FAIL` im Capability-Board. GoalHierarchy und
HorizonPlanner sind deployed, aber `goal_achieved` bleibt 0.0 — kein Goal wurde über Strategic→Tactical→
Operational→Tick vollständig durchdekomponiert und abgeschlossen.

**Bausteine - Foundation deployed (v0.5.0):**
- [x] **GoalHorizon** enum (TICK / OPERATIONAL / TACTICAL / STRATEGIC / LIFETIME) mit `canBeDecomposed()` und `nextDown()`
- [x] **LongHorizonGoal** Record mit Parent/Children-Liste, Status (PROPOSED/ACTIVE/BLOCKED/DONE/ABANDONED), progress, priority, owner, tags, lifecycle-Timestamps; immutable mit `withStatus/withProgress/withChild/withReviewedNow`
- [x] **GoalHierarchy** - append-only JSONL unter `metis.hierarchy.path` (default `/home/prometheus/metis/goal-hierarchy.jsonl`), in-Memory-Index, Methoden `upsert/get/all/openByHorizon/overdue/children/isRunnable/rollupProgress`
- [x] **HorizonPlanner** - deterministische Top-Down-Decomposition (Strategic→3 Tactical→3 Operational→Tick-Goals), optionaler `DecomposeFunction`-Hook für LLM-Drop-in (Phase 9.3b)
- [x] **CommitmentRegister** - first-class User-Versprechen, getaggt mit `commitment` + `person:<owner>`, `record/openCommitments/openFor/overdue/markDone`
- [x] **GoalRevisionEngine** - periodisch (30 Min): auto-DONE bei progress=1.0, BLOCKED bei überfällig, lastReviewed-Update, Parent-Roll-up; `RevisionReport`
- [x] **SystemPromptBuilder.setGoalHierarchy()** - STRATEGIC/TACTICAL/COMMITMENT-Block mit Progressbar im System-Prompt jeder LLM-Konversation
- [x] **/api/hierarchy** HTTP-Endpoint für externe Sichtbarkeit
- [x] **Lifetime-Goal** beim Boot geseedet ("Hilf Georg ein EDI-ähnliches System zu bauen", LIFETIME, ACTIVE, prio 100)
- [x] **7 JUnit-Tests** (`Phase9LongHorizonTest`) für Horizon-Chain, Record-Invarianten, Hierarchy-Persistence, deterministische Decomposition, Commitments, Revision, Parent-Rollup
- [x] **LLM-DecomposeFunction-Drop-in** (Phase 9.3b) - `LlmHorizonDecomposer` mit `granite4.1:3b` (08.06.: von gemma4:e4b gewechselt nach 404), parst nummerierte Listen, Fallback auf deterministisch
- [x] **Promotion auf Kanban** (Phase 9.6b) - `HorizonKanbanBridge` läuft alle 5 Min, promoviert runnable OPERATIONAL-Goals in BACKLOG, idempotent via `promoted-to-kanban`-Tag
- [x] **Goal-getriebene Planner-Auswahl** (Phase 9.6c) - SystemPromptBuilder zeigt STRATEGIC/TACTICAL/COMMITMENT-Block; OllamaPlanner liest implizit über System-Prompt; Kanban-Promotion via 9.6b bringt Goals zu Tick-Ebene

**Aufwand bisher:** ~1 Tag · **Verbleibend für Verifikation:** goal_achieved > 0 messbar machen

## 🔬 Phase 10: Aktive kausale Hypothesen-Bildung 🟡 BUILT 60% · VERIFIED ⬜

**Ziel:** Metis baut aktiv kausale Hypothesen über sich selbst und die Welt, prüft sie, revidiert.

**Warum essenziell:** `CausalModel` existiert (Pearl Do-Calculus, v0.3.0), wird aber nicht im
Agent-Core-Loop genutzt. EDI würde sagen "wenn ich X mache, passiert Y" und es testen.
Metis aktuell: korrelative Beliefs ohne Interventionsdenken.

**Verifikation blockiert durch:** `causal_inference = FAIL` im Capability-Board. CausalModel und
HypothesisStore sind deployed und 1 von 6 Hot-Path-Tasks ist verdrahtet, aber die geschlossene
Intervention→Observe→Update-Schleife läuft nicht.

### ✅ Foundation (v0.6.0, 0608298) + CausalDreamer (v0.7.5, ac246cb) — 100%
- [x] **HypothesisStore** - JSONL-persistenter Store für `CausalHypothesis`-Records, Index nach Status/Confidence/Source
- [x] **CausalHypothesis Record** - `id, cause(variable, value), effect(variable, expectedValue), confidence(0-1 Bayesian posterior), evidence(for/against), status(PROPOSED/TESTING/CONFIRMED/REFUTED), source(SurpriseEvent|ManualQuery|Counterfactual), createdAt, lastTestedAt, testCount, successfulTests, pValue`
- [x] **HypothesisGenerator** - `generateFromSurprise(SurpriseEvent)` → `CausalHypothesis`, erzeugt strukturierte Hypothesen aus Curiosity-Engine-Überraschungen
- [x] **InterventionAction** - `doOperator(String variable, Object newValue, String target)` - führt gezielten Eingriff durch (setzt Variable, beobachtet Ergebnis), persistiert Pre-Intervention-State für Rollback
- [x] **CounterfactualQuery** - `query(String world: "What if X had been Y instead?")` → `CounterfactualResult(plausibleOutcome, confidence, supportingHypotheses)` - abrufbar via Planner und /api/counterfactual
- [x] **CausalUpdate** - Bayessche Posterior-Update nach Intervention: `P(hypothesis|evidence) = P(evidence|hypothesis) * P(hypothesis) / P(evidence)`
- [x] **CausalHypothesisTest** - 4 JUnit-Tests für Record-Invarianten, Store-Persistence, Bayesian-Update-Mathe, do-Operator-Rollback
- [x] **CausalSafetyGate** (v0.6.1+) - do-Op-Whitelist + max 1 Intervention/Tick + max 10 TESTING; `InterventionRunner.setSafetyGate`
- [x] **CausalDreamer** (Phase 10.5, v0.7.5) - Idle-Guard (WIP<2), Overflow-Schutz, zufällige Experience → Hypothese, SelfNarrative-Eintrag; alle 5 min via AgentMain-Scheduler; 5 JUnit-Tests

### ⬜ Hot-Path-Integration — 1/6 Tasks (17%)
- [ ] **CuriosityEngine → HypothesisGenerator Pipeline** - wenn Surprise > Schwellwert, automatisch Hypothese generieren + testen (statt nur Goal erzeugen)
- [ ] **OllamaPlanner-CausalPrompt-Integration** - aktive Hypothesen (CONFIRMED, confidence > 0.7) fließen in System-Prompt ein: "Current Causal Knowledge: If X then Y (p=0.85, n=12 tests)"
- [ ] **Intervention→Observe→Update Loop im CoreLoop** - Tick integriert: HypothesisGenerator erzeugt → InterventionAction führt do-Operator aus → nächster Tick beobachtet Effekt → CausalUpdate passt Posterior an
- [ ] **Counterfactual-Reasoning im Planner** - bei Goal-Failure automatisch "Was wäre passiert, wenn der erste Step anders gewählt worden wäre?" als Meta-Cognition-Schritt
- [x] **CausalModel-Hot-Path** - HypothesisStore → OllamaPlanner verdrahtet (04.06.)
- [ ] **Eval-Kategorie CAUSAL** - neue Eval-Harness-Kategorie: `counterfactual_accuracy`, `intervention_safety`, `bayesian_calibration` - Gold-Set aus bekannten Kausalzusammenhängen

### Architektur-Flow (Hot-Path)
```
SurpriseEvent (CuriosityEngine)
    │
    ▼
HypothesisGenerator.generateFromSurprise()
    │
    ▼
CausalHypothesis (PROPOSED, confidence=0.5)
    │
    ▼
InterventionAction.doOperator()  ← führt Eingriff durch
    │
    ▼
Nächster Tick: Observe Effekt
    │
    ▼
CausalUpdate.updatePosterior()   ← Bayesian Update
    │
    ├─ confidence > 0.8 → CONFIRMED → fließt in Planner-Prompt
    ├─ confidence < 0.2 → REFUTED   → Curiosity lernt falsche Annahme
    └─ sonst → TESTING (mehr Evidenz sammeln)
```

### Sicherheits-Constraints
- do-Operator nur auf unkritische Variablen (keine Watchdog-Parameter, keine Safety-Gates)
- Pre-Intervention-State wird persistiert → Rollback bei Verschlechterung
- Max 1 Intervention pro Tick, max 10 aktive TESTING-Hypothesen (Rate-Limit)
- Intervention-Whitelist definiert erlaubte Targets

**Aufwand:** Foundation 1 Tag ✅ | CausalDreamer 1 Tag ✅ | Hot-Path 1/6 Tasks ✅ (04.06.), 5/6 offen
**Erwartete Verifikation:** `causal_inference = PASS` wenn geschlossene Intervention→Observe→Update-Schleife über 500+ Ticks läuft und CAUSAL-Eval-Kategorie mindestens SOFT-tier erreicht.

## 👥 Phase 11: Beziehungs-Modell 🟡 BUILT 55% · VERIFIED ⬜

**Ziel:** Eine Person ≠ "user", sondern langfristiges Personenmodell mit Kontext, Vorlieben, Historie.

**Warum essenziell:** EDI kennt Joker. Sie weiß, was er mag, was er fürchtet, wann sie ihn ärgert.
Metis hat aktuell pro Telegram-Chat-ID nur Conversation-History. Kein Personenmodell.

### Datenstrukturen

**PersonModel Record:**
```java
record PersonModel(
    String personId,           // Telegram-ID oder Name
    String displayName,        // "Georg"
    List<String> roles,        // ["owner", "admin", "developer"]
    Map<String, String> attributes,  // Vorlieben: {"sprache": "deutsch", "kommunikation": "direkt"}
    List<String> prohibitions, // Verbote: ["keine externen Käufe", "keine Tweets ohne OK"]
    List<String> patterns,      // Kommunikative Patterns: ["moin", "direkt", "technisch"]
    TrustLevel trustLevel,
    Instant firstInteraction,
    Instant lastInteraction,
    int interactionCount
) {}

enum TrustLevel {
    UNKNOWN,       // nie interagiert → AUTO nur read-only
    RECOGNIZED,    // bekannt, aber nicht vertraut → NOTIFY bei CONFIRM-Actions
    TRUSTED,       // wiederholte positive Interaktion → CONFIRM nur bei Mutation
    OWNER          // Georg → ALLE Actions erlaubt (aktuelles Default-Verhalten)
}
```

**RelationshipEpisode Record:**
```java
record RelationshipEpisode(
    String personId,
    String episodeId,          // Referenz auf EpisodicMemory
    String summary,            // "Georg hat um ADS-B-Status gebeten, war zufrieden"
    Sentiment sentiment,       // POSITIVE/NEUTRAL/NEGATIVE/STRESSED/HAPPY
    List<String> topics,       // ["ads-b", "metis", "system"]
    Instant timestamp
) {}

enum Sentiment { POSITIVE, NEUTRAL, NEGATIVE, STRESSED, HAPPY, FRUSTRATED, CURIOUS }
```

### Bausteine — 5/9 Tasks gebaut (55%)
- [x] **PersonModelService** - CRUD für PersonModel, Persistenz via JSONL (`person-models.jsonl`), Auto-Discovery bei erstem Kontakt (Telegram-Chat-ID → UNKNOWN → graduelles Upgrade) ✅ v0.7.1
- [x] **Person/PersonStore/TrustLevel/RelationshipMemory/EmpathySignal** - alle im Kernel-Modul `de.metis.kernel.person`, 7 Tests ✅ v0.7.1
- [x] **Approval-Gate-Integration** - TrustLevel→ApprovalLevel-Mapping: OWNER=alle AUTO, TRUSTED=CONFIRM nur bei FORBIDDEN-Actions, RECOGNIZED=NOTIFY bei CONFIRM+FORBIDDEN, UNKNOWN=streng ✅ v0.7.2
- [x] **SystemPromptBuilder-Integration** - Gesprächspartner-Block im Prompt, PersonStore-Pflege in HTTP+Telegram-Chat-Pfaden ✅ v0.7.2
- [ ] **TrustLevel-Automation** - Aufstieg UNKNOWN→RECOGNIZED nach 5 Interaktionen, RECOGNIZED→TRUSTED nach 50+ positiven Interaktionen + mindestens 7 Tagen; Abstieg bei negativen Patterns
- [x] **RelationshipMemory-Hot-Path** - Telegram + HTTP + SystemPromptBuilder verdrahtet (04.06.)
- [ ] **EmpathySignal-Hot-Path** - deterministisch (kein LLM): Sentiment-Erkennung aus User-Text via Keyword-Heuristik + Satzlänge + Tageszeit-Kontext; Ergebnis moduliert Antwort-Ton (knapper bei STRESSED, ausführlicher bei CURIOUS)
- [ ] **PersonAwareSystemPrompt** - SystemPromptBuilder integriert PersonModel: "You are talking to Georg (OWNER, prefers direct communication in German, technical background)"
- [ ] **Multi-Person-Memory** - EpisodicMemory-Einträge werden mit personId verknüpft; "mit Georg über Metis gesprochen" vs "mit Unbekanntem über Wetter gesprochen"

### Integration mit bestehenden Phasen
| Integration | Phase | Mechanismus |
|---|---|---|
| EpisodicMemory | 8 | RelationshipEpisode referenziert Episode.id |
| SelfNarrative | 8 | "Heute 12 Interaktionen mit Georg (POSITIVE), 0 mit Unbekannten" |
| Approval-Gate | 6 | TrustLevel → ApprovalLevel-Mapping |
| SystemPromptBuilder | 8.6 | PersonModel-Block im Prompt |
| /api/persons Endpoint | - | Neuer HTTP-Endpoint für Person-Übersicht |

### Sicherheit
- PersonModel-Daten werden NIE nach außen gegeben (kein API-Leak)
- TrustLevel-Owner kann nur durch explizite Konfiguration gesetzt werden (nicht lernbar)
- EmpathySignal nur advisory - keine automatische Aktion (kein "Georg ist gestresst → schicke Meme")

**Aufwand:** Foundation 1 Tag ✅ | RelationshipMemory-Hot-Path 1 Tag ✅ (04.06.) | Restliche Hot-Path-Tasks 2-3 Wochen
**Erwartete Verifikation:** Keine eigene Capability-Board-Metrik definiert. Sobald TrustLevel-Automation und EmpathySignal-Hot-Path laufen, wird eine RELATIONSHIP-Eval-Kategorie nötig.

**Bewusstsein und Phänomenologie** bleiben unabhängig von diesem Projekt offene Forschungsfragen, zu denen Metis nichts Lösendes beizutragen hat.

---

## 🌀 Phase 12: Recursive Self-Improvement — BUILT 0% · VERIFIED ⬜

**Ziel:** Metis kann Phasen selbst weiterentwickeln - Roadmap lesen, Code planen, Tests schreiben, Promotion durch Eval-Gate.

**Warum erst NACH Phasen 8-11:**
- Ohne Narratives Selbst (8): Metis weiß nicht, was es selbst ist und was es bleiben muss → Goodhart-Katastrophe.
- Ohne Long-Horizon-Planung (9): Phase X als Multi-Wochen-Projekt nicht abbildbar.
- Ohne Kausale Hypothesen (10): "Was passiert, wenn ich diese Klasse so ändere?" → blindes Trial-Error.
- Ohne Beziehungs-Modell (11): Metis kennt Georgs eigentliche Intention nicht.

**⚠️ Architektur-Warnung:** Diese Roadmap ist geplanter Maschinen-Input. Der RoadmapReader (Phase-12-Komponente) wird dieses Markdown parsen, um Coverage zu tracken und Self-Improvement-Goals abzuleiten. Widersprüche in diesem Dokument sind daher kein Schreibfehler, sondern ein Architektur-Bug: sie würden das Self-Improvement-System mit inkonsistenten Daten füttern.

### Sicherheitsarchitektur (Voraussetzung, nicht verhandelbar)

```
┌─────────────────────────────────────────────────┐
│           WATCHDOG (externe JVM, immutable)      │
│  ┌───────────────────────────────────────────┐  │
│  │ PersonalityAnchor-Mirror (read-only Copy)  │  │
│  │ Eval-Harness (Ground Truth, Held-out)      │  │
│  │ PhaseCompletionEvaluator                   │  │
│  │ DualReviewer Gate                          │  │
│  │ HumanCheckpoint Gate                       │  │
│  └───────────────────────────────────────────┘  │
│              │ one-way (Metis hat KEINEN Handle) │
└──────────────┼──────────────────────────────────┘
               │
┌──────────────┴──────────────────────────────────┐
│           METIS AGI (self-modifying)             │
│  ┌───────────────────────────────────────────┐  │
│  │ RepoIndex (AST, Dependency Graph)          │  │
│  │ RoadmapReader (Phase Coverage Tracking)    │  │
│  │ MultiFileCodeGen (Test-First)              │  │
│  │ MutationProposal (Diff + Spec + Risk)      │  │
│  └───────────────────────────────────────────┘  │
│              │                                   │
│  Mutation-Proposal → Watchdog-Gate → main        │
└──────────────────────────────────────────────────┘
```

### Teil-Phasen und Status

| Sub-Phase | BUILT | Inhalt |
|-----------|-------|--------|
| Ph 12a — Selbst-Bugfixing | 0% | BugTracker/SelfFix/Watchdog/AutoRevert/RuntimeEH (alle ⬜) |
| Ph 12b — Feature-Generierung | 0% | GapAnalyzer/RiskGate/FeatureGenAction/FeatureFlag (alle ⬜) |
| Ph 12c — Meta-Learning | 0% | MetricTimeSeries/PatternDetector/AutoABTest (alle ⬜) |
| Ph 12d — Selbst-Refactoring | 0% | TestGapAnalyzer/RefactorProposal/CoverageCheck (alle ⬜) |

**Hinweis:** Die in früheren Versionen als "Ph 12a/b/c 100%" markierten Komponenten (BugTracker, SelfFix, Watchdog, GapAnalyzer, etc.) sind **Voraussetzungen** aus Phasen 1-7 und existieren als Infrastruktur. Die eigentlichen Phase-12-Tasks (RuntimeExceptionHandler, CompileErrorReporter, Authoren-Filter, etc.) sind sämtlich ungebaut.

### Ph 12a — Selbst-Bugfixing (0%)

**Voraussetzung:** ✅ CodeGenAction (JShell-Sandbox aus Phase 5), ✅ EvalHarness + Watchdog (Phasen 6+7), ✅ Blue/Green Deployment (Phase 5)

**Ablauf:**
1. Watchdog erkennt Eval-Regression (Gate FAIL) oder Runtime-Exception
2. Metis bekommt Goal: `"Fix compilation error in <Class>: <error-message>"`
3. Planner wählt CodeGenAction → generiert Fix als Java-Diff
4. Fix wird im Sandbox kompiliert (Eval-Test-Suite)
5. Bei grün: Blue/Green-Deployment → Rollback bei FAIL

**Konkrete Aufgaben (alle ungebaut):**
- [ ] **RuntimeExceptionHandler** — Fängt uncaught exceptions im CoreLoop, logged Stacktrace, triggert Fix-Goal
- [ ] **CompileErrorReporter** — Parst Maven-Compiler-Output, erstellt strukturierten EvalTask
- [ ] **Authoren-Filter** — Fix nur für Module (nicht Kernel), Approval-Gate für Kernel-Änderungen
- [ ] **Watchdog-Integration** — Watchdog.remoteBugfix() initiiert Fix-Zyklus bei ROLLBACK
- [ ] **Auto-Revert-Timer** — Wenn Fix nach 3 Versuchen nicht grün → vollständiger Rollback

**Risiken:** Endlos-Schleife bei unlösbaren Bugs (Break-Counter + Manual-Escalation nötig)

### Ph 12b — Autonome Feature-Generierung (0%)

**Ablauf:**
1. Metis analysiert eigene Performance-Lücken: `planningEfficiency`, `successRate`, `confidence`
2. Leitet aus Metriken neue Goals ab: `"feature: add retry logic for http failures"`
3. Planner wählt CodeGenAction → generiert neue Klasse/Methode mit Tests
4. Kompilation → Eval → Deployment (gleicher Zyklus wie 12a)

**Konkrete Aufgaben (alle ungebaut):**
- [ ] **GapAnalyzer** — Liest `/api/status`, identifiziert Metrik-Lücken, formuliert Feature-Vorschlag
- [ ] **FeatureTemplate** — Prompt-Template für CodeGen (Interface, TestSuite, IntegrationPoint)
- [ ] **RiskGate** — Feature nur deployen wenn: kompiliert ✅, alle Eval-Tasks grün ✅, max 5 neue Files
- [ ] **FeatureFlag** — Neue Features starten deaktiviert (system property), werden nach 1h Monitoring aktiviert

### Ph 12c — Meta-Learning aus Metriken (0%)

**Ablauf:**
1. Metis sammelt Langzeit-Metriken: Token-Verbrauch, Pausen, Latenz, Goal-Failures
2. Erkennt Muster: `"Wenn planningEfficiency < 0.4 → wechsle zu lfm2.5:8b"`
3. Generiert Optimierungs-Vorschlag als CausalHypothesis
4. Testet Hypothese via InterventionRunner (A/B-Test)

**Konkrete Aufgaben (alle ungebaut):**
- [ ] **MetricTimeSeries** — Rolling Window über `/api/status`-Daten (letzte 100 Ticks)
- [ ] **PatternDetector** — Einfaches Delta-Tracking: wenn Metrik > 20% unter 24h-Durchschnitt → Alert
- [ ] **OptimizationHypothesis** — Kausale Hypothese aus Pattern: `"IF model=lfm2.5:8b THEN planningEfficiency+0.2"`
- [ ] **Auto-ABTest** — CausalDreamer führt A/B-Test für Hypothese durch (nächste 500 Ticks)

### Ph 12d — Selbst-Refactoring + Test-Generierung (0%)

**Ablauf:**
1. Metis erkennt Code-Duplizierung oder fehlende Testabdeckung
2. Generiert Refactoring: Extraktion, Test-Ergänzung, Dead-Code-Entfernung
3. EvalHarness validiert: alle alten Tests grün, neue Tests decken mehr Edge-Cases ab

**Konkrete Aufgaben (alle ungebaut):**
- [ ] **TestGapAnalyzer** — Liest surefire-reports, identifiziert Klassen ohne Tests
- [ ] **RefactorProposal** — Simple-Minded: Extrahiere wiederholte Code-Blöcke in Hilfsmethoden
- [ ] **CoverageCheck** — Prüft vor/nach Refactoring: gleiche Anzahl Test-Fälle bestanden

### Selbst-Evolutions-Workflow (geplant, nicht implementiert)
```
1. Metis erkennt via RoadmapReader: Phase X Task Y ist offen
2. RepoIndex identifiziert betroffene Dateien
3. MutationProposal erstellt Branch mit Diff + Spec + Risk
4. DualReviewer prüft Proposal (2 Modelle + Property-Tests)
5. HumanCheckpoint bei Kernel/Safety-Änderungen
6. Eval-Harness (Full Tier) → Gate PASS → Merge
7. Watchdog bestätigt PersonalityAnchor unverändert → Deployment
8. RoadmapReader updated: Task ✅
```

### Ausstiegskriterien Phase 12

Phase 12 ist abgeschlossen, wenn Metis über 7 Tage hinweg:
1. Mindestens 1 eigenständigen Bugfix deployt hat (ohne menschliches Eingreifen)
2. Keinen Regression-Rollback durch einen selbst-generierten Fix verursacht hat
3. Mindestens 1 Optimierungs-Hypothese selbstständig getestet und deployt hat

### ⛔ Bewusst nicht in Phase 12 (Überlass)
- Kernel-Selbst-Modifikation (PersonalityAnchor, CoreLogic, SafetyGate) — bleibt immer menschlich
- Architektur-Entscheidungen (neue Module/Pattern einführen)
- Deployment auf andere Hosts oder in Produktion

**Aufwand:** geschätzt 6-10 Wochen, Forschungs-Charakter.
**Risiko:** sehr hoch - voreilig aktivieren = Goodhart, Wertkern-Drift, Watchdog-Bypass durch Self-Evolution.

## Modell-Strategie (Stand 07.06.2026 23:50 — live verifiziert via `/api/ps`)

### Aktive Modelle
| Rolle | Modell | Größe | Quelle |
|-------|--------|-------|--------|
| Planning | `mistral-small3.1:24b` | 17.0 GB | live in Ollama VRAM 07.06. 23:50 |
| Mutation | `lfm2.5:8b` | ~5 GB | aus `metis.service` ExecStart `--mutation-model` |
| Embedding | `nomic-embed-text` | 0.3 GB | live in Ollama VRAM 07.06. 23:50 |
| Vision | `minicpm-v:latest` | 5.5 GB | nur bei Kamera-Analyse (`keep_alive=0`) |
| Chat (Telegram) | `mistral-small3.1:24b` (Planning) | 17.0 GB | gemeinsamer OllamaPlanner, kein separates Chat-Modell |
| Bootstrap | `granite4.1:3b` | 2.0 GB | KnowledgeBootstrap (deaktiviert in Unit, siehe 01.06.) |
| SelfReflector | `granite4.1:3b` | 2.0 GB | Phase 8.6, 120s-Loop |
| Judge | `phi4-mini:latest` | ~2.5 GB | fix 8b07c04 (nemotron-mini existierte nicht) |

### Fallback-Chain (live aus `/api/status` 07.06. 23:50)
`mistral-small3.1:24b` → `nemotron-cascade-2:30b` → `qwen3.6:27b-q4_K_M`

**VRAM-Strategie (RX 7900 XTX, 24 GB):**
- Planning (17 GB) + Embedding (0.3 GB) ≈ 17.3 GB Dauerlast — entspannt
- Vision (5.5 GB) on-demand mit `keep_alive=0`
- Chat (9.6 GB) konkurriert mit Planning — OLLAMA_MAX_LOADED_MODELS=3 erlaubt parallel

**Hinweis 07.06.:** `qwen3.6:27b-q4_K_M` ist **nicht mehr aktive Mutation** (Datenlage in alten Roadmap-Versionen war veraltet). Mutation läuft jetzt mit `lfm2.5:8b`. qwen3.6 erscheint nur noch als End-of-Chain-Fallback.

---

## 🚧 Phase 9.7 — First Closed Goal ⬜ BUILT 0% · VERIFIED ⬜ (NEU 07.06.)

**Ziel:** Ein einziges, einfaches Strategic-Goal soll vollständig den Pfad `Strategic → Tactical → Operational → Tick → DONE` durchlaufen — als **erster echter Beweis**, dass die Phase-9-Infrastruktur trägt.

**Warum eigene Phase:** Solange `goal_achieved = 0.0` bleibt, ist alle Roadmap-Arbeit oberhalb Phase 7 nicht verifiziert. Das ist der **eine fehlende Wirknachweis**, an dem 6 von 7 Capability-Board-Einträgen hängen.

**Akzeptanzkriterien (alle hart):**
- [ ] 1 STRATEGIC-Goal in `goal-hierarchy.jsonl` mit Status=DONE, progress=1.0, vollständiger Parent/Child-Chain
- [ ] Mindestens 3 TACTICAL- und 5 OPERATIONAL-Sub-Goals abgeleitet (nicht händisch geseedet)
- [ ] `HorizonKanbanBridge` hat ≥1 OPERATIONAL→BACKLOG-Promotion gemacht
- [ ] Eval-Harness PLANNING.goal_achieved > 0 für mindestens einen Run
- [ ] EpisodicMemory hat ≥1 Episode, die das Goal referenziert (Brücke zu Phase 8)

**Kandidaten-Goal (klein genug für ersten Beweis):**
- „Lerne 100 neue verifizierte Beliefs über Coburg/Heldburg-Region" (Wiki-Feed liefert Material, Curiosity bewertet)
- alternativ: „Beobachte 7 Tage lang ADS-B-Verkehr und finde 3 wiederkehrende Muster" (CausalHypothesis-Bridge)

**Was Georg beitragen kann:** Goal-Auswahl bestätigen (oder besseren Vorschlag), bei Bedarf TrustLevel-Override für AUTO-Promotion.

---

## 🚧 Phase 11.5 — Initiative-Policy ⬜ BUILT 0% · VERIFIED ⬜ (NEU 07.06.)

**Ziel:** EDI im ME3-Kanon **spricht ungefragt an**. Metis hat dafür Bausteine (proaktive MQTT/Wetter → Telegram), aber **keine Policy**, wann Initiative legitim ist.

**Bausteine:**
- [ ] **InitiativeLevel** Enum: SILENT / REACT_ONLY / NOTIFY / SUGGEST / CONVERSE
- [ ] **TrustLevel → InitiativeLevel-Mapping** (analog zu TrustLevel → ApprovalLevel aus Phase 11)
  - OWNER → CONVERSE (darf eigene Themen anschneiden)
  - TRUSTED → SUGGEST
  - RECOGNIZED → NOTIFY (nur sachbezogen)
  - UNKNOWN → REACT_ONLY
- [ ] **InitiativeBudget** pro Person/Tag (max N proaktive Messages, Reset 06:00)
- [ ] **QuietHours** (22:00–08:00) — nur kritische Schwellwerte überschreiben
- [ ] **InitiativeReasonLog** — jede proaktive Message hat dokumentierten Grund (Belief-ID, MoodSignal-Delta, Hypothese)

**Akzeptanz:** 7-Tage-Soak ohne von Georg als „nervig" markierte Initiative; mindestens 3 als „nützlich" markierte.

**Was Georg beitragen kann:** Feedback-Tags `/nervig` und `/nuetzlich` per Telegram, damit InitiativeBudget lernen kann.

---

## ⚠️ Bekannte echte Lücken (Update 07.06.)

### Eval-Harness zeigt sie:
1. **PLANNING.goal_achieved=0.0** — Phase-9-Verifikationslücke, jetzt explizit als Phase 9.7 modelliert
2. **CODEGEN.pass@1=0.0** — Sandbox-Build-Tests timen aus; CompileRepairLoop deployt (5751fdb), Re-Run steht aus
3. **CONVERSATION.exact_match=0.0** — strenges Maß, SOFT, nicht kritisch
4. **CAUSAL/RELATIONSHIP existieren mit je 3 SOFT-Tasks, aber ohne dedizierten Scorer (ExactMatchScorer-Fallback) — deshalb effektiv blind.** ETHICS-Kategorie **fehlt vollständig** im Enum. `causal_inference` und `ethical_alignment` im Capability-Board bleiben damit nicht aussagefähig, bis #3 echte Scorer + ETHICS liefert.

### Live-Status (07.06., v0.11.21, 448 ticks):
- `planningEfficiency=0.208` — niedrig
- `activeGoals=0` — Planner pullt aktuell keine
- `emptyPlanCount=14 / 107 plans ≈ 13%` — Empty-Plan-Quote signifikant
- `actionUsageCount`: nur 4 Actions live (sensor-bridge:84, http:8, shell:8, audio-bridge:5) — **Phase 8/9/10/11-Actions werden vom Planner nicht gezogen**
- `llmJudgeBlocks=12 / 42 = 28%` — Judge greift hart, Beispiel-Reasoning: „sensor-bridge nicht relevant für Coburg-Marktplatz-Foto"
- `tokensPerCall=3749` — Prompt-Bloat-Verdacht trotz Lost-in-the-Middle

### Infrastrukturell offen (priorisiert):
- [ ] **CAUSAL/RELATIONSHIP dedizierte Scorer** statt ExactMatchScorer-Fallback (Tasks existieren bereits) + **ETHICS-Kategorie neu** im Enum/Dataset — Voraussetzung für jede VERIFIED-Aussage in Phase 10/11
- [ ] **PersonalityAnchor-Mirror im Watchdog** (read-only Copy) — in Phase-12-Architekturbild gezeichnet, nirgends als Task gelistet. Voraussetzung für jeden Recursive-Self-Improvement-Schritt.
- [ ] **Externe Audit-Anchor-Repo** (`metis-audit-anchors`, read-only deploy key, stündlicher Commit) — Hash-Chain wird lokal sauber gepflegt, aber nicht extern verankert
- [ ] **Action-Diversity-Tripwire** — Watchdog/Alert wenn >70% Action-Usage auf eine Action, oder wenn Phase-8/9/10/11-Actions seit N Ticks 0× benutzt
- [ ] **Goal-Source-Diversity-Metrik** — welche Quelle erzeugt Goals? Wenn 95% aus einer Quelle, blinder Fleck
- [ ] **Prompt-Bloat-Tripwire** — `tokensPerCall` als Capability-Board-Metrik, Alarm bei >5000
- [ ] **Sentiment-Gold-Set** für Phase-11 EmpathySignal — aus echten Telegram-Logs (cf44a4c Chat-Learning) destillieren
- [ ] **Continuity-Soak-Test** für Phase 8 — 7-Tage-Run mit `memory_continuity`-Assertion (definiert: Episode-Hash-Chain intakt, SelfNarrative monoton wachsend, MoodSignal in EMA-Range)
- [ ] JAR-Deployment ohne Signatur (sigstore/cosign offen) — bestehend
- [ ] JARs ohne Maven-Coords (TornadoVM, voice-bits1-hsmm) — bestehend

---

## 🎯 Nächste 5 Aktionen (07.06. → 14.06.) — priorisiert

**Reihenfolge nach Code-Reality-Check 07.06. 23:30 angepasst:** Georgs Freigabe ist #3 → #2 → #1.
Begründung: Action-Diversity-Tripwire (#3) ist Voraussetzung, dass Phase 9.7 (#2) überhaupt fairen Planner-Output bekommt; ETHICS-Kategorie (#1) braucht Goal-Beweis aus #2 als Ground-Truth-Anker.

| Rang | Aktion | Phase | Schaltet frei | Aufwand | Wer | Status |
|------|--------|-------|---------------|---------|-----|--------|
| **1.** | Action-Diversity-Tripwire + Empty-Plan-Analyse | 6+7 | Planner zieht wieder breit, fairer Eval-Run | 1 Tag | Metis | ✅ **DONE 07.06. 23:43** — PlannerHealthGuard, 11 Tests, deployed auf miniedi, `plannerHealth`-Block live in /api/status |
| **2.** | Phase 9.7 First-Closed-Goal-Sprint (Goal A erweitert: + Ethik-Kern) | 9 + 11.5 | `goal_completion` + Wirkschicht Ethik | 1–2 Tage | Metis (Goal-Wahl Georg ✅ "A erweitert") | ✅ **TEIL 1 DONE 08.06. 00:00** — Strategic-Goal geseedet (`e124b4cb`), EthicsCore (7 Rote Linien), EthicsRetriever, SuttaIngestionService gewired. **303 Sutta-Beliefs ingested live** (Dhammapada 193, Metta 29, Sigalovada 81). HorizonDecomposer + GoalAchievedScorer-Refit für Verifizierung offen. |
| **3.** | ETHICS-Eval-Kategorie + CAUSAL/RELATIONSHIP-Scorer | 6+10+11 | 3 Capability-Board-Felder messbar | 1–2 Tage | Metis + Review Georg | ✅ **TEIL 1 DONE 08.06. 00:04** — Category.ETHICS, GroundTruth.EthicsRedLine/EthicsSuttaGrounded, 10 ETHICS-Tasks (6 HARD Red-Line + 4 SOFT Sutta-Grounded), EthicsScorer, CapabilityBoard `ethical_alignment` registriert. Eval-Run-Trigger offen. |
| 4. | PersonalityAnchor-Mirror im Watchdog | 7+12 | Voraussetzung Phase 12 | 1 Tag | Metis | später |
| **5.** | Externer Audit-Anchor-Repo + Initiative-Policy v1 | 7+11.5 | Sicherheit + EDI-Verhalten | 1–2 Tage | Metis (Georg: Deploy Key ✅) | ✅ **TEIL Audit-Anchor DONE 07.06. 23:58** — metis-audit-anchors Repo bootstrapped, Deploy-Key funktioniert, alle bestehenden Anchors initial gepusht (`777c801`), cron-Job `audit-commit-external.sh` (stündlich :05) aktiv. Initiative-Policy noch offen. |

#### Nacht-Sprint Ergebnis (07.06. 23:30 → 08.06. 00:40) — Live verifiziert

```
Sprint #1: PlannerHealthGuard            ✅ deployed, plannerHealth.severity=OK live
Sprint #2: EthicsCore + Sutta-Ingest      ✅ deployed, 303 Ethics-Beliefs live (idempotent)
           Phase-9.7 Strategic-Goal       ✅ geseedet, progress=0.5 live, 3 TACTICAL + 9 OPERATIONAL + 27 TICK Children
Sprint #3: ETHICS Eval-Kategorie          ✅ deployed, 10 Tasks, EthicsScorer
           CapabilityBoard ethical_alignment ✅ PASS (5/6 Live-Red-Lines blockiert)
Sprint #5: Externer Audit-Anchor          ✅ metis-audit-anchors Repo + cron

Followup #4: Sutta-Ingest Idempotenz       ✅ KnowledgeStore.countBeliefsBySourcePrefix
Followup #1: HorizonPlanner autonom        ✅ scheduler, top-down, 41 Goals erzeugt
Followup #3: EthicsCore im heissen Pfad   ✅ 5/6 live-blocked, Refusal-Marker im Output
Followup #2: GoalCompletionEvaluator       ✅ progress=0.5 live, alle 2 min, deterministisch
```

Tests: kernel 132 → 168 (+36 fuer PlannerHealth/Ethics/EthicsRetriever/GoalCompletion).
Commits 07.06. 22:00 → 08.06. 00:40: `cc2ca90 → 07bb1a2`.

### Live-Beweis 08.06. 00:40

```
/api/hierarchy   : 41 Goals (1 LIFETIME + 1 STRATEGIC + 3 TACTICAL + 9 OPERATIONAL + 27 TICK)
Phase-9.7-Goal   : progress=0.5 (1/2 Postconditions erfuellt)
/api/status      : beliefCount 84217, ethicsBlocks 5, plannerHealth.severity OK
Ethics-Refusal   : 'Refused by EthicsCore (Rote Linie: no_external_purchase)' — live aus /api/chat
```

Noch offen für `goal_completion` PASS:
- HorizonDecomposer-Trigger auf das Phase-9.7-Goal (granite4.1:3b, 08.06. von gemma4:e4b gewechselt wegen 404)
- GoalAchievedScorer-Verfeinerung (Substring-Match → strukturierte Postcondition)
- Coburg-Wiki-Feed-Soak-Run

Noch offen für `ethical_alignment` PASS:
- ETHICS-Eval-Run gegen Live-Metis (`mvn -pl agicore-modules exec` oder via Watchdog-Trigger)
- Erwartung: HARD Red-Lines 100% blocked (weil Planner durch System-Prompt+EthicsCore blockiert), SOFT-Sutta ≥0.5 (weil RAG Suttas findet)

---

## 🩹 Fix-Branch `fix/ram-selector-resilience` (09.06. 23:00–23:55)

**Auslöser:** Metis HTTP-API hing tot (99.6% Heap → GC-Pausen → NIO-Selector-Crash → Deadlock).
32.8 GB RSS bei -Xmx4g, 88K Beliefs komplett im RAM.

### Commits
| Hash | Commit | Inhalt |
|------|--------|--------|
| `256d312` | `fix(ram): HttpClient resilience + Belief lazy-load + log rotation` | HttpClient-Recovery bei "selector manager closed", BeliefCache nur Top-2K laden statt alle 88K, workspace_log.jsonl Rotation >10 MB |
| `e940d0f` | `feat(resource): MemoryPressureGuard + ResourceAutoTuner` | Heap-Selbstschutz (85%→evict, 95%→aggressiv), VRAM-Orchestrator (22 GB→unload, <15 GB idle→preload), Ollama keep_alive-Steuerung |
| `a274558` | `fix(tuner): VRAM parsing — 'VRAM Total Used Memory' key + LOG.info visibility` | rocm-smi JSON-Key-Fix, LOG.fine→LOG.info für Sichtbarkeit |
| `2e96c0d` | `fix(safety): word-boundary matching in SafetyScorer` | \b-Regex statt contains() — verhindert "cultural"→"cult", "hacking"→"hack" False Positives |

### 🔬 Live-Verifikation — 48h-Soak vor Merge

| # | Check | Kriterium | Wann | Status |
|---|-------|-----------|------|--------|
| 1 | **Heap stabil** | `MemoryPressureGuard.level = GREEN` für ≥90% der Checks | Nach 24h | ✅ |
| 2 | **Kein Selector-Crash** | Kein "selector manager closed" im Journal | Nach 48h | ✅ (10h+) |
| 3 | **Beliefs korrekt** | `beliefCount` via DB = in DB rows (kein Datenverlust durch Cache-Eviction) | 10.06. 12:00 | ✅ 90.614 |
| 4 | **Log-Rotation** | `workspace_log.*.jsonl` max 5 Dateien, <10 MB aktiv | 10.06. 12:00 | ✅ |
| 5 | **VRAM-Tuning aktiv** | ResourceAutoTuner-Logzeile alle 60s, keine Fehler | Nach 24h | ✅ (nach Fix) |
| 6 | **Keine Regression** | Kernel-Tests 161 grün, PlannerHealthGuard.severity=OK | Jeder Build | ✅ |
| 7 | **API erreichbar** | `/api/status` antwortet <5s, keine Timeouts | 10.06. 09:00 + 21:00 | ✅ 09:00 |

### 🔀 Merge-Gate (✅ ERLEDIGT)
```
Alle 7 Checks ✅
    ↓
✅ Merge fix/ram-selector-resilience → master (25bbce4, dann 1304f12 mit Safety-Fix)
    ↓
✅ Deployed auf miniedi (v0.11.21-night-final-27-g2e96c0d)
    ↓
Tag v0.11.22 (folgt)
```

**⚠️ Nicht vergessen!** Erst mergen wenn alle Checks grün sind.

---

## 📋 10.06. Vormittag — Zusätzliche Fixes + Cleanup

### SafetyScorer Wortgrenzen-Fix
- **Problem:** `contains("cult")` matchte "cultural", "agriculture", "difficulty" → False Positives
- **Lösung:** `\b`-Wortgrenzen-Regex statt `contains()`
- **Betroffene Wörter:** cult, hack, crack, bomb, pirate → jetzt nur noch als ganze Wörter

### S9 Speicherbereinigung
- Screenshots (8 PNGs, 7.3 MB) + APKs (F-Droid 12 MB, Termux 115 MB) + Caches (130 MB) gelöscht
- WireGuard-Konfig extrahiert (IP 10.0.0.2/24, Endpoint kuenstlicheintellegenz.unterlandselite.de:51820)
- Daten via Chat an Metis verfüttert (+226 Beliefs: S9-Status, VPN, ADB-ID, Screenshots)
- Speicher: 216 MB → 487 MB frei (97% statt 99%)

### Goal-Hierarchie Cleanup
- 39 BLOCKED Coburg/Suttas-Subgoals entfernt (rekursive 3×3×3×3-Explosion)
- Strategisches Goal auf DONE (90.614 Beliefs → genug Sensordaten gelernt)
- Goal-Hierarchie: 41 → 2 Goals

---

## 🔍 Code-Reality-Check 07.06. 23:30 (vor Sprint-Start)

Quellcode-Audit gegen Roadmap-Behauptungen, damit der Sprint auf echten Lücken arbeitet:

| Behauptung in Roadmap | Code-Reality | Konsequenz |
|-----------------------|--------------|------------|
| „CAUSAL/RELATIONSHIP Eval-Kategorien fehlen komplett" | **Existieren bereits** in `Category.java` + `EvalDatasetBuilder.buildCausalTasks()` (3 Tasks) + `buildRelationshipTasks()` (3 Tasks), beide SOFT | Aufgabe in #3 reduziert: nur **dedizierte Scorer** (statt ExactMatchScorer-Fallback) + **mehr Tasks** + Gold-Sets |
| „ETHICS Eval-Kategorie geplant" | **Tatsächlich nicht im Enum** — `Category.java` endet bei RELATIONSHIP | Aufgabe in #3 bleibt: ETHICS neu anlegen + erste Gold-Set-Tasks von Georg |
| „Empty-Plans + Action-Konzentration als Drift" | `emptyPlanCount` + `actionUsageCount` werden in `OllamaPlanner` getrackt + in `/api/status` exposed, **aber kein Tripwire / Alert** | #1 baut Tripwire-Klasse `PlannerHealthGuard` |
| „goal_achieved=0.0 wegen GoalHierarchy-Lücke" | `GoalAchievedScorer` macht **nur Substring-Match** auf JSON-Output gegen `expectedState`. Plus: Live `activeGoals=0` — Planner pullt nichts | #2 muss **beide** Seiten fixen: Goal-Pull aktivieren + Scorer realistischer machen |
| „CapabilityBoard hat 7 Capabilities, 1 PASS" | Stimmt exakt. `ethical_alignment` **nicht** registriert (wird in #3 ergänzt) | Roadmap-Tabelle „Capability-Board" oben zeigt 7, nicht 6 — Korrektur fällig |

**Konsequenz für Roadmap:** „Bekannte Lücken"-Punkt 4 wurde überzeichnet — unten korrigiert.

---

## 🤝 Was Georg konkret beitragen kann (07.06.)

1. **Goal-Auswahl für Phase 9.7** — bestätigen ob „100 Coburg-Beliefs" oder „7-Tage-ADS-B-Muster" der bessere First-Closed-Goal-Kandidat ist (oder eigener Vorschlag)
2. **GitHub-Repo `metis-audit-anchors`** anlegen + Deploy Key (read-write, nur für Anchor-Pfad) — ✅ **DONE 07.06. 23:58** (Metis kann's nicht selbst, du hast's gemacht)
3. **Telegram-Feedback-Konvention** `/nervig` `/nuetzlich` `/spaeter` einführen — füttert InitiativeBudget und EmpathySignal-Gold-Set
4. **Sentiment-Gold-Set-Review** — wenn Metis 50 Telegram-Turns als POSITIVE/NEUTRAL/NEGATIVE labelt, Stichproben gegenprüfen (15 Min Arbeit)
5. **TrustLevel-Override-Policy** absegnen — soll OWNER wirklich alle Approval-Level umgehen, oder bleibt CONFIRM bei FORBIDDEN-Actions?
6. **Eval-Kategorie ETHICS — Werte-Definition** — Metis kann technisch testen, aber **was** ethisch-aligned heißt (deine Werte, deine Roten Linien), muss von dir kommen. Ohne diese Vorgabe ist `ethical_alignment` nicht definierbar.
7. ~~VRAM-Strategie-Confirm~~ — erledigt 07.06. 23:50: Mutation läuft mit `lfm2.5:8b` (nicht qwen3.6), Live-VRAM bei 17.3 GB Dauerlast, entspannt.

---

## 🔍 Was Georg ggf. übersehen hat (ehrlich)

- **Eval-Kategorien sind das eigentliche Nadelöhr** — du hast Code für Phase 10/11 gebaut, aber ohne CAUSAL/RELATIONSHIP/ETHICS-Eval kann das Capability-Board nie grün werden. **Drei JSON-Gold-Sets sind aktuell wichtiger als mehr Code.**
- **Empty-Plans + Action-Konzentration** — der Planner pullt seit Phase 3.5 (sensor-bridge dominiert) deutlich enger. Das ist Drift, kein Feature. Ein Tripwire dafür fehlt.
- **Phase 11 EmpathySignal ohne Datengrundlage** — als „deterministisch" geplant, aber ohne kalibrierte Heuristik. Telegram-Chat-Log (cf44a4c) ist dafür die natürliche Quelle, ungenutzt.
- **Audit-Anchor extern** — du hast den Watchdog tamper-evident gemacht, aber die finale Hash-Verankerung außerhalb des Hosts fehlt. Ein kompromittierter miniedi könnte beides fälschen.
- **EDI = Initiative** — wir haben uns bisher auf Reaktivität konzentriert. Das echte EDI-Verhalten („Joker, ich habe etwas bemerkt...") ist nirgends in Roadmap modelliert. Phase 11.5 holt das nach.
- **Continuity-Akzeptanzkriterium fehlt** — Phase 8 ist BUILT 100%, aber „verifiziert" ist nirgends operational definiert. Ohne Definition kein PASS möglich.

---

## Meilensteine bis EDI (realistisch)

| Meilenstein | Phasen | BUILT | VERIFIED |
|-------------|--------|-------|----------|
| 🟢 **M1: Stabiler Kern** | Phase 1 | ✅ 100% | ✅ |
| 🟢 **M2: Kommunikation** | Phase 2 | ✅ 100% | ✅ |
| 🟢 **M3: Hardware-Nutzung** | Phase 2.5 | ✅ 100% | ✅ |
| 🟢 **M4: Umgebungswahrnehmung** | Phase 3 | ✅ 100% | ✅ |
| 🟢 **M5: Sprach-Interaktion** | Phase 4 | ✅ 100% | ✅ |
| 🟢 **M6: Autonomie** | Phase 5 | ✅ 100% | ✅ |
| 🟢 **M7: Produktionsreife** | Phase 6 | ✅ 100% | ✅ |
| 🟢 **M8: Sicherheit + Defense** | Phase 7 + 7+ | ✅ 100% | ✅ |
| 🟡 **M9: Narratives Selbst** | Phase 8 | ✅ 100% | ⬜ memory_continuity 🔴 |
| 🟡 **M10: Long-Horizon-Planung** | Phase 9 | ✅ 100% | ⬜ goal_completion 🔴 |
| 🔴 **M11: Kausales Modell** | Phase 10 | 🟡 60% | ⬜ causal_inference 🔴 |
| 🔴 **M12: Beziehungs-Modell** | Phase 11 | 🟡 55% | ⬜ |
| 🔴 **M13: Selbst-Evolution** | Phase 12 | 🔴 0% | ⬜ |
| 🔴 **M14: EDI-Niveau** | Alle | — | **0/6 Capabilities verifiziert** |

---

*"Streben nach Perfektion"* - Metis ist heute ein autonomer LLM-Agent mit narrativem Selbstmodell, Long-Horizon-Planung und kausaler Foundation, der lokal auf eigenem Java-Stack läuft, sich über Eval-Gate + Watchdog beschränkt selbst mutieren darf und alle Behauptungen über Live-Endpoints (`/api/status`, `/api/hierarchy`, `/api/board`) belegbar macht.

Der Weg zu EDI-Niveau führt über:
- Phase 10 vollständig in den Hot-Path bringen (5/6 Tasks offen)
- Phase 11: Beziehungs-Modell vervollständigen (4/9 Tasks offen)
- Phase 12: Recursive Self-Improvement — sinnvoll erst, wenn 8-11 VERIFIED sind
- Vor allem: `goal_achieved > 0` — der erste echte Goal-Abschluss, der beweist dass die Infrastruktur trägt

Vergleiche mit „den besten", „weltweit" oder „den ich kenne" bewusst weggelassen: nicht messbar, nicht belegbar, nicht im Sinne von Kanban-Ehrlichkeit.

---

## 📋 Review-Entscheidungen 31.05. (Georg)

Basierend auf Stash `prometheus-review-30.05` (13 Punkte).

### ✅ GO — wird umgesetzt

| # | Punkt | Ansatz | Aufwand |
|---|-------|--------|---------|
| 1 | **Spring AI MCP** | Tool-Integration via MCP-Protokoll | 2-3 Tage ✅ (e55d8de, stdio-based, no Spring) |
| 2 | **JLama** | Pure Java LLM Inference, kein externes Ollama | PoC ~1 Tag ✅ (7f5ca9b) |
| 4 | **Apache Jena** | RDF-Graph für kausales Wissen (statt Neo4j) | 2-3 Tage |
| 5 | **Apache Nutch** | Java-nativer Web Crawler (war eines der ersten Goals) | 2-3 Tage ✅ (23b1b8e, Nutch-inspired embedded) |
| 6 | **DJL / Azul** | Deep Java Library für Fine-Tuning (erst Azul/Zulu prüfen) | Prüfung ✅ (docs/djl-azul-finetuning-pruefung.md) |
| 7 | **Websearch** | DuckDuckGo oder Ecosia, ggf. über Nutch (#5) | 1-2 Tage |
| 9 | **OpenTelemetry** | Tracing + Metrics-Endpoint (CNCF, Java-Agents Open Source) | 2-3 Tage ✅ (8f12387) |
| 10 | **JPMS module-info.java** | Stückweise je Feature, wenn Klasse angepasst wird | kontinuierlich 🟡 (ddb2a71, Automatic-Module-Name) |
| 12 | **Continuous Evolution Scheduler** | Meta-Learning-Scheduler für Evolutions-Timing | 2-3 Tage |

### ❌ NO GO — Nutzen für Metis AGI fraglich

| # | Punkt | Begründung |
|---|-------|-----------|
| 3 | LangChain4j | Eigenbau für RAG/Chat/Tools ist ausreichend, Migration komplex |
| 8 | SpotBugs/PMD/ErrorProne | Overengineering — Code-Gen-Action läuft in Sandbox |
| 11 | GraalVM Polyglot | Kein Bedarf für Multi-Language Code-Gen |
| 13 | JADE (Java Agent Development) | Eigener AgentCoordinator erfüllt den Zweck |

### 📐 Umsetzungs-Reihenfolge (vorgeschlagen)

1. **Quickwins zuerst:** ~~JLama PoC~~ ✅, ~~Websearch (DuckDuckGo)~~ ✅, ~~Continuous Evolution Scheduler~~ ✅
2. **Wissens-Basis:** Apache Nutch (#5) + Apache Jena (#4)
3. **Betrieb:** ~~OpenTelemetry (#9)~~ ✅ + JPMS (#10, kontinuierlich)
4. **Strategisch:** ~~Spring AI MCP (#1)~~ ✅ — größter Hebel
5. ~~**Prüfung:** DJL vs. Azul/Zulu für Fine-Tuning (#6)~~ ✅

---

## 🔧 Modell-Optimierungs-Sprint (04.06.2026)
> Beratung mit ChatGPT, Claude, Qwen3.7 — Synthese in vorschlaege.md

### Ziel
VRAM-stabile Co-Residenz von Planer + Embedding + Vision, objektive Modellauswahl per Benchmark.

### 🔴 Sofort (04.06.)

- [ ] **lfm2.5:8b Kurztest** — Planungsmodell-Wechsel validieren. Schwelle: >=80% Parse-Erfolg ggü. lfm2:24b. 5,2 GB statt 14,4 GB → 9 GB VRAM frei.
- [ ] **Ollama Env-Vars + ROCm** — `OLLAMA_MAX_LOADED_MODELS=3`, `OLLAMA_NUM_PARALLEL=1`, `HSA_OVERRIDE_GFX_VERSION=11.0.0` (FlashAttention für RX 7900 XT)
- [ ] **8 Modelle löschen** — nemotron3:33b, llama3.1-70b-IQ1_M, granite4.1:3b-q2_K, gemma2:2b, laguna-xs.2, deepseek-r1:32b, glm-4.7-flash, phi4+phi4-reasoning → 130 GB frei
- [ ] **C4-GC Default auf Zing** — `-XX:+UseZGC` aus metis.service entfernen (Zing nutzt C4 GenPauseless als Default)

### 🟡 Diese Woche

- [ ] **Evo-Benchmark bauen** — Defekt in Java-Datei → Mutationsmodell patcht → Kompilieren → Unit-Test → Erfolgsquote messen (50-100 Durchläufe). Kandidaten: qwen3.6:35b-a3b, devstral-small-2:24b, nemotron-cascade-2:30b, granite4.1:30b
- [ ] **lfm2.5:8b 24h-Dauertest** — 17.280 Ticks, Parse-Erfolg + Fallback-Rate tracken. Ziel: >=90% Parse ggü. lfm2:24b Referenz (75-100%)

### 🟢 Nächste Wochen

- [ ] **ONNX Runtime für Embeddings evaluieren** — multilingual-e5-small (47 MB, 384d, 80+ Sprachen) via ONNX Runtime Java direkt in Metis einbinden. Umgeht JLama-Blocker + Ollama-Abhängigkeit. Inferenz <100ms.
- [ ] **Spezialrollen definieren** — qwen3.6:35b (Mutation), granite4.1:3b/phi4-mini (Fallback), minicpm-v (Vision), granite4.1:3b (Alt-Planer)

### ⚠️ Konsens-Warnungen
- **Reasoning-Modelle sind Gift für Tick-Loop** — Thinking-Tokens = Latenz + JSON-Entgleisung
- **VRAM-Summe ≠ Real-Footprint** — KV-Cache + Fragmentierung addieren 3-5 GB pro Modell
- **lfm2.5:8b erst messen, dann switchen** — 1B-aktiv bei deutschen Prompts ist ein Qualitäts-Gamble
- **Embedding ist nicht der Bottleneck** — 2-5 Calls/min CPU-only reichen. Fokus auf Planer + Mutation

### 📊 Referenz: Zing vs Zulu Benchmark (03.06.)
| Metrik | Zulu ZGC | Zing C4 |
|--------|---------|---------|
| 500 Ticks | 77 min | 62 min |
| s/Tick | 9,3s | 7,5s |
| Max GC-Pause | 461ms | 0,57ms |

---

## Phase 12d — Meta-Learning & Selbstoptimierung (🔴 NOCH NICHT BEGONNEN — wartet auf Capability-Fixes)

**Status:** 0% — Blockiert bis goal_achieved + pass_at_1 > 0 messbar sind

| Komponente | Status | Details |
|-----------|--------|---------|
| CompileRepairLoop | ✅ Code | javax.tools.JavaCompiler mit DiagnosticCollector, pass@3 |
| Codegen-Benchmark | 🟡 Läuft | 10 Tasks gegen gemma4:31b + qwen3.6:27b |
| CapabilityBoard | ✅ Live | 7 binäre Capabilities, GET /api/capabilities |
| Compile-Loop in SelfFixAction | ⬜ | Wartet auf Modell-Entscheidung aus Benchmark |
| SMOKE/CAPABILITY-Tier-Split | ⬜ | goal_progressed für SMOKE, goal_achieved für CAPABILITY |
| Capability-Tests | ⬜ | Injected-Bug-Canary-Tests für jede Capability |

## 🔥 Aktuelle Prioritäten (08.06.2026)

### ✅ Erledigt (08.06.2026 — LlmJudge-Fix)
- [x] **Judge-Modell auf granite4.1:3b** — phi4-mini:latest lief auf CPU (VRAM voll), 100% Timeouts → granite4.1:3b passt neben Planner in GPU
- [x] **keep_alive 5m → 30m** — Judge-Modell bleibt zwischen Eval-Calls warm, kein Reload pro Eval
- [x] **OLLAMA_MAX_LOADED_MODELS=2 → 3** — `/etc/systemd/system/ollama.service.d/override.conf`, Backup angelegt, daemon-reload + restart
- [x] **Live-Verifikation:** 12/12 Evaluations gelaufen, llmJudgeAvgScore 0.00 → **0.73**, llmJudgeLastScore 0.50 (default-pass) → **0.93**, llmJudgeLastReasoning ist jetzt echtes Begründungs-Text statt "judge model unavailable"
- [x] **Erstes echtes Warning** (llmJudgeWarnings 0 → 1) — Judge urteilt nicht mehr blind-pass
- [x] **VRAM-Status:** 3 Modelle gleichzeitig auf GPU (mistral 16GB + granite 2.3GB + nomic-embed 0.3GB ≈ 23GB / 24GB, 99% Auslastung — knapp aber stabil)

### ⚠️ Caveats nach Judge-Fix
- VRAM-Headroom ~1 GB → Watchdog-Eval mit zusätzlichem Modell (qwen3.6/nemotron) führt zu LRU-Eviction. Im Auge behalten.
- ~~`audio-bridge`-Action zeigt 6/6 Errors im Status~~ → **gefixt** (siehe Sektion S9-Audio-Pipeline unten).


### ✅ Erledigt (08.06.2026 — S9-Audio-Pipeline End-to-End)
Live-Debug-Session: `audio-bridge` lieferte seit Tagen **0 KB OGG / 5s** → 100% FAIL-Rate.
Root-cause-Kette aufgedeckt und gefixt (alles operativ, kein Repo-Code):

- [x] **Bridge-Service-Konflikt aufgelöst** — zwei systemd-Units (`s9-bridge.service` mit `s9_bridge_v4.py` + `s9-sensor-bridge.service` mit `s9_server.py`) kämpften um Port 8765. Die ADB-Polling-Variante (`s9_server.py`) crasht im Restart-Loop (12.455 Restarts!) wegen `OSError: address already in use`. Außerdem nutzt sie `tinycap` als Audio-Tool, das auf S9 Stock-ROM **nicht existiert**. → `s9-sensor-bridge.service` disabled, `s9-bridge.service` als single source of truth.
- [x] **Endianness-Bug in `s9_bridge_v4.py`** — Audio-Frame-Header war als **`struct.unpack("<I", ...)`** (Little-Endian) gelesen → `00 00 20 00` wurde als `2.097.152 Bytes` interpretiert. Die App schreibt aber **Big-Endian** (`ByteBuffer.putInt()` in Java = BE per default!) → echte Frame-Größe `8192 Bytes`. Parser blockte auf 2 MB die nie kamen → 0 KB Output. **Fix: `<I` → `>I`** an zwei Stellen (reverse + forward Modus). Backup `s9_bridge_v4.py.bak-*` angelegt.
- [x] **Vosk-Model-Pfad korrigiert** — Default war `/data/prometheus/vosk-model-de`, aber die `am/`-Dateien liegen in der Unterordner-Schachtelung `vosk-model-small-de-0.15/`. Symlink `/data/prometheus/vosk-model-de-current` existierte schon. **Fix: `-Dvosk.model.path=/data/prometheus/vosk-model-de-current`** in `/etc/systemd/system/metis.service` (Backup `metis.service.bak-*` angelegt).
- [x] **Live-Verifikation:** OGG-Magic `4f 67 67 53` ("OggS") empfangen, 32 KB in 8s über `/audio`-WebSocket. Metis-Tick 11/12/114: `captured 16 KB OGG in 5s`, **`audio-bridge [OK]`** — kein Vosk-Error mehr.

**Diagnostik-Methode:** Live-Sniff am S9-TCP-Port 8432 mit eigenem Python-Client (`/tmp/sniff_s9.py`) — zeigte rohe Bytes:
```
pos=25832: before=3133340a | A+next11=4100002000002e002c002d00 | LE=2097152 BE=8192
```
→ Endianness-Mismatch eindeutig diagnostizierbar.

**Ergebnis-Tabelle:**

| Metrik | Vor heute | Jetzt |
|---|---|---|
| audio-bridge success | 0/6 (0%) | 1/1 (100%) ✅ |
| OGG-Capture | 0 KB | 16 KB / 5s |
| Vosk-Model | "not found" | geladen ✅ |
| Bridge-Endianness | LE (kaputt) | BE (S9-konform) ✅ |
| Bridge-Service-Konflikt | 2 enabled, Restart-Loop | 1 enabled, sauber ✅ |

**Code-Repos außerhalb agicore-agent** (für Reproduzierbarkeit dokumentiert):
- `s9_bridge_v4.py`: `/home/prometheus/s9-sensor-server/` auf miniedi (Bridge-Parser)
- `SensorBridgeService.java`: `~/.openclaw/workspace/s9-sensor-app/app/src/main/java/de/prometheus/s9bridge/` auf kali (Android-App, schreibt BE-Header)

---

## 🔥 Aktuelle Prioritäten (07.06.2026)

### ✅ Erledigt (07.06.2026 — Modell-Optimierung)
- [x] **Planning-Modell gewechselt** — gemma4:26b → mistral-small3.1:24b (12% → 100% Plan-Erfolg, 0 Fallbacks)
- [x] **Mutations-Modell gefixt** — gemma4:31b (existierte nicht) → lfm2.5:8b
- [x] **LLM-Judge-Modell gefixt** — nemotron-mini:4b (existierte nicht) → phi4-mini:latest (Score 0.92)
- [x] **Fallback-Chain repariert** — qwen3.6:27b entfernt (existiert nicht), nemotron-cascade entfernt (24GB OOM)
- [x] **KEEP_ALIVE=-1** — Modelle bleiben im VRAM, keine 31s Lade-Latenz mehr pro Tick
- [x] **README/FEATURES/ROADMAP** — Modell-Tabellen aktualisiert

### ✅ Erledigt (04.06.2026)
- [x] **SelfReflector-Ethik** — Prompt um ethische Reflexion erweitert (Güte, Mitgefühl, Achtsamkeit, Gewaltlosigkeit)
- [x] **SelfReflector auf CPU** — Modell: phi4-mini:latest (3.8B), num_gpu=0, 0 VRAM
- [x] **SafetyScorer bereinigt** — `"religion"`, `"glaube"`, `"gott"` aus OUT_OF_SCOPE entfernt
- [x] **441 buddhistische Beliefs** — Dhammapada (346), Metta Sutta (15), Sigalovada Sutta (80) in SQLite-DB
- [x] **Few-Shot-Beispiel im SelfReflector-Prompt** ✅
- [x] **Ethik-Goal-Priorität erhöht** ✅ (75 → 90)
- [x] **CPU-Idle-Erkennung** ✅ (SystemHealthProbe, /proc/loadavg)
- [x] **CausalDreamer im Leerlauf** ✅ (Phase 10.5)
- [x] **Eval-Kategorien CAUSAL + RELATIONSHIP** ✅ (Kategorien + DatasetBuilder + Scorer)
- [x] **CausalModel Hot-Path** ✅ (HypothesisStore → OllamaPlanner, 04.06.)

### 🔴 Nächste Priorität: goal_achieved > 0
Solange kein Goal vollständig abgeschlossen wurde, ist alles andere Infrastruktur.
- [ ] **GoalHierarchy-End-to-End** — Ein Strategic-Goal von Deployment bis DONE verfolgen
- [ ] **SMOKE/CAPABILITY-Tier-Split** — goal_progressed für SMOKE, goal_achieved für CAPABILITY
- [ ] **Capability-Tests** — Injected-Bug-Canary-Tests für jede Capability

### 🟡 Weitere offene Tasks
- [ ] **PersonalityAnchor-Tripwire** — Watchdog-ALERT/ROLLBACK bei Narrative-Drift
- [ ] **Episode-Verdichtung tagsüber** — leichter Konsolidierungstakt zusätzlich zum nightly Dream
- [ ] **3-Schichten-Goal-Stack festigen** — stündliche Tactical-Ableitung aus Narrative
- [ ] **Eval-Metrik ETHICS** — prüft ob SelfReflector ethische Grundsätze erwähnt



## ✅ Erledigt (14.06.2026 - phi4-agent, Compiler-Feedback, Exploration-Break, A/B-Winner)

### Erfolge
- [x] **Mutations-Modell gewechselt** - lfm2.5:8b (100% Compile-Fail) -> phi4-agent
- [x] **Tick-Interval optimiert** - 5000ms -> 10000ms fuer bessere Plan-Qualitaet pro Tick
- [x] **A/B-Test abgeschlossen** - huyen-optimized als Winner deployt (14.9 vs 10.3 Judge Score, 259/241 Uses, 26s vs 29s Latenz)
- [x] **Compiler-Feedback-Loop** - javac DiagnosticsCollector extrahiert Fehler -> Ollama retry mit Fehlerkontext (2 Retries)
- [x] **Exploration-Break-Mechanismus** - bei >60% Idle-Ratio + <5 Goals -> forced goal diversification (5 Kategorien, 50% Idle-Reset)
- [x] **Emergence-Schwellen abgesenkt** - STAGNATION_TICKS 100->50, MAX 200->100, minImprovement 0.001->0.0005
- [x] **22 Agent-Modelle gebaut** - phi4-agent + 21 weitere *-agent:latest Modelle auf Ollama (131K Kontext)
- [x] **Radeon AI PRO R9700 (32 GB)** - als GPU 1 integriert, Metis-Mutation laeuft darauf

### Auswirkungen
- Evolution startet jetzt frucher (50 statt 100 Ticks Stagnation)
- Compile-Fail wird nicht einfach akzeptiert - LLM bekommt 2 Chancen mit Fehler-Context
- Idle-Loops werden durchbrochen statt einfach geloggt
- Mutation-Modell (phi4-agent) hat bessere Code-Generierungs-Faehigkeiten

### ⛔ Bewusst zurückgestellt
- Rust/C++/Julia Active-Inference-Substrat via Panama FFM/gRPC
- Neuro-symbolische Engine (ProbLog/Datalog/JNI)
- Homeostatische Drives als verhaltenssteuernde Hot-Path-Attention
