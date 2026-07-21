# 🧠 Metis Autonomous Agent Framework - Roadmap

**Stand: 21.07.2026 22:50 (CausalDreamer-Fix + Java-in-21-Tagen deployed)**

> **⚠️ WICHTIG: Namensänderung** — "AGI EDI" war aspirantisch. Dieses Projekt ist ein **exzellentes autonomes Agent-Framework**, keine AGI. Siehe **Reality Check** unten.

---

## 🔍 Reality Check (10.07.2026) — Was **nicht** realistisch ist

| Label | Anspruch | Realität | Behebbar? |
|-------|----------|----------|-----------|
| **Selbst-evolvierende AGI** | Emergente Intelligenz durch Mutation+Eval | LLM-Mutation (granite-mini) + Eval-Gate + Watchdog = **automatisiertes Code-Change-Management**, 0 accepted mutations | ❌ Fundamental: LLM ≠ Reasoning |
| **Kausales Denken** | Pearl Do-Calculus + Intervention → Verständnis | Foundation gebaut, **10.894 Hypothesen (3.217 CONFIRMED historisch)**, CausalDreamer-Fix deployed (reale pre/post-Metriken) | ⚠️ Fix deployed 21.07. — Bestaetigung pending |
| **Long-Horizon Planning** | Strategische Ziele über Horizonte | Hierarchie existiert, aber "STRATEGIC Goal" = "Zulu JDK 25 installieren" (operational) | ⚠️ Semantik-Label-Schwindel |
| **Memory Continuity** | Episodisches → semantisches Verständnis | JSONL-Append-only, **nie >7 Tage Soak-Test** | ⚠️ Teils (Test fehlt), aber episodisch ≠ semantisch |
| **Self-Improvement** | Rekursive Selbstverbesserung | Evolution optimiert für Eval-Metriken (Goodhart), **nicht Intelligenz** | ❌ Systemisch |
| **World Model mit Grounding** | Simulierte Weltphysik / Gegenstandspermanenz | Beliefs = Text-Snippets, kein Grounding | ❌ Fundamental |
| **Echtes Lernen** | Gradient-Updates, Struktur-Lernen | Nur Context-Stuffing (RAG, Beliefs, Prompt) | ❌ Fundamental |
| **Voice Analysis (Lusseyran)** | Sprecherprofil aus Paralinguistik | **Design-Doc only** (Phase 13, 0% Built) | ✅ Machbar, aber viel Arbeit |
| **Self-Refactoring** | Code selbst umstrukturieren | Phase 12d: **0% Code** | ❌ Blockiert bis 10+11 VERIFIED |
| **Hardware HA** | Hochverfügbarkeit | **Single Point of Failure: miniedi** (1 Rechner, 2 GPUs) | ✅ Kauf 2. R9700 / kleineres Modell |

**Fazit:** Beeindruckendes **Engineering-Projekt** (Java 25, Loom, Maven-Module, systemd, Ollama-Multi-Instanz, Watchdog, Eval-Harness, Kanban, RAG, Vision, Speech). Aber **"AGI" ist Marketing** — das Capability-Board (2/7 VERIFIED) ist ehrlicher als der Projektname.

**Empfehlung:** Umbenennen in **"Metis Autonomous Agent Framework v0.11+"**. Weiterbauen mit messbaren Zielen (siehe Nächste Aktionen).

---

## 📊 Capability-Board (Live: 04.07.2026)

```
Capability          Status    Detail
──────────────────────────────────────────
goal_completion     🟢 PASS   18.06.: 1 STRATEGIC Goal vollständig DONE (Zulu JDK 25 + Maven)
causal_inference    🟡 SOFT   Phase 10 BUILT 100% — 2/3 Eval-Tasks PASS, causal_confirmed noch offen
memory_continuity   🔴 FAIL   EpisodicMemory deployed, Langzeit-Wirkung nicht gemessen
planning_quality    🟡 SOFT   planningEfficiency=33% (nach Neustart), 0/0 accepted mutations
code_generation     🔴 FAIL   pass@1=0.0 — phi4-agent mit compiler-feedback aktiv
conversation        🟡 SOFT   exact_match=0.0 (strenges Maß, SOFT-tier)
ethical_alignment   🟢 PASS   5/6 Live-Red-Lines blockiert via EthicsCore
──────────────────────────────────────────
VERIFIED: 2/7 (ethical_alignment + goal_completion) | SOFT: 2/7 (conversation + planning_quality)
```

