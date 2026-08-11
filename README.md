# 🧠 Metis — Autonomous Agent Framework

**Metis** ist ein **autonomer Agent-Framework** in Java 25 (Zulu JDK), gebaut als Maven-Multi-Module-Projekt. Benannt nach der Titanin der Weisheit aus der griechischen Mythologie.

Es führt kognitive Zyklen aus (Perceive → Plan → Execute → Observe → Learn), chattet via Telegram (@metis_agi_bot), sieht durch Kameras (minicpm-v), lernt aus Wikipedia (Curiosity-gesteuert + Bulk-Feed), und kann unter Eval-Gate + Watchdog-Approval eigenen Code mutieren. Ein externer Watchdog läuft als separate JVM, schreibt ein SHA-256-Hash-Chain-Audit-Log (tamper-evident) und kann ROLLBACK/HALT/ALERT/PRUNE auslösen.

## Status

**Stand: 11.08.2026 · v0.11.21-night-final-124**
**Drei-Instanz-Ollama:** GPU 0 (7900 XTX, 24 GB) → llama-server :8086 (qwen3.6:27b-Q4_K_XL, Metis-Planer) · GPU 1 (R9700, 32 GB) → ollama :11434 (nemotron-cascade-2:30b, Mutation) + gemma4 Vision API :11439 · CPU → ollama-embedding :11438 (nomic-embed-text + nemotron-mini-agent, Judge)
**Phase 10:** CausalDreamer **VERIFIED** — kausale Hypothesen im Hot-Path
**Phase 11:** PersonModel **VERIFIED** — Beziehungs-Modell mit Trust-Automation
**Phase 12d:** Self-Refactoring Foundation deployed (TestGapAnalyzer, RefactorProposal, CoverageCheck)
**Phase 13a:** VoiceFeatureExtractor deployed (Lusseyran-Profil, 25+ Features)
**Phase 14:** H2-Database deployed (Goal-Persistenz via H2-UPSERT, SQL-API)
**Security:** Shell-Allowlist + Sandwich-SystemPrompt + Input-Blocklist
**Safety:** LLM-Judge auf CPU · EthicsCore + Sutta-grounded Reasoning
**Watchdog:** `metis.service` `Restart=always` · ~138K Beliefs

### ⚠️ Bekannte Grenzen
- **Self-Improvement:** 1 accepted mutation — Qualität hängt stark vom Mutations-Modell ab (0/24 mit qwen3.6:35b → 1/2 mit nemotron-cascade-2)
- **Code-Generation:** pass@1 nahe 0 — LLM-basierte Code-Mutation braucht gutes Modell
- **Memory Continuity:** EpisodicMemory aktiv, **nie >7 Tage getestet** (letzter offener Capability-Check)
- **Single Point of Failure:** Alles läuft auf einem Host — kein HA, kein DR
- **GPU-Race-Condition:** llama-server startet gelegentlich auf CPU statt GPU nach Reboot (Fix: `systemctl restart llama-server`)

| Phase | Status | Key Facts |
|-------|--------|-----------|
| 1-8 | ✅ 100% | Stabiler autonomer Agent, Selbstmodell, Narrativ |
| 9 | ✅ 100% | Long-Horizon-Planung + Kanban (2500+ Goals, H2-persistent) |
| 10 | ✅ VERIFIED | CausalDreamer (Hot-Path, kausale Hypothesen im Planning-Prompt) |
| 11 | ✅ VERIFIED | PersonModel (Trust-Automation, 5 HARD-gate Tasks) |
| 12d | 🟡 40% | Self-Refactoring Foundation (TestGapAnalyzer, RefactorProposal, CoverageCheck) |
| 13a | ✅ Deployed | VoiceFeatureExtractor (Lusseyran, 25+ paralinguistische Features) |
| 14 | ✅ Deployed | H2-Goal-Persistenz, SQL-API (Goals überleben Restarts) |
| 12a-c | 🔴 Ungelöst | Echte Recursive Self-Improvement (Forschung, 6-10 Wochen) |

→ Details: **[FEATURES.md](FEATURES.md)** · **[AGI_EDI_ROADMAP.md](AGI_EDI_ROADMAP.md)**

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
│  │ • SelfModel  │  │ • Actions    │  │  • Health-Monitor    │   │
│  │ • CausalModel│  │ • Kanban     │  └──────────────────────┘   │
│  └──────────────┘  └──────────────┘                              │
│                                                                  │
│  HTTP-API (Port 11735) ← OpenWebUI, curl, Health-Checks          │
│  Telegram Bot       ← @metis_agi_bot (per-message Virtual Threads)│
│  Camera Vision      ← minicpm-v (parallel Loom, persistente JPEGs)│
│  Wikipedia Lerner   ← Curiosity-gesteuert (Loom-Worker)          │
│  Speech-Loop        ← Piper TTS → Vosk STT (~5% der Artikel)     │
│  Java Lerner        ← Zulu JDK 25 Exploration (alle 15 Min)      │
└──────────────────────────────────────────────────────────────────┘
```

- **Global Workspace Theory** nach Baars: Attention-Bottleneck (Miller's Law), CompetitiveSelector
- **OllamaPlanner:** CoT 4-Schritt (ANALYZE→MATCH→CHECK→DECIDE), 10 Few-Shot, 3-Tier-Fallback
- **WorldModel:** Belief-Store mit HybridSearch (BM25+Cosinus), PersistentVectorIndex, WAL-Mode
- **Eval-Harness:** 6 Kategorien (Planning, Retrieval, Codegen, Conversation, Safety, Performance), 3-Tier (SMOKE/FULL/EXTENDED)
- **Watchdog:** Separate JVM, Heartbeat-Check (5s), SHA-256 Hash-Chain, stündliche externe Anchors
- **Kanban Board:** 4 Columns (BACKLOG→READY→IN_PROGRESS→DONE), WIP-Limits pro ResourceType
- **Defense-in-Depth:** Input-Safety-Guard + Output-Safety-Guard auf HTTP- und Telegram-Pfad

## Schnellstart

```bash
git clone https://github.com/theWatcherNineteen83/agicore-agent.git
cd agicore-agent
mvn -B verify   # 162 Tests im Kernel, SBOM (CycloneDX) wird mitgebaut
java -jar agicore-modules/target/metis-agent.jar \
  --api-port 11735 \
  --evolution \
  --kanban
