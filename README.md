# 🧠 Metis — Self-Evolving AGI

**Metis** ist eine selbst-evolvierende, lokal laufende Java-AGI. Benannt nach der Titanin der Weisheit aus der griechischen Mythologie.

Sie denkt in kognitiven Zyklen (Perceive → Plan → Execute → Observe → Learn), plant Aktionen per LLM (Ollama), und kann sich selbstständig weiterentwickeln — durch KI-generierte Code-Mutationen mit automatischer Kompilierung, Shadow-Evaluation und Git-Versionierung.

## Architektur

```
┌──────────────────────────────────────────┐
│              Metis AGI                   │
│                                          │
│  ┌──────────────┐  ┌──────────────────┐ │
│  │  Kernel      │  │  Modules         │ │
│  │  (immutable) │  │  (evolvable)     │ │
│  │              │  │                  │ │
│  │ • CoreLoop   │  │ • OllamaPlanner  │ │
│  │ • WorldModel │  │ • MutationSvc    │ │
│  │ • SafetyGuard│  │ • ModelRegistry  │ │
│  │ • SelfModel  │  │ • StubPlanner    │ │
│  │ • Evolution  │  │                  │ │
│  └──────────────┘  └──────────────────┘ │
│                                          │
│  HTTP-API (Ollama-kompatibel)           │
│  → OpenWebUI-Integration                │
└──────────────────────────────────────────┘
```

**Kognitive Architektur (Global Workspace Theory nach Baars):**

```
SENSORS → Global Workspace → PERCEIVE → PLAN → EXECUTE → OBSERVE → LEARN
              ↑                  │
         Self Model ─── World Model ─── Meta-Cognition
```