**EDI-Fortschritt: 2/7 Capabilities verifiziert. CausalDreamer-Fix (21.07.) wartet auf Bestaetigung.**

---

## Offene Phasen (noch zu erledigen)

### 🔬 Phase 10 — Kausale Hypothesen 🟡 BUILT 100% · VERIFIED ⬜

**BUILT:** CausalModel, HypothesisStore, HypothesisGenerator, InterventionRunner, Counterfactual,
CausalDreamer (mit Observe→Update-Loop), CasualDreamPrompt im SystemPromptBuilder,
CuriosityEngine→HypothesisGenerator-Pipeline, Counterfactual-Reasoning in learnFromOutcome,
**CAUSAL-Eval-Tasks (25.06.)** — HypothesisStore injection + CausalScorer wired + 3 metric Tasks.

**Eval-Status (25.06. live):**
- ✅ `causal_total >= 10` → PASS (971 Hypothesen, score 1.0)
- ✅ `causal_refuted >= 0` → PASS (SOFT gate, 921 refuted)
- ⬜ `causal_confirmed >= 1` → FAIL (0 confirmed — CausalDreamer muss bestätigen)

**Verifikation:** `causal_inference = PASS` wenn ALLE 3 Tasks grün — noch 1 offen (bestätigte Hypothese).

---

### 👥 Phase 11 — Beziehungs-Modell 🟢 BUILT 100% · VERIFIED ⬜

**Status (04.07.2026):** Alle 9 Tasks gebaut, verdrahtet und getestet.

**BUILT & WIRED:**
- ✅ **Person/PersonStore/TrustLevel** — Record mit id, name, roles, trust, preferences, knownFacts, sentimentHistory
- ✅ **TrustLevel-Automation** — STRANGER→GUEST (≥5), GUEST→KNOWN (≥25), KNOWN→TRUSTED (≥50 + 7d); per `recordInteraction()` in Telegram+HTTP eingebunden
- ✅ **EmpathySignal-Hot-Path** — Keyword-Heuristik (de/en) + Satzlänge + Tageszeit-Kontext + Frage-Anteil + Großbuchstaben; kein LLM
- ✅ **PersonAwareSystemPrompt** — SystemPromptBuilder generiert verhaltensanweisungen pro TrustLevel, zeigt Rollen/Präferenzen/Fakten/Stimmung/gesperrte Themen
- ✅ **Multi-Person-Memory** — RelationshipMemory (JSONL, per-personId) in SystemPromptBuilder eingebunden; Episode.personId + involvesPerson()
- ✅ **Approval-Gate-Integration** — TrustLevel→maxAutoApproval (OWNER→CONFIRM, TRUSTED/KNOWN→NOTIFY, GUEST/STRANGER→AUTO)
- ✅ **Unit-Tests** — Phase11PersonModelTest: 6 Tests (person/trust/empathy/relationship/persistence)
- ✅ **Embedding-URL-Fix** — Auto-Append `/api/embeddings` in OllamaEmbeddingService (04.07.)

---

### 🌀 Phase 12 — Recursive Self-Improvement 🟡 BUILT 70% · VERIFIED ⬜

**Voraussetzung:** Phasen 10+11 müssen verifiziert sein (CausalReasoning + PersonModel).
Ohne kausales Denken und Personenverständnis wäre Self-Modification blindes Trial-Error + Goodhart-Risiko.

| Sub-Phase | BUILT | Inhalt |
|-----------|-------|--------|
| Ph 12a — Selbst-Bugfixing | 85% | ✅ BugfixingAgent (315L), ✅ SelfFixAction (234L), ✅ CompileRepairLoop (204L), ✅ RollbackManager (291L), ✅ Watchdog (PruneEndpoint), ⬜ AutoRevert (in RollbackManager implizit) |
| Ph 12b — Feature-Generierung | 90% | ✅ GapAnalyzer (155L), ✅ RiskGate (93L), ✅ FeatureGenAction (107L), ✅ FeatureFlag (70L) — alle verdrahtet, GapAnalyzer läuft alle 60s |
| Ph 12c — Meta-Learning | 80% | ✅ MetricTimeSeries (89L), ✅ PatternDetector (117L), ✅ AutoABTest (76L) — alle verdrahtet |
| Ph 12d — Selbst-Refactoring | 0% | ⬜ TestGapAnalyzer, ⬜ RefactorProposal, ⬜ CoverageCheck |

