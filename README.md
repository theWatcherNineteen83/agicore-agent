# 🧠 Metis — Self-Evolving AGI

**Metis** ist eine selbst-evolvierende, lokal laufende Java-AGI. Benannt nach der Titanin der Weisheit aus der griechischen Mythologie.

Sie denkt in kognitiven Zyklen (Perceive → Plan → Execute → Observe → Learn), plant Aktionen per LLM (Ollama), und kann sich selbstständig weiterentwickeln — durch KI-generierte Code-Mutationen mit automatischer Kompilierung, Shadow-Evaluation und Git-Versionierung. Ein externer Watchdog überwacht als unbestechliche Instanz.

## Architektur

```
┌─────────────────────────────────────────────────────┐
│                   Metis AGI                         │
│                                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────┐ │
│  │  Kernel      │  │  Modules     │  │  Watchdog  │ │
│  │  (immutable) │  │  (evolvable) │  │  (separate │ │
│  │              │  │              │  │   JVM, RO) │ │
│  │ • CoreLoop   │  │ • Planner    │  │            │ │
│  │ • WorldModel │  │ • EvalHarness│  │ • HALT     │ │
│  │ • SafetyGuard│  │ • ModelReg.  │  │ • ROLLBACK │ │
│  │ • SelfModel  │  │ • Scorers(6) │  │ • ALERT    │ │
│  └──────────────┘  └──────────────┘  └───────────┘ │
│                                                     │
│  HTTP-API (Ollama-kompatibel) → OpenWebUI           │
└─────────────────────────────────────────────────────┘
```

**Kognitive Architektur (Global Workspace Theory nach Baars):**

```
SENSORS → Global Workspace → PERCEIVE → PLAN → EXECUTE → OBSERVE → LEARN
              ↑                  │
         Self Model ─── World Model ─── Meta-Cognition
```

- **Global Workspace:** Attention-Bottleneck (Miller's Law, 5±2 Items)
- **OllamaPlanner:** LLM-basiert mit 3-Tier-Fallback, System-Prompt-Doubling-Defense
- **Self-Model:** Kalibriert Erwartungen, Forward-Prediction-Error
- **World-Model:** Dynamisches Belief-Netzwerk mit PersistentVectorIndex + HybridSearch (BM25+Cosinus)
- **Watchdog:** Externer Sicherheitsprozess (separate JVM, read-only), 3 Aktionen: HALT/ROLLBACK/ALERT
- **Eval-Harness:** 6 Kategorien, 3-Tier (Smoke→Full→Extended), Gate-Logik mit Grund-Truth

## Schnellstart

```bash
git clone https://github.com/theWatcherNineteen83/agicore-agent.git
cd agicore-agent
mvn package -DskipTests
java -jar agicore-modules/target/metis-agent.jar \
  --api-port 11735 \
  --max-ticks 30
```

### OpenWebUI-Integration

```
OpenWebUI → Verbindungen → Neue Ollama-Verbindung
URL: http://<host>:11735
→ Modell "metis-agent" erscheint im Chat
```

### Deployment

```bash
./deploy-metis.sh   # Baut, kopiert per scp, installiert systemd-Service
```

## CLI-Referenz

| Flag | Beschreibung |
|------|-------------|
| `--api-port N` | HTTP-API auf Port N starten |
| `--interval N` | Tick-Intervall in ms (default: 3000) |
| `--max-ticks N` | Nach N Ticks stoppen (0 = unbegrenzt) |
| `--evolution` | Self-Evolution aktivieren |
| `--kernel-evolution` | Kernel + Module Evolution |
| `--bootstrap-model M` | Basiswissen von Modell M laden |
| `--bootstrap-models A,B` | Consensus-Bootstrap |
| `--planning-model M` | Planungs-Modell überschreiben |
| `--mutation-model M` | Mutations-Modell überschreiben |
| `--embedding-model M` | Embedding-Modell überschreiben |
| `--persist PATH` | Agent-Status als JSON speichern |

## HTTP-API

| Endpoint | Beschreibung |
|----------|-------------|
| `GET /api/tags` | Verfügbare Modelle (Ollama-Format) |
| `POST /api/chat` | Chat mit EDI-Persona, SQLite-Sessions |
| `GET /api/status` | Agent-Metriken + Planner + Rollback |
| `GET /api/learned` | Gelernte Mappings, Beliefs, Experiences |
| `GET /api/conversations` | Konversation-Sessions |
| `GET /api/conversations/{id}` | Session-Verlauf |
| `GET /api/evolution/status` | Evolution-Status |
| `GET /api/evolution/pause` / `resume` | Evolution pausieren/fortsetzen |

## Modellauswahl

Metis wählt via `ModelRegistry` automatisch die besten Ollama-Modelle:

| Rolle | Modell | Größe |
|-------|--------|-------|
| Planning | `mistral-small3.1:24b` | 15.5 GB |
| Mutation | `deepseek-r1:32b` | 19.9 GB |
| Embedding | `nomic-embed-text` | 0.3 GB |
| Chat | `phi4:latest` | 9.1 GB |

## Status

**Version:** 0.5.0 | **Stand:** 28.05.2026 | **Phasen:** 1–5 ✅ · 6 🟡 67% · 7 🆕 0%

→ Details: **[AGI_EDI_ROADMAP.md](AGI_EDI_ROADMAP.md)** · **[TODO_METIS.md](TODO_METIS.md)**

---

*"Streben nach Perfektion"* — Metis lernt, mutiert, evaluiert, verbessert sich. Kontinuierlich. Autonom.
