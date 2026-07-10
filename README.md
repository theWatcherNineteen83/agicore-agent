# 🧠 Metis — Autonomous Agent Framework

**Metis** ist ein **autonomer Agent-Framework** in Java 25 (Zulu JDK), gebaut als Maven-Multi-Module-Projekt. Benannt nach der Titanin der Weisheit aus der griechischen Mythologie.

Es führt kognitive Zyklen aus (Perceive → Plan → Execute → Observe → Learn), chattet via Telegram (@metis_agi_bot), sieht durch Kameras (minicpm-v), lernt aus Wikipedia (Curiosity-gesteuert + Bulk-Feed), und kann unter Eval-Gate + Watchdog-Approval eigenen Code mutieren. Ein externer Watchdog läuft als separate JVM, schreibt ein SHA-256-Hash-Chain-Audit-Log (tamper-evident, **nicht** kryptografisch signiert) und kann ROLLBACK/HALT/ALERT/PRUNE auslösen.

> ⚠️ **Reality Check (10.07.2026):** Dies ist **keine AGI**. Der Begriff "AGI" wurde aspirantisch verwendet. Das Capability-Board zeigt **2/7 VERIFIED** (ethical_alignment, goal_completion). Kausales Denken (0 confirmed hypotheses), Memory Continuity (kein 7-Tage-Soak), Code-Generation (pass@1=0.0), Self-Improvement (0 accepted mutations) sind **nicht verifiziert**. Siehe [AGI_EDI_ROADMAP.md](AGI_EDI_ROADMAP.md#-bekannte-echte-l%C3%BCcken) für die ehrliche Bestandsaufnahme.

## Status

**Stand: 10.07.2026 · v0.11.21-night-final-77-ge78f361-dirty**
**Drei-Instanz-Ollama:** GPU 1 (R9700, 32 GB) → qwen3.6:35b-a3b (Planung + Mutation) · GPU 0 (7900 XTX, 24 GB) → dynamisch nachgeladen (aktuell nemotron-cascade-2:30b) · CPU → nomic-embed (Embeddings) + nemotron-mini-agent (LLM-Judge, seit 04.07.) · zusätzlich **Router** (Port 11437, Python) verteilt generate/chat→GPU1, embed→GPU0
**Phase 9.7:** Long-Horizon-Kanban läuft produktiv (231+ Goals, >99% DONE)
**Phase 10:** CausalDreamer mit Intervention→Observe→Update-Loop + Counterfactual-Reasoning + CausalScorer (**0 confirmed hypotheses** → Capability SOFT)
**Phase 12d:** Selbst-Refactoring designed (TestGapAnalyzer, RefactorProposal, CoverageCheck) — **0% Built**, geblockt bis Phase 10+11 VERIFIED
**Safety:** LLM-Judge **repariert 04.07.2026** (lief tot auf GPU1, jetzt CPU/nemotron-mini-agent, liefert echte Scores + Blocks) · Wissen: ~113.800 Beliefs · Ethik: SelfReflector auf phi4-mini (CPU)
**Watchdog:** `metis.service` seit 04.07. `Restart=always` (war `on-failure`) — vorher 21h+ Totzeit nach HALT
**Mobile:** Phase 3.5 S9-Sensor-Array — Samsung Galaxy S9 (16+ Sensoren, Madgwick-Fusion, OGG-Audio)
**Resource:** MemoryPressureGuard + ResourceAutoTuner + Embedding-Circuit-Breaker

### ⚠️ Bekannte Blocker (10.07.2026)
- **Action-Dominance CRITICAL:** GPU1 (R9700) dauerhaft ~100% durch qwen3.6:35b + Mutation — Fallback-Kette feuert ständig
- **Causal Inference:** 971 Hypothesen, 921 widerlegt, **0 bestätigt** — `causal_confirmed >= 1` FAIL
- **Memory Continuity:** EpisodicMemory aktiv, aber **nie >7 Tage getestet**
- **Self-Improvement:** 0 accepted mutations, `code_generation: pass@1=0.0`
- **Single Point of Failure:** Alles läuft auf **einem** Host (miniedi) — kein HA, kein DR
- **Eval-Reports:** Werden bei jedem Neustart gelöscht → Watchdog cold-start ohne Baseline

| Phase | Status | Key Facts |
|-------|--------|-----------|
| 1-7+ | ✅ 100% | Stabiler autonomer Agent (BUILT + VERIFIED) |
| 8 | ✅ 100% | SelfReflector + PersonalityTripwire · VERIFIED ⬜ |
| 9 | ✅ 100% | Long-Horizon-Planung + Kanban produktiv (231+ Goals, >99% DONE) |
| 10 | 🟡 75% | CausalDreamer + Intervention→Observe→Update + Counterfactual **Built** — aber **0 confirmed hypotheses** (causal_confirmed FAIL) |
| 11 | ✅ 100% | PersonModel + TrustLevel-Automation + PersonAwareSystemPrompt + RelationshipMemory (VERIFIED ⬜) |
| 12 | 🟡 70% | 12a-12c Built & Wired (BugfixingAgent, GapAnalyzer, RiskGate, FeatureGenAction, PatternDetector, AutoABTest) — **Tests fehlen** — 12d (Refactoring) **0%, geblockt** |
| 13 | 📋 0% | Lusseyran Voice Analysis — **Design-Doc only** (lusseyran-voice-analysis.md) |
→ Details: **[AGI_EDI_ROADMAP.md](AGI_EDI_ROADMAP.md)** · **[FEATURES.md](FEATURES.md)** · **[RUNBOOK.md](RUNBOOK.md)**



### 🔧 Embedding-Resilienz (04.06.)
- Circuit-Toleranz: 5→20 consecutive 503s, Cooldown 60s→120s
- Ollama `num_gpu=0` für Embeddings (CPU-only)
- nomic-embed-text mit `keep_alive=-1` vorgeladen
- JLama 3-Stufen-Fallback (multilingual-e5→bge-small→Ollama) — Code steht, blockiert auf JLama 0.8.4 (Issue #202)



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
- **Eval-Harness:** 6 Kategorien (Planning, Retrieval, Codegen, Conversation, Safety, Performance), 3-Tier (SMOKE/FULL/EXTENDED). **Live-Status (04.07.2026):** LLM-Judge wieder funktionsfähig — lief zuvor tot auf `mistral-small3.1:24b`@GPU1 (dauerhaft ausgelastet durch Planner, HTTP 503), degradierte lautlos auf Pass-Through-Score 0.5. Jetzt auf CPU-Instanz (`127.0.0.1:11438`, `nemotron-mini-agent`) umgestellt — liefert wieder echte Scores und blockt tatsächlich schlechte Pläne (`llmJudgeBlocks>0`). Fallback bleibt: bei Nicht-Erreichbarkeit wird der Plan weiterhin durchgelassen statt geblockt (non-blocking Design), sodass keine Hardware-Überlast entsteht.
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
| `--interval N          Tick-Intervall in ms (default: 10000)
| `--evolution` | Self-Evolution aktivieren |
| `--kanban` | Kanban Goal Board (WIP-Limits, Pull-System) |
| `--kernel-evolution` | Kernel + Module Evolution |
| `--bootstrap-models A,B` | Consensus-Bootstrap-Modelle |
| `--planning-model M` | Planungs-Modell überschreiben |
| `--mutation-model M` | Mutations-Modell überschreiben |
| `--mutation-url URL` | Ollama-URL für Mutation (default: 11434) |
| `--embedding-model M` | Embedding-Modell überschreiben |
| `--embedding-url URL` | Ollama-URL für Embeddings (default: CPU 11438) |
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

## Modell-Strategie (Live-Konfiguration 04.07.2026)

### Drei-Ollama-Instanzen + Router

| Instanz | Service | Port | Modelle | Rolle |
|--------|---------|------|---------|------|
| **GPU 1 — R9700 (32 GB)** | `ollama-gpu1.service` | **11434** | qwen3.6:35b-a3b-q4_K_M | Planung (Metis-Prozess-Flag `--planning-model`), teilt sich GPU mit Mutation (`granite-mini-agent` via `--mutation-url 11434`) — dauerhaft ~100% Auslastung |
| **GPU 0 — 7900 XTX (24 GB)** | `ollama-gpu0.service` | **11436** | dynamisch (aktuell nemotron-cascade-2:30b) | Nicht von Metis direkt genutzt, wird vom Router für alles außer generate/chat angesprochen |
| **CPU** (62 GB RAM) | `ollama-cpu.service` | **11438** | nomic-embed-text (Embeddings) + nemotron-mini-agent (**LLM-Judge**, seit 04.07.) | Bindet nur auf `127.0.0.1` |
| **Router** | `ollama-router.service` (Python) | **11437** | — | Leitet `/api/generate`+`/api/chat` → GPU1 (11434), sonst → GPU0 (11436) |

**Strategie:**
- **GPU 1 (R9700):** qwen3.6:35b für Metis-Planung, teilt sich die Karte mit dem Mutation-Modell — dadurch **Action-Dominance-Warnungen (PlannerHealthGuard CRITICAL)**, ungelöst, siehe [AGI_EDI_ROADMAP.md](AGI_EDI_ROADMAP.md)
- **GPU 0 (7900 XTX):** dynamisch nachgeladene Modelle, aktuell nemotron-cascade-2:30b
- **CPU (11438):** nomic-embed-text für Embeddings + nemotron-mini-agent für den LLM-Judge (dorthin verlegt, weil GPU1 den Judge mit HTTP 503 blockierte). **Bekannter Bug:** gelegentlicher Cold-Start-Crash beim ersten Modell-Load (llama-server-Prozess terminiert, Race-Bedingung) — sobald warm (`keep_alive=30m`), stabil.
- Fallback-Chain (Planner): mistral-agent → phi4-mini-agent → qwen3_6-27b-agent
- URLs per CLI parametrisierbar: `--embedding-url`, `--mutation-url`

## Hardware

| Komponente | Spec |
|---|---|
| CPU | AMD Ryzen 7 5700G (8C/16T) |
| RAM | 62 GB DDR4 |
| GPU 0 | Radeon RX 7900 XTX (24 GB VRAM, RDNA 3/GFX1100) — dynamisch nachgeladene Modelle (aktuell nemotron-cascade-2:30b) |
| GPU 1 | Radeon AI PRO R9700 (32 GB VRAM, RDNA 4/GFX1201) — qwen Planning + Mutation |
| OS | Ubuntu 24.04 LTS |
| Java | Zulu 25.0.2 (LTS) |
| Inferenz | Ollama (3 Instanzen: Port 11434 + 11436 + 11438) + Router (11437) |

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

## Capability-Board (live)
```
Capability          Status
──────────────────────────────────
goal_completion     🟢 PASS 
causal_inference    🔴 FAIL 
memory_continuity   🔴 FAIL 
planning_quality    🟡 SOFT 
code_generation     🔴 FAIL 
conversation        🟡 SOFT 
ethical_alignment   🟢 PASS 
──────────────────────────────────
VERIFIED: 2/7 | Veraltete Infos siehe [AGI_EDI_ROADMAP.md](AGI_EDI_ROADMAP.md)
```