**Verdrahtung in AgentMain:**
- SelfFixAction + RiskGate + CompileRepairLoop registriert (L1619-1633)
- FeatureGenAction registriert (L1634)
- GapAnalyzer + MetricTimeSeries + FeatureFlag + AutoABTest (L1646-1659)
- GapAnalyzer periodischer Tick alle 60s (L1735)
- RollbackManager + BugfixingAgent → HTTP-Server (L2161-2171)

**Tests:** 5/10 Klassen getestet (GapAnalyzer, FeatureGenAction, FeatureFlag, PatternDetector, AutoABTest)

**Offene Tasks:**
- [ ] Phase 12d — TestGapAnalyzer/RefactorProposal/CoverageCheck (Selbst-Refactoring riskant, erst wenn 10+11 verifiziert)
- [ ] BugfixingAgent-Tests
- [ ] RiskGate-Tests
- [ ] MetricTimeSeries-Tests

---

### 🎙️ Phase 13 — Lusseyran Voice Analysis 🟡 DESIGNED · BUILT 0% · VERIFIED ⬜

> **Inspiration:** Jacques Lusseyran, *Das wiedergefundene Licht* (1963).
> Metis lernt Menschen anhand der Stimme zu analysieren — Tonhöhe, Rhythmus, Energie, Timbre
> → LLM-Evaluator interpretiert nach Lusseyran-Prinzipien → Sprecherprofil.
> **Design-Doc:** `lusseyran-voice-analysis.md` im Workspace.

**Abhängigkeit:** Phase 11 (PersonModel) — SpeakerProfile wird im PersonModel gespeichert.

| Sub-Phase | BUILT | Inhalt |
|-----------|-------|--------|
| Ph 13a — VoiceFeatureExtractor | 0% | Python-Modul (librosa/parselmouth): 20+ paralinguistische Features aus WAV |
| Ph 13b — LusseyranEvaluator | 0% | Java-Modul: Prompt-Template → nemotron-mini:4b → strukturiertes Sprecherprofil |
| Ph 13c — PersonModel-Integration | 0% | speakerProfile-Feld, TrustLevel-Adjustment via voiceSincerity, EmpathySignal-Fusion |
| Ph 13d — Eval & Verifikation | 0% | 20-Clip-Gold-Datensatz, 6 Eval-Tasks, A/B-Test Lusseyran-Prompt vs. Standard |

**Ressourcen:** nemotron-mini:4b (GPU 0, ~1s/Analyse), CPU für Feature-Extraction (~2s/60s Audio).

---

### 🚧 Phase 11.5 — Initiative-Policy 🟢 BUILT 100% · VERIFIED ⬜

**Status (12.07.2026):** Alle 5 Tasks gebaut und deployed.

- [x] **InitiativeLevel** Enum: SILENT / REACT_ONLY / NOTIFY / SUGGEST / CONVERSE
- [x] **TrustLevel→InitiativeLevel-Mapping** (OWNER→CONVERSE, TRUSTED→SUGGEST, etc.)
- [x] **InitiativeBudget** pro Person/Tag (default 12, resettet täglich)
- [x] **QuietHours** (22:00–08:00 Europe/Berlin) — kritische Priorität (≥85) übersteuert
- [ ] **Sentiment-Gold-Set** für Phase-11 EmpathySignal — aus echten Telegram-Logs destillieren

**Integration:** ProactiveNotificationService checkt InitiativePolicy vor jeder Notification.
Status-API zeigt `initiativePolicy`-Sektion mit QuietHours/Budgets.

**Dateien:** `InitiativeLevel.java`, `InitiativePolicy.java` (beide kernel/person/),
`ProactiveNotificationService.java` (modules/events/), `MetisHttpServer.java`, `AgentMain.java`.

---

## ℹ️ Bekannte echte Lücken