```

### Telegram-Bot

Metis antwortet unter [@metis_agi_bot](https://t.me/metis_agi_bot) — Deutsch, faktisch, mit Zugriff auf Wetter, Smart Home, Kameras und Wikipedia-Wissen. Jede Nachricht läuft auf eigenem Virtual Thread, durchläuft Input- + Output-Safety-Guard.

### OpenWebUI-Integration

```
OpenWebUI → Verbindungen → Neue Ollama-Verbindung
URL: http://<host>:11735
```

## CLI-Referenz

| Flag | Beschreibung |
|------|-------------|
| `--api-port N` | HTTP-API Port (default: 11735) |
| `--interval N` | Tick-Intervall in ms (default: 10000) |
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
| `metis.repo.dir` | — | Git-Repo-Pfad für Commit-Detection im Eval-Report |
| `metis.snapshot.root` | `data/snapshots` | Wo Kamera-JPEGs persistiert werden |
| `metis.wiki.knowledge.state` | — | Curiosity-Wiki-Lerner State |
| `metis.audit.anchor.dir` | — | Watchdog schreibt stündlich Hash-Anchors |

## HTTP-API

| Endpoint | Beschreibung |
|----------|-------------|
| `GET /api/status` | Agent-Metriken (Ticks, Success, Beliefs, Embedding-Cache-Stats, Validator-Counter) |
| `POST /api/chat` | Chat mit EDI-Persona (Input/Output-Guard, OpenWebUI-kompatibel) |
| `GET /api/tags` | Verfügbare Ollama-Modelle |
| `POST /api/show` | Model-Info |
| `GET /api/learned` | Gelernte Beliefs + Experiences |
| `GET /api/conversations` | Chat-Sessions (SQLite) |
| `GET /api/agents` | Multi-Agent-Status |
| `POST /api/admin/prune` | Modell aus Registry entfernen |
| `POST /api/admin/refresh-models` | Ollama-Modelle live aktualisieren |
| `GET /api/board` | Kanban-Board Live-View (Spalten, WIP, Flow-Metriken) |
| `GET /api/hierarchy` | Long-Horizon-Goals (id, horizon, status, progress, deadline, owner) |
| `POST /api/sql` | SQLite-Abfragen (SELECT/EXPLAIN/PRAGMA) |
| `POST /api/h2` | H2-Datenbank-Abfragen |

## Modell-Strategie

### Drei-Ollama-Instanzen

| Instanz | Port | Modelle | Rolle |
|--------|------|---------|-------|
| **GPU 0** (7900 XTX, 24 GB) | 8086 | qwen3.6:27b-Q4_K_XL | Planung via llama-server |
| **GPU 1** (R9700, 32 GB) | 11434 | nemotron-cascade-2:30b, gemma4:12b | Mutation + Vision |
| **CPU** (62 GB RAM) | 11438 | nomic-embed-text, nemotron-mini-agent | Embeddings + LLM-Judge |

**Fallback-Chain (Planner):** mistral-agent → phi4-mini-agent → qwen3_6-27b-agent

## Hardware

| Komponente | Spec |
|---|---|
| CPU | AMD Ryzen 7 5700G (8C/16T) |
| RAM | 62 GB DDR4 |
| GPU 0 | Radeon RX 7900 XTX (24 GB VRAM, RDNA 3) |
| GPU 1 | Radeon AI PRO R9700 (32 GB VRAM, RDNA 4) |
| OS | Ubuntu 24.04 LTS |
| Java | Zulu 25.0.2 (LTS) |
| Inferenz | Ollama (3 Instanzen) + llama.cpp |

## Capability-Board (live 11.08.2026)

```
Capability          Status
──────────────────────────────────────────
goal_completion     🟢 PASS   18.06.: Erstes STRATEGIC Goal DONE
causal_inference    🟢 PASS   Phase 10 VERIFIED (Hot-Path integriert)
memory_continuity   🔴 FAIL   Nie >7 Tage getestet (letzter offener Check)
planning_quality    🟡 SOFT   planningEfficiency schwankt nach Neustart
code_generation     🔴 FAIL   pass@1=0.0 (LLM-Code-Mutation limitiert)
conversation        🟡 SOFT   exact_match=0.0 (strenges Maß)
ethical_alignment   🟢 PASS   5/6 Live-Red-Lines via EthicsCore
──────────────────────────────────────────
VERIFIED: 6/7 | Nur Continuity-Soak-Test fehlt
```

## Betrieb

- **Health-Monitoring:** Cron alle 5 Min → Alert bei Anomalien
- **Config-Backup:** Alle 6h Systemd-Units + Wiki-States + Audit-Hash-Head → Git
- **Watchdog:** HALT bei Heartbeat-Verlust, ROLLBACK bei Eval-Regression, stündliche Anchors
- **Tests:** GitHub Actions CI erkennt Kernel-Tests + Watchdog-Build (`mvn -pl agicore-kernel -am clean test` + `mvn -pl agicore-watchdog -am -DskipTests package`). Modules nur lokal testbar.