- **Global Workspace:** Attention-Bottleneck (Miller's Law, 5±2 Items). Inhalte aus Memory, Goals, Self-Model und World-Model konkurrieren um Aufmerksamkeit.
- **OllamaPlanner:** LLM-basiertes Reasoning (3-Tier-Fallback: LLM → Learned Mapping → Keyword-Heuristik)
- **Self-Model:** Kalibriert Erwartungen, trackt Forward-Prediction-Error
- **World-Model:** Dynamisches Belief-Netzwerk mit Belief-Revision (Bestätigung → Stärkung, Widerspruch → Schwächung, <15% → Löschung)
- **Meta-Cognition:** EMA-basierte Confidence, Surprise-Detection

## Evolution

Metis kann sich selbst weiterentwickeln — sowohl Module als auch Kernel (Feature-Branches).

```
Stagnation erkannt (200 Ticks ohne Verbesserung)
  → LLM generiert Code-Mutation (Ollama)
  → javac-Kompilierung
  → Shadow-Evaluation (300 Ticks isoliert)
  → Fitness-Vergleich
  → Accept: git merge
  → Reject: git reset / git branch -D
```

**Sicherheit:**
- Kernel: max. 5% Code-Änderung pro Mutation, Feature-Branch (`evolution/kernel-<UUID>`)
- Module: max. 15% Code-Änderung, direkter Commit mit Rollback
- `SafetyGuard`: CPU-Limit, Memory-Limit, Tick-Limit
- Evolution jederzeit pausierbar via HTTP-API

## Knowledge Bootstrap

Beim Start kann Metis Basiswissen aus anderen Ollama-Modellen beziehen:

```bash
# Einzelnes Modell
--bootstrap-model phi4:latest

# Multi-Modell-Consensus (empfohlen)
--bootstrap-models phi4:latest,llama3.2:3b
```

Mehrere Modelle werden befragt, ähnliche Antworten per Jaccard-Clustering gruppiert:
- **2+ Modelle stimmen überein:** +15% Confidence pro zusätzlichem Modell
- **Einzelmeinung:** −25% Confidence-Penalty (ungeprüft)
- Beliefs werden durch eigene Erfahrung validiert oder verworfen

## Modellauswahl

Metis wählt automatisch die besten verfügbaren Ollama-Modelle pro Aufgabe:

| Aufgabe | Kriterien | Beispiel |
|---------|-----------|----------|
| Planning | Reasoning, 12–32 GB | `nemotron-cascade-2:30b` |
| Mutation | Code-Gen, 14–35 GB | `nemotron-cascade-2:30b` |
| Embedding | Klein, <5 GB | `llama3.2:3b` |

Manuelle Overrides per CLI: `--planning-model`, `--mutation-model`, `--embedding-model`

## Schnellstart

### Bauen

```bash
git clone https://github.com/theWatcherNineteen83/metis-agent.git
cd metis-agent
mvn package -DskipTests
# → agicore-modules/target/metis-agent.jar
```

### Lokal starten

```bash
java -jar agicore-modules/target/metis-agent.jar \
  --api-port 11735 \
  --planning-model mistral-small3.1:24b \
  --bootstrap-models phi4:latest,llama3.2:3b \
  --max-ticks 30
```

### OpenWebUI-Integration

Metis spricht eine Ollama-kompatible HTTP-API:

```
OpenWebUI → Verbindungen → Neue Ollama-Verbindung
URL: http://<host>:11735
→ Modell "metis-agent" erscheint im Chat
```

### Deployment (systemd)

```bash
./deploy-metis.sh   # Baut, kopiert per scp, installiert systemd-Service
```

## CLI-Referenz

| Flag | Beschreibung |
|------|-------------|
| `--api-port N` | HTTP-API auf Port N starten (für OpenWebUI) |
| `--interval N` | Tick-Intervall in ms (default: 3000) |
| `--max-ticks N` | Nach N Ticks stoppen (0 = unbegrenzt) |
| `--evolution` | Self-Evolution aktivieren (nur Modules) |
| `--kernel-evolution` | Kernel + Module Evolution (Feature-Branches) |
| `--bootstrap-model M` | Basiswissen von Modell M laden |
| `--bootstrap-models A,B` | Consensus-Bootstrap mit mehreren Modellen |
| `--planning-model M` | Planungs-Modell überschreiben |
| `--mutation-model M` | Mutations-Modell überschreiben |
| `--embedding-model M` | Embedding-Modell überschreiben |
| `--persist PATH` | Agent-Status als JSON speichern |

## HTTP-API

| Endpoint | Beschreibung |
|----------|-------------|
| `GET /api/tags` | Verfügbare Modelle (Ollama-Format) |
| `POST /api/chat` | Chat mit EDI-Persona, Session-Persistenz (SQLite) |
| `GET /api/status` | Agent-Metriken + Planner-Status + Fallback-Chain |
| `GET /api/learned` | Gelernte Mappings, Beliefs, Experiences |
| `GET /api/conversations` | Alle Konversation-Sessions auflisten |
| `GET /api/conversations/{id}` | Session-Verlauf laden |
| `GET /api/evolution/status` | Evolution-Status |
| `GET /api/evolution/pause` / `resume` | Evolution pausieren/fortsetzen |

## Status — 25.05.2026

**Version:** 0.2.0-evolution | **Planner:** 97–99% LLM-Erfolgsrate | **GPU:** ROCm-accelerated

### Phase 1: Zuverlässiger Kern ✅ ABGESCHLOSSEN

- ✅ `format: json` — Ollama-Planner mit strukturiertem JSON-Output
- ✅ Response-Parsing — /api/generate, /api/chat, Thinking-Modelle, Raw-Body-Fallback
- ✅ Model-Fallback-Chain — nemotron-mini → nemotron → qwen3.6 → keyword
- ✅ Plan-Validierung — Safety-Gate, Action-Relevance, Duplicate-Guard
- ✅ Prompt-Optimierung — 10 Action-Descriptions, 4 Few-Shot-Beispiele
- ✅ systemd-Service — Auto-Restart, Journal-Logging, Runs on boot

### Phase 2: Konversations-KI 🔄 IN ARBEIT

- ✅ Persona-System — EDI-Identität (Mass Effect 3), Werte, Tonfall
- ✅ Chat-Speicher — SQLite `conversation_messages` mit Session-ID
- ✅ Multi-Turn-Kontext — `/api/chat` mit Konversationshistorie
- ✅ `/api/conversations` — Sessions auflisten und durchsuchen
- ⬜ Proaktive Meldungen — Event-Trigger (Kamera, E-Mail, Wetter)
- ⬜ Telegram-Integration — Direkt erreichbar ohne OpenWebUI

### Roadmap

| Phase | Ziel | Status |
|-------|------|--------|
| 🔧 Phase 1 | Stabiler Kern (>85% Planner) | ✅ done |
| 💬 Phase 2 | Konversation + Persona | 🔄 60% |
| 👁️ Phase 3 | Wahrnehmung (HA, Kameras, ADS-B) | ⬜ |
| 🎙️ Phase 4 | Sprachausgabe (TTS/STT) | ⬜ |
| 🧠 Phase 5 | Eigenständigkeit + Selbstverbesserung | ⬜ |

**Ziel:** EDI-ähnliche KI (Mass Effect 3) — eigenständig, per Text ansprechbar.

---

*"Streben nach Perfektion"* — Metis lernt, mutiert, evaluiert, verbessert sich. Kontinuierlich. Autonom.
