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
| `POST /api/chat` | Chat-Nachricht senden (Ollama-Format) |
| `GET /api/status` | Agent-Metriken (Ticks, Beliefs, Confidence) |
| `GET /api/evolution/status` | Evolution-Status |
| `GET /api/evolution/pause` | Evolution pausieren |
| `GET /api/evolution/resume` | Evolution fortsetzen |

## Technologien

- **Java 21+** (Zulu JDK)
- **Maven** Multi-Module (Kernel + Modules)
- **Ollama** für LLM-Inferenz (Planning, Mutation, Embeddings)
- **JDK HttpServer** für die REST-API (keine externen Dependencies)
- **Javac** für Compile-Checks bei Mutationen
- **Git** für Versionierung aller Evolution-Schritte

## Was Metis *nicht* ist

- ❌ Kein Chatbot — Metis ist ein autonomer Agent mit eigenem Antrieb
- ❌ Kein API-Wrapper um OpenAI — Metis denkt selbst, Ollama ist nur ein Werkzeug
- ❌ Keine Cloud-AGI — Metis läuft komplett lokal, keine Daten verlassen dein Netzwerk
- ❌ Nicht fertig — Metis entwickelt sich weiter, genau wie ihr Code

## Status

**Version:** 0.2.0-evolution  
**Kernel:** 39 Klassen (Cognitive Loop, WorldModel, SelfModel, Evolution, Safety)  
**Modules:** 10 Klassen (OllamaPlanner, MutationService, ModelRegistry, HTTP-API)  
**Getestet auf:** Ubuntu 24.04 + Kali Linux, Ryzen 7 5700G, RX 7900 XTX, 62 GB RAM

---

*"Streben nach Perfektion"* — Metis lernt, mutiert, evaluiert, verbessert sich. Kontinuierlich. Autonom.
