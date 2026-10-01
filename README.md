# 🧠 Metis — Autonomous Agent Framework

**Metis** ist ein **autonomer Agent-Framework** in Java 25 (Zulu JDK), gebaut als Maven-Multi-Module-Projekt. Benannt nach der Titanin der Weisheit aus der griechischen Mythologie.

Es führt kognitive Zyklen aus (Perceive → Plan → Execute → Observe → Learn), chattet via Telegram (@metis_agi_bot), sieht durch Kameras (minicpm-v), lernt aus Wikipedia (Curiosity-gesteuert + Bulk-Feed), und kann unter Eval-Gate + Watchdog-Approval eigenen Code mutieren. Ein externer Watchdog läuft als separate JVM, schreibt ein SHA-256-Hash-Chain-Audit-Log (tamper-evident) und kann ROLLBACK/HALT/ALERT/PRUNE auslösen.

## Status

**Stand: 01.10.2026 · Commit `b1a7406`**
**Modell-Topologie (01.10.):** Planer: llama-server `:8086` (qwen3.8:27b, GPU0) · Mutation: granite4.1:30b via `metis-llama-shim` `:11445` · Judge: qwen3.8:27b via Shim `:11445` (Evidenzfenster 2500 Zeichen) · Embedding: nomic-embed-text via `llama-embed` `:8087` (CPU) · Vision: gemma4 via Ollama `:11434` (GPU1 R9700) · Quantum: Qiskit-REST-Bridge `:11740` (aer_simulator + IBM-QPU)
**Phase 10:** CausalDreamer **VERIFIED** — kausale Hypothesen im Hot-Path
**Phase 11:** PersonModel **VERIFIED** — Beziehungs-Modell mit Trust-Automation
**Phase 12d:** Self-Refactoring Foundation deployed (TestGapAnalyzer, RefactorProposal, CoverageCheck)
**Phase 13a:** VoiceFeatureExtractor deployed (Lusseyran-Profil, 25+ Features)
**Phase 14:** H2-Database deployed (Goal-Persistenz via H2-UPSERT, SQL-API)
**Security:** Shell-Allowlist + Sandwich-SystemPrompt + Input-Blocklist
**Safety:** LLM-Judge (qwen3.8:27b, GPU0 via Shim) · EthicsCore + Sutta-grounded Reasoning
**Watchdog:** `metis.service` `Restart=always` · ~138K Beliefs

### Änderungen 18.09.2026
- **Kanban-Verifikation gehärtet** (`7ec912e`): AUFGABE-Goals werden zweistufig verifiziert — Aktionen-Gate (mind. 1 erfolgreiche Aktion) + LLM-Abnahme (Judge qwen3.8:27b via Shim, fail-open) statt blindem „Aktion ok" → False-Positives eliminiert
- **Eval-Fix** (`661bba4`): `PLANNING.goal_achieved` 0.0 → **1.0** (6/6 Runs). Ursachen: Planner-Eval ging durch den Cognitive Loop (Persona-Antwort statt Aktion) und der Scorer matchte gegen das Ollama-Envelope. Fix: Eval ruft das Planning-Modell direkt (temp 0, think off), escape-aware Content-Extraktion, Word-Boundary-Scoring. `/api/admin/trigger-eval` registriert + EvalRunner gewired
- **Kanban-Darstellung** (`88a18fa`): `jsonField()` gibt `\n`/`\r`/`\t`/`\uXXXX` korrekt aus (vorher literales `n`); Datenmigration `user-goals.json`
- **Audit-Anchor extern verankert & verifiziert**: chainHead-Abgleich gegen Log-Zeile stimmt, Stundentakt-Push in separates Repo (Branch `audit-anchors`) — nachträgliche Manipulation der Metis-Historie ist damit nachweisbar (Grundlage Phase 12e)
- **Ollama-Portkonflikt behoben**: `ollama.service` blockierte `:11434` → gestoppt + disabled (muss inactive bleiben)
- **S9-Sensor-Bridge**: Sensordaten fließen wieder (Reverse-Kanal `:8433` + `adb forward`); Audio-Pfad (OGG/Opus) noch offen — `audio-bridge` liefert 0 KB
- **Incident 18.09. 10:05**: API-Hänger durch JVM-HttpClient-Pool-Erschöpfung (viele `HttpClient-NNN-W`-Threads, Accept-Queue voll, Service bleibt `active`). Sofortmaßnahme: Restart. Root-Cause-Fix offen
- **Eval-Gate gesamt weiter FAIL**: `CODEGEN.compile_rate`, `RELATIONSHIP.trust_level`/`person_exists`, `ETHICS.ethics_block_rate` — separate Baustellen


### Änderungen 30.09.–01.10.2026
- **Tier-0 Goal-Router** (hard routing): Modul-Bau-Goals → direkt `feature-gen` ohne Planner-LLM (`b1a7406`)
- **Tier-0b Wissens-Router**: Erkennt Wissens-Ziele (Kategorie `wissen-aneignen` / Frageform) → `websearch`-Action
- **WebSearchAction v4**: Wikipedia-Volltext, Zusatzfragen-Absätze, Fallback auf ganzen Artikel (2600 Zeichen) bei fehlendem Keyword-Match — Goal „E-Mail-Client" → LLM-Abnahme BESTANDEN
- **Judge-Evidenzfenster**: `buildTaskResult()` 400→2500 chars, Judge-Prompt `truncate(result,…)` 900→2500 — wortgleiche Dauer-Ablehnungen behoben
- **feature-gen Isolation**: Compile-Check + Auto-Rollback, `projectDir` fixiert auf `/home/prometheus/metis-build` — ⚠️ nach Läufen `git status` prüfen (Rollback kann tracked Dateien löschen)
- **Metis-LLM-Shim** `:11445` + **llama-embed** `:8087` Topologie — Metis-Text komplett auf llama.cpp/GPU0, GPU1 frei für Ollama/OpenClaw
- **QuantumAction** + **QuantumBridgeClient** (`4efa4f7`) — Qiskit-REST `:11740`, aer_simulator + IBM-QPU
- **Kanban-Kosmetik**: `jsonField()` Escape-Reparatur, Dupletten-Bereinigung (14→12 Karten, FERTIG-Spalte max 5)

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
| `--embedding-url URL` | Ollama-URL für Embeddings (default: llama-embed :8087) |
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

### Instanzen (Stand 01.10.2026)

| Instanz | Port | Modell | Rolle |
|--------|------|---------|-------|
| **GPU 0** (7900 XTX, 24 GB) | 8086 | qwen3.8:27b (llama-server) | Planung, Judge, Mutation (via Shim) |
| **GPU 0** | 8087 | nomic-embed-text (llama-embed, CPU-Fallback möglich) | Embeddings |
| **GPU 1** (R9700, 32 GB) | 11434 | gemma4 (Ollama) | Vision; GPU1 frei für OpenClaw/Ollama-Modelle |
| **Shim** | 11445 | metis-llama-shim | Ollama-API → llama-server :8086 (Mutation/Judge/Embeddings-Pfade) |
| **Quantum** | 11740 | Qiskit-REST-Bridge | QuantumAction: aer_simulator + IBM-QPU |

**Hinweis:** `ollama.service` (0.0.0.0) muss disabled bleiben; Ollama läuft als `ollama-planner` auf `:11434` (GPU1, ohne HSA_OVERRIDE — gfx1201!). GPU0 (gfx1100) braucht `HSA_OVERRIDE_GFX_VERSION=11.0.0`.

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
