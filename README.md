# 🧠 Metis — Self-Evolving AGI

**Metis** ist eine selbst-evolvierende, lokal laufende Java-AGI auf JDK 25 (Zulu). Benannt nach der Titanin der Weisheit aus der griechischen Mythologie.

Sie denkt in kognitiven Zyklen (Perceive → Plan → Execute → Observe → Learn), chattet via Telegram (@metis_agi_bot), sieht durch Kameras (minicpm-v), lernt aus Wikipedia (Curiosity-gesteuert + Bulk-Feed), und kann unter Eval-Gate + Watchdog-Approval beschränkt eigenen Code mutieren. Ein externer Watchdog läuft als separate JVM, schreibt ein SHA-256-Hash-Chain-Audit-Log (tamper-evident, **nicht** kryptografisch signiert) und kann ROLLBACK/HALT/ALERT/PRUNE auslösen.

## Status

**Stand: 04.06.2026 17:30 · **Tests: 330 grün (54 Kernel + 276 Modules)** · **CI:** Kernel + Watchdog (GitHub Actions, Zulu 25)
**JVM:** Zing 26.04 C4 GenPauseless (27% schneller als Zulu ZGC) · **Benchmark:** `bench-zing-vs-zulu-20260603` · **Empfehlung:** Zing für Produktion

**Safety:** SafetyScorer bereinigt (religion/glaube/gott raus) · **Wissen:** 441 buddhistische Beliefs (Dhammapada, Metta Sutta, Sigalovada) in SQLite-DB · **Ethik:** SelfReflector auf phi4-mini:latest CPU (0 VRAM, Temp 0.7, keep_alive=5m) + ethisches Goal in AgentMain
**Chat:** Option B — OpenClaw beantwortet Telegram-Chats direkt, Metis macht Agent-Arbeit (Kanban-Integration fuer eingehende Nachrichten)
**Buecher:** BookIngestionService — PDF/EPUB→Text→Chunks→Beliefs→Kanban-Goals (--book-dir)
**Chat:** Option B — OpenClaw beantwortet Telegram-Chats direkt, Metis macht Agent-Arbeit (Kanban-Integration für eingehende Nachrichten)
**Bücher:** BookIngestionService — PDF/EPUB→Text→Chunks→Beliefs→Kanban-Goals (--book-dir)

| Phase | Status | Key Facts |
|-------|--------|-----------|
| 1-7+ | ✅ 100% | Stabiler autonomer Agent |
| 8 | ✅ 100% | SelfReflector + PersonalityTripwire |
| 9 | ✅ 100% | Long-Horizon-Planung + CommitmentGuard |
| 10 | ✅ 100% | CausalModel + Counterfactual + InterventionRunner |
| 11 | ✅ 100% | PersonModel + TrustLevel + EmpathySignal |
| 12a | ✅ 100% | BugTracker + SelfFixAction + Watchdog + AutoRevert + EvalReportGenerator |
| 12b | ✅ 100% | GapAnalyzer + RiskGate + FeatureGenAction + FeatureFlag |
| 12c | ✅ 100% | MetricTimeSeries + PatternDetector + AutoABTest |
| Gov | ✅ 100% | FeatureBranchManager + 3-Stufen-RiskGate (ALLOW/PR_REQUIRED/DENY) |
→ Details: **[FEATURES.md](FEATURES.md)** · **[AGI_EDI_ROADMAP.md](AGI_EDI_ROADMAP.md)** · **[RUNBOOK.md](RUNBOOK.md)**

### 🔬 Zing vs Zulu Benchmark (03.06.2026)
| Metrik | Zulu ZGC | Zing C4 |
|--------|---------|---------|
| 500 Ticks (60+ min) | 77 min | **62 min** |
| s/Tick | 9,3s | **7,5s** |
| Max GC-Pause | 461ms | **0,57ms** |
| Success Rate | 100% | 100% |

Zing 27% schneller, C4 pauslos. Monitoring via `-XX:+PrintCPUUtilization -XX:+UseZingMXBeans`.