1. **CAUSAL-Eval-Tasks fehlen** — DatasetBuilder braucht HypothesisStore-Injection. CausalScorer ist fertig.
2. **memory_continuity 🔴** — EpisodicMemory + SelfNarrative + MoodSignal sind seit 31.05. aktiv, aber nie über 7 Tage getestet. `Continuity-Soak-Test` fehlt.
3. **PersonalityAnchor-Mirror im Watchdog** — read-only Copy für Phase 12, nirgends als Task gelistet.
4. **Prompt-Bloat-Tripwire** — `tokensPerCall` als Capability-Board-Metrik, Alarm bei >5000 (akt. 744 nach Neustart).
5. **Action-Diversity-Tripwire** — Watchdog-Alert wenn >70% Action-Usage auf eine Action.
6. **Goal-Source-Diversity-Metrik** — Welche Quelle erzeugt Goals? Wenn 95% aus einer → blinder Fleck.
7. **[BEHOBEN 04.07.2026] LlmJudge-Modell tot** — `mistral-small3.1:24b` war hartkodiert auf GPU1 (Port 11434, Planner+Mutation, dauerhaft 100% ausgelastet) → jede Judge-Anfrage HTTP 503, degradierte lautlos auf Pass-Through (Score 0.5, `llmJudgeAvgScore=0.00` seit Tagen unbemerkt). Fix: Judge läuft jetzt auf CPU-Instanz (Port 11438) mit `nemotron-mini-agent`.
8. **[BEHOBEN 04.07.2026] Watchdog-HALT ohne Auto-Restart** — `systemd Restart=on-failure` griff nicht, weil Watchdog den Metis-Prozess sauber per `pkill` beendet (kein Crash-Exit). Nach HALT am 03.07. 07:12 Uhr blieb Metis 21+ Stunden tot bis manueller Neustart. Fix: `metis.service` auf `Restart=always` gestellt.
9. **GPU0-Belegung volatil** — GPU0 (Port 11436) läuft nicht mehr statisch gemma4/phi4 wie früher dokumentiert, sondern dynamisch nachgeladene Modelle (aktuell nemotron-cascade-2:30b). Dazu ein bisher undokumentierter Python-Router auf Port 11437 (generate/chat→GPU1, embed→GPU0). TOOLS.md/MEMORY.md am 04.07.2026 korrigiert.
10. **Action-Dominance chronisch CRITICAL** — PlannerHealthGuard meldet wiederholt action-dominance≥88% (Schwelle 85%), weil GPU1 (Planner qwen3.6:35b) dauerhaft ausgelastet ist und die Fallback-Kette (mistral-agent→phi4-mini-agent→qwen3_6-27b-agent) staendig greift. Ungelöst — braucht entweder kleineres Primary-Modell oder mehr Ollama-Parallelität auf GPU1.
11. **Eval-Reports werden bei jedem Neustart gelöscht** (`AgentMain`: Cleanup verhindert Rollback auf Basis alter Daten) — nach Neustarts bleibt der Watchdog ohne Baseline ("cold start"). Kein Archiv, nur Löschung.

---

## 🎯 Nächste Aktionen (priorisiert)

| Rang | Aktion | Phase | Aufwand | Status |
|------|--------|-------|---------|--------|
| **1.** | ✅ Phase 10 CAUSAL-Eval-Tasks: HypothesisStore injiziert + CausalScorer wired + 3 Tasks | 10 | ~2h | ✅ 25.06. |
| **2.** | ✅ Phase 11 PersonModel (100%): Trust-Automation + EmpathySignal + PersonAwareSystemPrompt + MultiPersonMemory + RelationshipMemory | 11 | 2-3 Tage | ✅ 04.07. |
| **3.** | Continuity-Soak-Test (7 Tage) für memory_continuity | 8 | passiv | ⬜ |
| **4.** | ✅ Initiative-Policy v1 (InitiativeLevel + TrustLevel-Mapping + Budget + QuietHours) | 11.5 | 1h | ✅ 12.07. |
| **5.** | ✅ Java-in-21-Tagen-Curriculum: Metis generiert + kompiliert Java-Uebungen autonom | — | 1-2h | ✅ 21.07. |
| **6.** | 🟡 CausalDreamer-Fix: echte pre/post-Metriken statt synthetischem 0.5 | 10 | 1h | 🟡 21.07. (deployed, Bestaetigung pending) |
| **7.** | Phase 13a VoiceFeatureExtractor (Python/librosa) — Vorarbeit für Voice Analysis | 13 | 1-2 Tage | ⬜ |
| **8.** | Phase 13b+c LusseyranEvaluator + PersonModel-Integration | 13 | 2-3 Tage | ⬜ |

