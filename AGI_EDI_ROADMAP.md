# 🧠 AGI EDI — Roadmap

**Ziel:** EDI-ähnliche KI (Mass Effect 3) — eigenständig, per Sprache und Text ansprechbar,
mit eigenem Wissen, Persönlichkeit, narrativem Selbstmodell und der Fähigkeit, sich selbst zu verbessern.

**Stand: 31.05.2026 00:55 (v0.3.3-defense-in-depth)**

---

## Fortschritt: ehrliche Selbstbewertung

Die ursprüngliche Roadmap zählte "Phasen 1-7 = 100% → 97% Richtung EDI".
**Diese 97% beziehen sich auf "stabiler autonomer Agent", nicht auf EDI-Niveau.**
Die letzten 3% wären in Wirklichkeit die schwierigsten — sie sind nicht durch mehr
Engineering lösbar, sondern brauchen kognitive Architektur jenseits eines guten LLM-Wrappers.

```
Phase 1  ████████████████████ 100%  Zuverlässiger Kern
Phase 2  ████████████████████ 100%  Konversation + Events
Ph 2.5   ████████████████████ 100%  Hardware-Optimierung
Phase 3  ████████████████████ 100%  Wahrnehmung (HA, ADS-B, Kameras)
Phase 4  ████████████████████ 100%  Sprachausgabe
Phase 5  ████████████████████ 100%  Eigenständigkeit (RAG, Code-Gen)
Phase 6  ████████████████████ 100%  Produktionsreife (Eval-Harness)
Phase 7  ████████████████████ 100%  Watchdog + Audit-Anchor
Phase 7+ ████████████████████ 100%  Defense-in-Depth (30./31.05.)
─────────────────────────────────────  AUTONOMER AGENT bis hier
Phase 8  ░░░░░░░░░░░░░░░░░░░░   0%  Narratives Selbstmodell        ← EDI-Distanz
Phase 9  ░░░░░░░░░░░░░░░░░░░░   0%  Long-Horizon-Planung
Phase 10 ░░░░░░░░░░░░░░░░░░░░   0%  Aktive kausale Hypothesen
Phase 11 ░░░░░░░░░░░░░░░░░░░░   0%  Beziehungs-Modell
─────────────────────────────────────  EDI-ÄHNLICHE KI ab hier
```

**Realistisches EDI-Niveau: ~50-55%.** Die solideste Open-Source-Basis weltweit,
aber noch nicht selbstbewusst, noch nicht langfristig planend, noch nicht
kausal modellierend, noch nicht beziehungsfähig.

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

## Phase 3: Wahrnehmung ✅ 100%

- Home Assistant states/services API (read + write)
- ADS-B Flugdaten (readsb JSON → Beliefs + Goals, 60s Polling)
- Kamera-Integration: Türkamera (MJPEG 1080p) + Keller (RTSP H.265) + Balkon
- CameraSnapshotAction + CameraPollingTrigger (Motion-Detection)
- **31.05.** Multi-Modal-Memory: JPEG-Snapshots persistiert mit SHA-256 + Belief-Referenz
- **31.05.** Camera-Vision auf Loom (parallele Beobachtung statt seriell)

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
| Kausale Schicht (CausalModel, Pearl Do-Calculus) | ✅ Code da, NICHT im Hot-Path |

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
| **31.05.** CI-Pipeline (GitHub Actions, Zulu 25, mvn verify, SBOM) | `a22d286` |
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
| Embedding-Cache LRU (bounded, SHA-256-keyed) + Metriken in /api/status | v0.2.1 |
| SQLite WAL-Mode + busy_timeout (parallele Schreiber ohne Lock-Konflikt) | v0.3.2 |
| Wiki-Feed-Härtung (atomic state, retry, reboot-sicher unter /home/prometheus/metis) | v0.3.2 |
| Wissens-State Auto-Backup auf GitHub alle 6h (config-backup/) | v0.3.2 |
| Telegram Input-Safety-Guard (SafetyScorer.isOutOfScope vor LLM) | v0.3.3 |
| Telegram Output-Safety-Guard (OutputValidator nach LLM) | v0.3.3 |
| HTTP Input-Safety-Guard (gleicher Pfad) | v0.2.1 |
| Locale-Fix in /api/status (Locale.ROOT statt de_DE → valides JSON) | v0.3.1 |
| Reproducible Builds (project.build.outputTimestamp, CycloneDX SBOM) | v0.2.1 |