### 🔧 Embedding-Resilienz (04.06.)
- Circuit-Toleranz: 5→20 consecutive 503s, Cooldown 60s→120s
- Ollama `num_gpu=0` für Embeddings (CPU-only)
- nomic-embed-text mit `keep_alive=-1` vorgeladen
- JLama 3-Stufen-Fallback (multilingual-e5→bge-small→Ollama) — Code steht, blockiert auf JLama 0.8.4 (Issue #202)

### 📋 Nächster Sprint: [Modell-Optimierung](#🔧-Modell-Optimierungs-Sprint-04062026)

### Tag-Linie (30./31.05.2026, chronologisch)
| Tag | Inhalt | Tests bei Tag |
|---|---|---|
| `v0.11.21-night-final` | Phasen 1–7 abgeschlossen | 1 |
| `v0.2.1-hardened` | CI + Embedding-LRU + Java 25 + Input-Guard | 21 |
| `v0.3.0-agi-push` | Multi-Modal-Memory + Loom-Vision + Subprocess-Isolation + Audit-Anchor | 23 |
| `v0.3.1-observability` | Locale-Fix + Wiki-Persistence + git-cwd-Fix + Wiki-Loom | 25 |
| `v0.3.2-feed-hardening` | WAL-Mode + atomic State + Lock-Retry + Wiki-Backup auf GitHub | 25 |
| `v0.3.3-defense-in-depth` | Telegram-Loom + Telegram Input/Output-Safety-Guards | 27 |
| `v0.4.0-phase8-foundation` | EpisodicMemory + SelfNarrative + MoodSignal + PersonalityAnchor + DreamConsolidation | — |
| `v0.4.1-phase8-complete` | SystemPromptBuilder + LlmDreamSummarizer + Phase-12-Outlook | — |
| `v0.5.0-phase9-long-horizon` | GoalHierarchy + HorizonPlanner + CommitmentRegister + GoalRevisionEngine | — |
| `v0.5.1-phase9-complete` | LLM-Decomposer + Horizon→Kanban-Bridge | — |
| `v0.6.0-phase10-causal` | Active Causal Hypotheses Foundation (Record + Store + Generator + Intervention + Counterfactual) | **73** (lokal) |
| `v0.6.1-honesty-audit` | Honesty-Audit + CI-Konfig (Kernel+Watchdog) + Maven-Profil miniedi | **73** (lokal) |
| `6b5fb44` (post-v0.6.1) | WIP-aware LLM-as-Judge (`KanbanBoard.tryAcquireAdHocSlot`) — Judge-Calls ins INFERENCE-Bookkeeping | **80** (Kernel) |
| `v0.7.0-cognitive-selfreflector` | SelfReflector (granite4.1:3b, 120s-Loop), CommitmentGuard, Phase 9.5 HARD-Commitment-Wächter | **105** |
| `v0.7.1-phase11-personmodel` | Phase 11 PersonModel: Person/PersonStore/TrustLevel/RelationshipMemory/EmpathySignal | **105** |
| `v0.8.2-phase10-hotpath` | Phase 10 Hot-Path: CausalHypotheses im Planning-Prompt, 44 Hypothesen | **134** |
| `v0.8.3-phase10-11-finish` | Phase 10+11 abgeschlossen: CAUSAL/RELATIONSHIP Eval-Kategorien, Roadmap konsolidiert | **134** |
| `v0.7.2-phase11-wired` | SystemPromptBuilder (Gesprächspartner-Block), Approval-Gate (TrustLevel→maxAutoApproval), HTTP+Telegram PersonStore-Pflege | **112** |
| `v0.7.3-prompt-tightening` | System-Prompt-Tightening (CAPS, 1-Satz, genaue Action-Namen, OK/NO-OK statt vage) | **112** |
| `v0.7.4-personality-tripwire` | PersonalityTripwire: Drift-Detection alle 5 min, SHA-256-Pin vs Live-Anchor, ROLE_VIOLATION/TONE_SHIFT/CORE_ERASURE | **112** |
| `v0.7.5-causal-dreamer` | CausalDreamer: Idle-Guard (WIP<2), Overflow-Schutz, zufällige Experience → Hypothese, SelfNarrative-CausalDream-Eintrag | **134** (112 K + 22 M) |
| `v0.7.6-embedding-backoff` | 503-Fix: Embedding-Backoff (1s/2s/4s), NUM_PARALLEL 4→8, embedding503s-Metrik in /api/status | **134** (112 K + 22 M) |
| `v0.7.7` | VideoAnalysisAction + HTTP-Resilienz + 5 Coburg-Webcams (Videoframes), SafetyScrub, Buddhist-Beliefs (441) | **134** (112 K + 22 M) |
| `v0.7.8` | GermanLanguageGuard (Code-Switching, Umlaute, Anrede, Anglizismen), Ethic-SelfReflector (phi4-mini CPU, Ethik-Goal Prio 90, Few-Shot), CPU-Idle-Erkennung | **134** (112 K + 22 M) |
| `v0.8.0` | **Option B Chat-Architektur + BookIngestionService**: TelegramBot→Kanban+ACK, OpenClaw antwortet, PDF/EPUB→Beliefs, Ollama-GPU-Analyse | **134** (112 K + 22 M) |
| `v0.7.9` | **Embedding-Circuit-Breaker**: 5×503 → 60s Cooldown (verhindert Queue-Überflutung). Neue Metriken: embeddingCircuitOpen/Trips/Consecutive503s/RequestsSkipped | **134** (112 K + 22 M) |

> Die früheren Test-Zahlen sind aus den jeweiligen Commits übernommen und nicht rückwirkend nachgemessen. Aktuell, gegen Master per `mvn test`: **112 Kernel + 22 Modules = 134 Tests grün**.

## Architektur

```
┌──────────────────────────────────────────────────────────────────┐
│                        Metis AGI                                 │
│                                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐   │
│  │  Kernel      │  │  Modules     │  │  Watchdog (R/O JVM)  │   │
│  │  (immutable) │  │  (evolvable) │  │                      │   │
│  │              │  │              │  │  • HALT/ROLLBACK     │   │
│  │ • CoreLoop   │  │ • Planner    │  │  • ALERT/PRUNE       │   │
│  │ • WorldModel │  │ • EvalHarness│  │  • Audit-Log SHA-256 │   │
│  │ • SafetyGuard│  │ • ModelReg.  │  │  • Hourly Anchors    │   │
│  │ • SelfModel  │  │ • 31 Actions │  │  • Health-Monitor    │   │
│  │ • CausalModel│  │ • Kanban     │  └──────────────────────┘   │
│  └──────────────┘  └──────────────┘                              │
│                                                                  │
│  HTTP-API (Port 11735) ← OpenWebUI, curl, Health-Checks          │
│  Telegram Bot       ← @metis_agi_bot (per-message Virtual Threads)│
│  Camera Vision      ← minicpm-v (parallel Loom, persistente JPEGs)│
│  Wikipedia Lerner   ← Curiosity-gesteuert (Loom-Worker)          │
│  Wikipedia Feed     ← Bulk-Cron (5163 Artikel, WAL-safe)         │
│  Speech-Loop        ← Piper TTS → Vosk STT (~5% der Artikel)     │
│  Java Lerner        ← Zulu JDK 25 Exploration (alle 15 Min)      │
└──────────────────────────────────────────────────────────────────┘
```

- **Global Workspace Theory** nach Baars: Attention-Bottleneck (Miller's Law), CompetitiveSelector
- **OllamaPlanner:** CoT 4-Schritt (ANALYZE→MATCH→CHECK→DECIDE), 10 Few-Shot, 3-Tier-Fallback
- **WorldModel:** Belief-Store mit HybridSearch (BM25+Cosinus), PersistentVectorIndex, WAL-Mode. Aktueller Stand über `/api/status -> beliefCount` (Snapshot 31.05. 02:00: 32.897).
- **Eval-Harness:** 6 Kategorien (Planning, Retrieval, Codegen, Conversation, Safety, Performance), 3-Tier (SMOKE/FULL/EXTENDED). **Ehrlicher Live-Status:** `llmJudgeLastReasoning="judge model unavailable (non-blocking)"`, `llmJudgeAvgScore=0.00`. Die Gate-Logik läuft, die LLM-Judge-Pipeline antwortet aktuell nicht zuverlässig im Timeout — seit dem WIP-aware-Judge-Patch (31.05.) wird der Plan in dem Fall **durchgelassen statt geblockt**, sodass keine Hardware-Überlast mehr entsteht. Promotion hängt damit vor allem an deterministischen Smoke-Tests.
- **Watchdog:** Separate JVM, Heartbeat-Check (5s), SHA-256 Hash-Chain, stündliche externe Anchors
- **Kanban Board:** 4 Columns (BACKLOG→READY→IN_PROGRESS→DONE), WIP-Limits pro ResourceType; seit 31.05. zusätzlich **Ad-hoc-Slots** (`tryAcquireAdHocSlot(ResourceType)`) für kurzlebige Inference-Konsumenten (z. B. LLM-as-Judge), die dasselbe WIP-Limit teilen — verhindert versteckte Hardware-Überlast jenseits der Goal-Buchhaltung
- **Defense-in-Depth:** Input-Safety-Guard + Output-Safety-Guard auf HTTP- und Telegram-Pfad

## Schnellstart

```bash
git clone https://github.com/theWatcherNineteen83/agicore-agent.git
cd agicore-agent
mvn -B verify   # 80 Tests im Kernel, SBOM (CycloneDX) wird mitgebaut
java -jar agicore-modules/target/metis-agent.jar \
  --api-port 11735 \
  --evolution \
  --kanban
```

### Telegram-Bot

Metis antwortet unter [@metis_agi_bot](https://t.me/metis_agi_bot) — Deutsch, faktisch, mit Zugriff auf Wetter, HA, Kameras und Wikipedia-Wissen. Jede Nachricht läuft auf eigenem Virtual Thread, durchläuft Input- + Output-Safety-Guard.

### OpenWebUI-Integration

```
OpenWebUI → Verbindungen → Neue Ollama-Verbindung
URL: http://<host>:11735
```

## CLI-Referenz

| Flag | Beschreibung |
|------|-------------|
| `--api-port N` | HTTP-API Port (default: 11735) |
| `--interval N` | Tick-Intervall in ms (default: 5000) |
| `--evolution` | Self-Evolution aktivieren |
| `--kanban` | Kanban Goal Board (WIP-Limits, Pull-System) |
| `--kernel-evolution` | Kernel + Module Evolution |
| `--bootstrap-models A,B` | Consensus-Bootstrap-Modelle |
| `--planning-model M` | Planungs-Modell überschreiben |
| `--mutation-model M` | Mutations-Modell überschreiben |
| `--embedding-model M` | Embedding-Modell überschreiben |
| `--persist PATH` | Agent-Status als JSON speichern |
| `--telegram-token T` | Telegram-Bot-Token |

### JVM-System-Properties (optional)

| Property | Default | Zweck |
|---|---|---|
| `metis.repo.dir` | `/home/prometheus/metis-agent-repo` | Git-Repo-Pfad für Commit-Detection im Eval-Report |
| `metis.snapshot.root` | `data/snapshots` | Wo Kamera-JPEGs persistiert werden |
| `metis.wiki.knowledge.state` | `/home/prometheus/metis/wiki-knowledge-state.json` | Curiosity-Wiki-Lerner State |
| `metis.audit.anchor.dir` | `/home/prometheus/metis/audit-anchors` | Watchdog schreibt stündlich Hash-Anchors |

## HTTP-API

| Endpoint | Beschreibung |
|----------|-------------|
| `GET /api/status` | Agent-Metriken (Ticks, Success, Beliefs, **Embedding-Cache-Stats**, Validator-Counter) |
| `POST /api/chat` | Chat mit EDI-Persona (Input/Output-Guard, OpenWebUI-kompatibel) |
| `GET /api/tags` | Verfügbare Ollama-Modelle |
| `POST /api/show` | Model-Info |
| `GET /api/learned` | Gelernte Beliefs + Experiences |
| `GET /api/conversations` | Chat-Sessions (SQLite) |
| `GET /api/agents` | Multi-Agent-Status |
| `POST /api/admin/prune` | Modell aus Registry entfernen |
| `POST /api/admin/refresh-models` | Ollama-Modelle live aktualisieren |
| `/api/board` | Kanban-Board Live-View (Spalten, WIP, Flow-Metriken) |
| `/api/hierarchy` | Long-Horizon-Goals (Phase 9): id, horizon, status, progress, deadline, owner |

## Modell-Strategie

| Rolle | Modell | Größe |
|-------|--------|-------|
| Planning | `lfm2:24b` | 15.5 GB |
| Mutation | `qwen3.6:27b-q4_K_M` | 17.4 GB |
| Embedding | `nomic-embed-text` | 0.3 GB |
| Chat (Telegram) | `gemma4:e4b` | 9.6 GB |
| Vision (Kameras) | `minicpm-v:latest` | 5.5 GB |
| SelfReflector | `granite4.1:3b` | 2.0 GB |
| Bootstrap | `granite4.1:3b` / `llama3.2:3b` | 2.0 GB |
| Fallback-Chain | mistral-small3.1 → qwen3.6:27b-q4_K_M → phi4 → lfm2:24b | — |

**VRAM-Strategie (RX 7900 XTX, 24 GB):** Planner + Embedding ≈ 16 GB Dauerlast. Chat/Vision/Facts mit `keep_alive=0` — sofort entladen.

## Hardware

| Komponente | Spec |
|---|---|
| CPU | AMD Ryzen 7 5700G (8C/16T) |
| RAM | 62 GB DDR4 |
| GPU | Radeon RX 7900 XTX (24 GB VRAM) |
| OS | Ubuntu 24.04 LTS |
| Java | Zulu 25.0.2 (LTS) |
| Inferenz | Ollama (22+ Modelle) |

## Deployment

Metis läuft auf `miniedi` als systemd-Service (`metis.service`)
aus `/home/prometheus/metis/metis-agent.jar` (`-Xmx2g`, ZGC, Zulu 25).
Neustart: `echo "<pw>" | sudo -S systemctl restart metis.service`.
Der Watchdog läuft als getrennte User-Unit `metis-watchdog.service`.

```bash
# Prozess-Status
pgrep -af metis-agent.jar
ss -tlnp | grep -E '11735|11736'

# Health-Check (einziger zuverlässiger Endpoint)
curl -s http://localhost:11735/api/status | head -c 800
#   /status liefert 404 — nicht verwenden.

# Watchdog (separater Java-Prozess, eigene User-systemd-Unit)
systemctl --user status metis-watchdog.service

# Modelle live aktualisieren
curl -X POST http://localhost:11735/api/admin/refresh-models

# Backup auf GitHub (alle 6h, manuell triggerbar)
bash /home/prometheus/metis/backup-config.sh
```

## Betrieb

- **Health-Monitoring:** Cron alle 5 Min → Telegram-Alert bei Anomalien
- **Config-Backup:** Alle 6h systemd-Units + Wiki-States + Audit-Hash-Head → GitHub `config-backup/`
- **Watchdog:** HALT bei Heartbeat-Verlust, ROLLBACK bei Eval-Regression, stündliche Anchors
- **Wiki-Feed:** Cron-Job `metis-wiki-feed` (10 Min, 30 Artikel/Batch). Live-Fortschritt in `/home/prometheus/metis/wiki-feed-state.json` (Snapshot 31.05. 02:00: 2450/5163)
- **Tests:** GitHub Actions CI erkennt Kernel-Tests + Watchdog-Build (`mvn -pl agicore-kernel -am clean test` + `mvn -pl agicore-watchdog -am -DskipTests package`). Modules nur lokal testbar (MaryTTS-JARs, TornadoVM-GPU nicht auf CI verfügbar).
- **Runbook:** [RUNBOOK.md](RUNBOOK.md) — 6 Failure-Modi + Deployment + Health-Check

## EDI-Distanz
Phasen 1-7+ sind 100% autonomer Agent. Phase 8 (narratives Selbstmodell, SelfReflector + PersonalityTripwire) und Phase 9 (Long-Horizon-Planung) sind 100% deployed. Phase 10 | 🟢 100% | CausalModel ✅ Counterfactual ✅ InterventionRunner ✅