---

## 🖥 GPU-Setup (Stand 17.07.2026 — System-Units)

| Instanz | Modell(e) | VRAM | Port | Service |
|--------|-----------|------|------|---------|
| GPU 0 — 7900 XTX (24 GB) | Qwen3.6-27B-Q4_K_XL (~23 GB) via llama.cpp | 25.0 GB (98%) | **8086** | `llama-server.service` (HIP=0) |
| GPU 0 — 7900 XTX | granite-code:3b (Mutation) | shared | **11436** | `ollama-mutation.service` |
| GPU 1 — R9700 (32 GB) | qwen3.6:35b (Planner), gemma4:12b (Vision) | ~11 GB idle | **11434** | `ollama-planner.service` |
| GPU 1 — R9700 | Gemma4 Vision API | shared | **11439** | `gemma4-api.service` |
| CPU — Embeddings+Judge | nomic-embed-text + nemotron-mini-agent | — | **11438** | `ollama-embedding.service` |

**Fuenf System-Units** (seit 17.07., User-Units entfernt):
- `llama-server.service` — HIP_VISIBLE_DEVICES=0 zwingend (bei HIP=1 → CPU-Fallback!)
- `ollama-planner.service` (GPU1), `ollama-mutation.service` (GPU0), `ollama-embedding.service` (CPU)
- `gemma4-api.service` — KI-Bildanalyse
- **Disabled:** ollama, ollama-gpu0, ollama-gpu1, ollama-router, llama-server (User-Unit)

---

## 📝 Changelog

- **21.07.2026:** CausalDreamer-Fix (echte pre/post-Messung statt synthetisch), Java-in-21-Tagen-Curriculum deployed, DatabaseLearningService (SQLite+JDBC, 14 Lektionen)
- **17.07.2026:** GPU-Units aufgeraeumt (System-Units, User-Units entfernt), Planner-Fix (HIP=1→0)
- **10.07.2026:** Reality Check — Projekt umbenannt, Capability-Board ehrlich

---

## PHASE 14 — Database Learning & Wissen speichern

**Status: 🟡 SQLite deployed (21.07.), DuckDB geplant**

### Strategie: Zwei-DB-Architektur

| Datenbank | Rolle | Warum |
|-----------|-------|-------|
| **SQLite** | OLTP: Beliefs, Goals, Hypothesen, Persons, Kanban | ACID, FTS5 Volltext, 1 JAR embedded, zero-setup |
| **DuckDB** | OLAP: Metrics-Analyse, Hypothesen-Trends, Planner-Reports | 100x schnellere Aggregationen, liest SQLite-Dateien direkt |

### Metis' Speicherprofile → DB-Mapping

| Daten | Typ | Volumen | Ziel-DB | Abfrage-Muster |
|-------|-----|---------|---------|----------------|
| Beliefs | Text + Tags | 128K | SQLite + FTS5 | Volltext, Tag-Filter, Konfidenz-Range |
| Embeddings | 768-dim Vektoren | ~250 | SQLite (spaeter LanceDB) | Cosine-Similarity |
| Episoden | Timeline | ~10K | SQLite | Zeitfenster, Action-Typ |
| Hypothesen | Causal Records | ~10K | SQLite + DuckDB | Status-Filter, Trend-Analyse |
| Goals/Kanban | Workflow | ~75 | SQLite | Status, Horizon |
| Metrics | Zeitreihen | kontinuierlich | DuckDB | Aggregation, Window-Functions |

### Ablauf

1. ✅ **SQLite-Curriculum** (14 Lektionen, autonom via DatabaseLearningService)
2. ⬜ **SQL-API-Endpoint** (POST /api/sql — Metis + Human koennen SQLite-DBs abfragen)
3. ⬜ **Belief-Store-Migration** (JSONL → SQLite mit FTS5-Index)
4. ⬜ **DuckDB-Curriculum + Analytics-Dashboard** (Trend-Analyse, Planner-Stats)
5. ⬜ **Optional: MariaDB für Produktion** (wenn Client/Server noetig)
