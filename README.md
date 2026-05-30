# 🧠 Metis — Self-Evolving AGI

**Metis** ist eine selbst-evolvierende, lokal laufende Java-AGI. Benannt nach der Titanin der Weisheit aus der griechischen Mythologie.

Sie denkt in kognitiven Zyklen (Perceive → Plan → Execute → Observe → Learn), chattet via Telegram (@metis_agi_bot), sieht durch Kameras (minicpm-v), lernt aus Wikipedia (Curiosity-gesteuert), und kann sich selbstständig weiterentwickeln. Ein externer Watchdog überwacht als unbestechliche Instanz.

## Status

**Version:** 0.2.0-evolution | **Stand:** 30.05.2026 23:15 | **Phasen:** 1–7 ✅ 100% | **GitHub:** [v0.2.0 Release](https://github.com/theWatcherNineteen83/agicore-agent/releases/tag/v0.2.0)

→ Details: **[FEATURES.md](FEATURES.md)** · **[AGI_EDI_ROADMAP.md](AGI_EDI_ROADMAP.md)** · **[RUNBOOK.md](RUNBOOK.md)** · **[TODO_Metis.md](TODO_Metis.md)**

## Architektur

```
┌──────────────────────────────────────────────────────────────────┐
│                        Metis AGI                                 │
│                                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐   │
│  │  Kernel      │  │  Modules     │  │  Watchdog (R/O JVM)  │   │
│  │  (immutable) │  │  (evolvable) │  │                      │   │
│  │              │  │              │  │  • HALT/ROLLBACK      │   │
│  │ • CoreLoop   │  │ • Planner    │  │  • ALERT/PRUNE       │   │
│  │ • WorldModel │  │ • EvalHarness│  │  • Audit-Log (SHA)   │   │
│  │ • SafetyGuard│  │ • ModelReg.  │  │  • Health-Monitor    │   │
│  │ • SelfModel  │  │ • 24 Actions │  └──────────────────────┘   │
│  └──────────────┘  │ • Kanban Board│                              │
│                     └──────────────┘                              │
│                                                                  │
│  HTTP-API (Port 11735) ← OpenWebUI, curl, Health-Checks          │
│  Telegram Bot       ← @metis_agi_bot (gemma4:e4b, Deutsch)       │
│  Camera Vision      ← minicpm-v (alle 5 Min, Tür + Balkon)       │
│  Wikipedia Lerner   ← Curiosity-gesteuert (alle 10 Min)          │
│  Speech-Loop        ← Piper TTS → Vosk STT (~5% der Artikel)     │
│  Java Lerner        ← Zulu JDK 25 Exploration (alle 15 Min)      │
└──────────────────────────────────────────────────────────────────┘
```

- **Global Workspace Theory** nach Baars: Attention-Bottleneck (Miller's Law), CompetitiveSelector
- **OllamaPlanner:** CoT 4-Schritt (ANALYZE→MATCH→CHECK→DECIDE), 10 Few-Shot, 3-Tier-Fallback
- **WorldModel:** 5.700+ Beliefs, HybridSearch (BM25+Cosinus), PersistentVectorIndex
- **Eval-Harness:** 6 Kategorien, 50+ Tasks, 3-Tier (SMOKE/FULL/EXTENDED), Gate: PASS ✅
- **Watchdog:** Separate JVM, Heartbeat-Check (5s), Audit-Log mit SHA-256 Hash-Chain
- **Kanban Board:** 4 Columns (BACKLOG→READY→IN_PROGRESS→DONE), WIP-Limits pro ResourceType, Service-Klassen (EXPEDITE/FIXED_DATE/STANDARD/INTANGIBLE), Anderson 2010

## Schnellstart

```bash
git clone https://github.com/theWatcherNineteen83/agicore-agent.git
cd agicore-agent
mvn package -DskipTests
java -jar agicore-modules/target/metis-agent.jar \
  --api-port 11735 \
  --evolution \
  --kanban
```

### Telegram-Bot

Metis antwortet unter [@metis_agi_bot](https://t.me/metis_agi_bot) — Deutsch, faktisch, mit Zugriff auf Wetter, HA, Kameras und Wikipedia-Wissen.

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

## HTTP-API

| Endpoint | Beschreibung |
|----------|-------------|
| `GET /api/status` | Agent-Metriken (Ticks, Success, Beliefs) |
| `POST /api/chat` | Chat mit EDI-Persona (OpenWebUI-kompatibel) |
| `GET /api/tags` | Verfügbare Ollama-Modelle |
| `POST /api/show` | Model-Info |
| `GET /api/learned` | Gelernte Beliefs + Experiences |
| `GET /api/conversations` | Chat-Sessions (SQLite) |
| `GET /api/agents` | Multi-Agent-Status |
| `POST /api/admin/prune` | Modell aus Registry entfernen |
| `POST /api/admin/refresh-models` 🆕 | Ollama-Modelle live aktualisieren |
| `/api/board` 🆕 | Kanban-Board Live-View (Spalten, WIP, Flow-Metriken) |

## Modell-Strategie

| Rolle | Modell | Größe |
|-------|--------|-------|
| Planning | `mistral-small3.1:24b` | 17.8 GB |
| Mutation | `qwen3.6:27b-q4_K_M` | 17.4 GB |
| Embedding | `nomic-embed-text` | 0.6 GB |
| Chat (Telegram) | `gemma4:e4b` | 9.6 GB |
| Vision (Kameras) | `minicpm-v:latest` | 5.5 GB |
| Fact Extraction | `gemma4:e4b` (temp=0.2) | 9.6 GB |
| Bootstrap | `llama3.2:3b` | 2.0 GB |
| Fallback-Chain | mistral-small3.1 → qwen3.6:27b → phi4 | — |

**VRAM-Strategie (RX 7900 XTX, 24 GB):** Planner + Embedding = 18.4 GB Dauerlast. Chat/Vision/Facts mit `keep_alive=0` — werden nach Nutzung sofort entladen.

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
```

## Betrieb

- **Health-Monitoring:** Cron alle 5 Min → Telegram-Alert bei Anomalien
- **Config-Backup:** Alle 6h systemd-Units + Status → Git-Repo
- **Watchdog:** HALT bei Heartbeat-Verlust, ROLLBACK bei Eval-Regression
- **Runbook:** [RUNBOOK.md](RUNBOOK.md) — 6 Failure-Modi + Deployment + Health-Check

---

*"Streben nach Perfektion"* — Metis lernt, sieht, mutiert, evaluiert, verbessert sich. Kontinuierlich. Autonom.