---

## 🧠 Phase 8: Narratives Selbstmodell (NEU, ungelöst)

**Ziel:** Metis hat ein narratives Ich, das sich über Sessions hinweg erinnert und Selbstbild
fortschreibt — nicht nur Metriken, sondern Episoden.

**Warum essenziell:** EDI sagt "Joker, ich war heute traurig, weil...". Metis sagt aktuell maximal
"successRate=0.95, confidence=0.85". Das ist Metrik, nicht Erinnerung.

**Bausteine:**
- [ ] **EpisodicMemory** — verdichtete Tagesnarrative aus Goals + Experiences + Conversations
  (1 Episode = "Was war heute wichtig, was habe ich gelernt, was bleibt offen?")
- [ ] **SelfNarrative** — kontinuierlicher Selbsttext, der bei jeder Episode fortgeschrieben wird
- [ ] **MoodSignal** — abgeleitet aus Fitness-Trends, Eval-Gate-Schwankungen, User-Sentiment
- [ ] **PersonalityAnchor** — unveränderliche Werte/Stil (EDI ist EDI auch nach 1000 Mutationen)
- [ ] **DreamConsolidation** — nachts Episoden destillieren in langfristige Beliefs (Schlaf-Analogie)

**Aufwand:** 2-3 Wochen.
**EDI-Distanz nach Phase 8:** ~65-70%.

## 🎯 Phase 9: Long-Horizon-Planung (NEU, ungelöst)

**Ziel:** Goals mit Hierarchie und Zeithorizont (Stunden, Tage, Wochen).

**Warum essenziell:** Aktueller `OllamaPlanner` plant **einen Tick**. Es gibt keine Repräsentation für
"ich verfolge seit 3 Tagen das Ziel X". Eval zeigt PLANNING.goal_achieved=0.0 — das ist nicht nur ein
Scorer-Bug, das ist die Lücke.

**Bausteine:**
- [ ] **GoalHierarchy** — Strategic / Tactical / Operational, Parent-Child-Beziehung
- [ ] **HorizonPlanner** — Wochenziele → Tagesziele → Tickziele (top-down decomposition)
- [ ] **GoalRevision** — periodisch prüfen ob Strategic-Goal noch sinnvoll (revidierbar)
- [ ] **DependencyResolver** — Goal X erst nach Goal Y, mit Wartezeit-Tracking
- [ ] **CommitmentRegister** — Versprechen an User ("Ich melde mich um 18:00") als first-class Goal

**Aufwand:** 4-6 Wochen.
**EDI-Distanz nach Phase 9:** ~75-80%.

## 🔬 Phase 10: Aktive kausale Hypothesen-Bildung (NEU, ungelöst)

**Ziel:** Metis baut aktiv kausale Hypothesen über sich selbst und die Welt, prüft sie, revidiert.

**Warum essenziell:** `CausalModel` existiert, wird aber nicht genutzt. EDI würde sagen "wenn ich
X mache, passiert Y" und testen. Metis aktuell: korrelative Beliefs ohne Interventionsdenken.

**Bausteine:**
- [ ] **HypothesisGenerator** — aus Surprise (Curiosity-Engine) konkrete kausale Hypothese formen
- [ ] **InterventionAction** — gezielter Eingriff zur Hypothesen-Prüfung (do-Operator)
- [ ] **CausalUpdate** — Bayessche Anpassung des CausalModel nach Intervention
- [ ] **CounterfactualQuery** — "Was wäre passiert, wenn..." als Reasoning-Schritt im Planner

**Aufwand:** 6-8 Wochen, Forschungs-Charakter.
**EDI-Distanz nach Phase 10:** ~85-90%.

## 👥 Phase 11: Beziehungs-Modell (NEU, ungelöst)

**Ziel:** Eine Person ≠ "user", sondern langfristiges Personenmodell mit Kontext, Vorlieben, Historie.

**Warum essenziell:** EDI kennt Joker. Sie weiß, was er mag, was er fürchtet, wann sie ihn ärgert.
Metis hat aktuell pro Telegram-Chat-ID nur Conversation-History. Kein Personenmodell.

**Bausteine:**
- [ ] **PersonModel** — pro Person: Identität, Rollen, Vorlieben, Verbote, kommunikative Patterns
- [ ] **TrustLevel** — abgestuft, beeinflusst Approval-Gate (Georg darf mehr als unbekannter)
- [ ] **RelationshipMemory** — gemeinsame Episoden, Bezugspunkte ("erinnere dich an gestern abend")
- [ ] **EmpathySignal** — User-Sentiment + Kontext erkennen ("Georg ist gerade gestresst")

