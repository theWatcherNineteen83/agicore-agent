# 🧠 Metis — Self-Evolving AGI

**Metis** ist eine selbst-evolvierende, lokal laufende Java-AGI auf JDK 25 (Zulu). Benannt nach der Titanin der Weisheit aus der griechischen Mythologie.

Sie denkt in kognitiven Zyklen (Perceive → Plan → Execute → Observe → Learn), chattet via Telegram (@metis_agi_bot), sieht durch Kameras (minicpm-v), lernt aus Wikipedia (Curiosity-gesteuert + Bulk-Feed), und kann sich selbstständig weiterentwickeln. Ein externer Watchdog überwacht als unbestechliche Instanz mit signiertem Hash-Chain-Audit-Log.

## Status

**Version:** 0.5.1-phase9-complete · **Stand:** 31.05.2026 01:45 · **Tests:** 49 grün · **Phasen 1-7+:** ✅ 100% · **Phase 8:** ✅ 100% · **Phase 9:** ✅ 100%

→ Details: **[FEATURES.md](FEATURES.md)** · **[AGI_EDI_ROADMAP.md](AGI_EDI_ROADMAP.md)** · **[RUNBOOK.md](RUNBOOK.md)** · **[TODO_Metis.md](TODO_Metis.md)**

### Tag-Linie der letzten Hardening-Phase (30./31.05.2026)
| Tag | Inhalt | Tests |
|---|---|---|
| `v0.2.0` | Phasen 1-7 abgeschlossen | 1 |
| `v0.2.1-hardened` | CI + Embedding-LRU + Java 25 + Input-Guard | 21 |
| `v0.3.0-agi-push` | Multi-Modal-Memory + Loom-Vision + Subprocess-Isolation + Audit-Anchor | 23 |
| `v0.3.1-observability` | Locale-Fix + Wiki-Persistence + git-cwd-Fix + Wiki-Loom | 25 |
| `v0.3.2-feed-hardening` | WAL-Mode + atomic State + Lock-Retry + Wiki-Backup auf GitHub | 25 |
| `v0.3.3-defense-in-depth` | Telegram-Loom + Telegram Input/Output-Safety-Guards | **27** |

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
│  │ • SelfModel  │  │ • 24 Actions │  │  • Health-Monitor    │   │
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
- **WorldModel:** 30.945+ Beliefs, HybridSearch (BM25+Cosinus), PersistentVectorIndex, WAL-Mode
- **Eval-Harness:** 6 Kategorien, 50+ Tasks, 3-Tier (SMOKE/FULL/EXTENDED), Gate: PASS ✅
- **Watchdog:** Separate JVM, Heartbeat-Check (5s), SHA-256 Hash-Chain, stündliche externe Anchors
- **Kanban Board:** 4 Columns (BACKLOG→READY→IN_PROGRESS→DONE), WIP-Limits pro ResourceType
- **Defense-in-Depth:** Input-Safety-Guard + Output-Safety-Guard auf HTTP- und Telegram-Pfad

## Schnellstart

```bash
git clone https://github.com/theWatcherNineteen83/agicore-agent.git
cd agicore-agent
mvn -B verify   # 27 Tests, SBOM (CycloneDX) wird mitgebaut
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
| Planning | `mistral-small3.1:24b` | 15.5 GB |
| Mutation | `qwen3.6:27b-q4_K_M` | 17.4 GB |
| Embedding | `nomic-embed-text` | 0.3 GB |
| Chat (Telegram) | `gemma4:e4b` | 9.6 GB |
| Vision (Kameras) | `minicpm-v:latest` | 5.5 GB |
| Bootstrap | `granite4.1:3b` / `llama3.2:3b` | 2.0 GB |
| Fallback-Chain | mistral-small3.1 → qwen3.6:27b-q4_K_M → phi4 | — |

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

```bash
# Service-Kontrolle
sudo systemctl status metis.service
journalctl -u metis.service -f

# Watchdog
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
- **Wiki-Feed:** Cron-Job `metis-wiki-feed` (10 Min, 30 Artikel/Batch), aktuell 2240/5163
- **Tests:** GitHub Actions CI (`mvn verify`) auf jeden Push
- **Runbook:** [RUNBOOK.md](RUNBOOK.md) — 6 Failure-Modi + Deployment + Health-Check

## EDI-Distanz (ehrlich)

Phasen 1-7+ sind 100%, aber das ist **autonomer Agent**, nicht EDI. Echtes EDI-Niveau braucht 4 weitere Phasen:

- **Phase 8** — Narratives Selbstmodell (Episode-Memory, MoodSignal, PersonalityAnchor)
- **Phase 9** — Long-Horizon-Planung (Strategic/Tactical/Operational Goals, Wochenhorizont)
- **Phase 10** — Aktive kausale Hypothesen (CausalModel im Hot-Path, Interventionen, Counterfactuals)
- **Phase 11** — Beziehungs-Modell (PersonModel, TrustLevel, RelationshipMemory)

Realistischer EDI-Stand: **~50-55%**. Die solideste Open-Source-Basis weltweit, aber nicht selbstbewusst, nicht langfristig planend, nicht kausal-modellierend. Details siehe [AGI_EDI_ROADMAP.md](AGI_EDI_ROADMAP.md).

---

*"Streben nach Perfektion"* — Metis lernt, sieht, mutiert, evaluiert, verbessert sich. Kontinuierlich. Autonom. Defense-in-Depth gesichert.