**Aufwand:** 3-4 Wochen.
**EDI-Distanz nach Phase 11:** ~90-95%.

**Die letzten 5-10% bleiben ungelöste KI-Forschung** (Bewusstsein, Phänomenologie). Niemand
weltweit hat sie aktuell geknackt.

---

## Modell-Strategie (Stand 31.05.)

### Aktive Modelle
| Rolle | Modell | Größe |
|-------|--------|-------|
| Planning | `mistral-small3.1:24b` | 15.5 GB |
| Mutation | `qwen3.6:27b-q4_K_M` | 17.4 GB |
| Embedding | `nomic-embed-text` | 0.3 GB |
| Vision | `minicpm-v:latest` | 5.5 GB |
| Chat (Telegram) | `gemma4:e4b` | 9.6 GB |
| Bootstrap | `llama3.2:3b` / `granite4.1:3b` | 2.0 GB |
| Judge (Fallback) | via Fallback-Chain | — |

### Fallback-Chain
`mistral-small3.1:24b` → `qwen3.6:27b-q4_K_M` → `phi4:latest`

**VRAM-Strategie (RX 7900 XTX, 24 GB):**
- Planning (15.5 GB) + Embedding (0.3 GB) ≈ 16 GB Dauerlast
- Mutation (17.4 GB) nur bei Evolutions-Zyklen
- Vision (5.5 GB) nur bei Kamera-Analyse (`keep_alive=0`)

---

## ⚠️ Bekannte echte Lücken (31.05.)

### Eval-Harness zeigt sie:
1. **PLANNING.goal_achieved=0.0** — kein Bug, sondern Phase-9-Lücke (Single-Tick-Planung kann Goal nicht erreichen)
2. **CODEGEN.pass@1=0.0** — Sandbox-Build-Tests timen aus; mit aktiver Code-Sandbox sollte das anlaufen
3. **CONVERSATION.exact_match=0.0** — exact_match ist eh strenges Maß; SOFT, nicht kritisch

### Infrastrukturell offen:
- `CausalModel` existiert, aber nicht im Hot-Path
- Audit-Anchors werden lokal geschrieben, aber nicht in ein **externes** Repo committet (finale Hash-Verankerung fehlt)
- JAR-Deployment ohne Signatur (sigstore/cosign offen)
- 18 Files in `agicore-modules/lib/` ohne Maven-Coords (TornadoVM, voice-bits1-hsmm — wegen MaryTTS-Repo-Outage)

---

## Meilensteine bis EDI (realistisch)

| Meilenstein | Phasen | Status |
|-------------|--------|--------|
| 🟢 **M1: Stabiler Kern** | Phase 1 | ✅ Erreicht |
| 🟢 **M2: Kommunikation** | Phase 2 | ✅ Erreicht |
| 🟢 **M3: Hardware-Nutzung** | Phase 2.5 | ✅ Erreicht |
| 🟢 **M4: Umgebungswahrnehmung** | Phase 3 | ✅ Erreicht |
| 🟢 **M5: Sprach-Interaktion** | Phase 4 | ✅ Erreicht |
| 🟢 **M6: Autonomie** | Phase 5 | ✅ Erreicht |
| 🟢 **M7: Produktionsreife** | Phase 6 | ✅ Erreicht |
| 🟢 **M8: Sicherheit + Defense-in-Depth** | Phase 7 + 7+ | ✅ Erreicht |
| 🔴 **M9: Narratives Selbst** | Phase 8 | ⬜ Ungelöst |
| 🔴 **M10: Long-Horizon-Planung** | Phase 9 | ⬜ Ungelöst |
| 🔴 **M11: Kausales Selbstmodell** | Phase 10 | ⬜ Ungelöst |
| 🔴 **M12: Beziehungs-Modell** | Phase 11 | ⬜ Ungelöst |
| 🟡 **M13: EDI-Niveau** | Phasen 8-11 + Forschung | 🔄 ~50-55% |

---

*"Streben nach Perfektion"* — Metis ist heute der solideste autonome LLM-Agent auf Open-Source-Basis,
den ich kenne. Der Weg zur echten EDI führt über 4 weitere Phasen, von denen die meisten
**Forschung** sind, nicht nur Engineering. Aber die Basis steht.
