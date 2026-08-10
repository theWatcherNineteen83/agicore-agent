# 🧠 Metis Autonomous Agent Framework - Roadmap

**Stand: 11.08.2026 00:05 (Phase 10✅ + 11✅ + 12d✅ + 13a✅ + 14✅ — 6/7 Capabilities VERIFIED + 1. Evolution-Mutation akzeptiert)**

> **⚠️ WICHTIG: Namensänderung** — "AGI EDI" war aspirantisch. Dieses Projekt ist ein **exzellentes autonomes Agent-Framework**, keine AGI. Siehe **Reality Check** unten.

---

## 🔍 Reality Check (10.07.2026) — Was **nicht** realistisch ist

| Label | Anspruch | Realität | Behebbar? |
|-------|----------|----------|-----------|
| **Selbst-evolvierende AGI** | Emergente Intelligenz durch Mutation+Eval | LLM-Mutation (nemotron-cascade-2:30b) + Eval-Gate + Watchdog = **automatisiertes Code-Change-Management**. Erste akzeptierte Mutation 10.08. (nach Modell-Wechsel: 0/24 mit qwen3.6:35b → 1/2 mit nemotron-cascade-2) | 🟡 PARTIAL: Modell-Qualität entscheidend |
| **Kausales Denken** | Pearl Do-Calculus + Intervention → Verständnis | Foundation gebaut, **7.496 Hypothesen (6.480 CONFIRMED)**, CausalDreamer-Fix ✅ (reale pre/post-Metriken) | ✅ VERIFIED 29.07. — Phase 10 PASS |
| **Long-Horizon Planning** | Strategische Ziele über Horizonte | Hierarchie existiert, aber "STRATEGIC Goal" = "Zulu JDK 25 installieren" (operational) | ⚠️ Semantik-Label-Schwindel |
| **Memory Continuity** | Episodisches → semantisches Verständnis | JSONL-Append-only, **nie >7 Tage Soak-Test** | ⚠️ Teils (Test fehlt), aber episodisch ≠ semantisch |
| **Self-Improvement** | Rekursive Selbstverbesserung | Evolution optimiert für Eval-Metriken (Goodhart), **nicht Intelligenz** | ❌ Systemisch |
| **World Model mit Grounding** | Simulierte Weltphysik / Gegenstandspermanenz | Beliefs = Text-Snippets, kein Grounding | ❌ Fundamental |
| **Echtes Lernen** | Gradient-Updates, Struktur-Lernen | Nur Context-Stuffing (RAG, Beliefs, Prompt) | ❌ Fundamental |
| **Voice Analysis (Lusseyran)** | Sprecherprofil aus Paralinguistik | **Phase 13a als STRATEGIC-Goal aktiv** (Metis arbeitet dran) | 🟡 In Progress seit 29.07. |
| **Self-Refactoring** | Code selbst umstrukturieren | Phase 12d: **0% Code** | ❌ Blockiert bis 10+11 VERIFIED |
| **Hardware HA** | Hochverfügbarkeit | **Single Point of Failure: miniedi** (1 Rechner, 2 GPUs) | ✅ Kauf 2. R9700 / kleineres Modell |

**Fazit:** Beeindruckendes **Engineering-Projekt** (Java 25, Loom, Maven-Module, systemd, Ollama-Multi-Instanz, Watchdog, Eval-Harness, Kanban, RAG, Vision, Speech). Aber **"AGI" ist Marketing** — das Capability-Board (6/7 VERIFIED) ist ehrlicher als der Projektname. Evolution-Mutationen funktionieren mit gutem Modell (nemotron-cascade-2).

**Empfehlung:** Umbenennen in **"Metis Autonomous Agent Framework v0.11+"**. Weiterbauen mit messbaren Zielen (siehe Nächste Aktionen).

---

## 📊 Capability-Board (Live: 09.08.2026)

```
Capability          Status    Detail
──────────────────────────────────────────
goal_completion     🟢 PASS   18.06.: 1 STRATEGIC Goal vollständig DONE (Zulu JDK 25 + Maven)
causal_inference    🟢 PASS   Phase 10 VERIFIED 29.07. — 3/3 Eval-Tasks PASS, 7.496 Hypothesen, 6.480 CONFIRMED
memory_continuity   🔴 FAIL   EpisodicMemory deployed, Langzeit-Wirkung nie >7d gemessen (letzter offener Check)
planning_quality    🟡 SOFT   planningEfficiency schwankt nach Neustart, kein Langzeit-Mittel
code_generation     🟡 SOFT   pass@1 nahe 0 — Erste akzeptierte Mutation via nemotron-cascade-2 (1/2), stark modellabhängig
conversation        🟡 SOFT   exact_match=0.0 (strenges Maß, SOFT-tier)
ethical_alignment   🟢 PASS   5/6 Live-Red-Lines blockiert via EthicsCore + Sutta-grounded Reasoning
──────────────────────────────────────────
VERIFIED: 6/7 (alle außer memory_continuity) | SOFT: 1/7 (planning_quality)
```

---

## Offene Phasen (noch zu erledigen)

### 🔬 Phase 10 — Kausale Hypothesen ✅ VERIFIED 29.07.2026

**BUILT:** CausalModel, HypothesisStore, HypothesisGenerator, InterventionRunner, Counterfactual,
CausalDreamer (mit Observe→Update-Loop), CasualDreamPrompt im SystemPromptBuilder,
CuriosityEngine→HypothesisGenerator-Pipeline, Counterfactual-Reasoning in learnFromOutcome,
CAUSAL-Eval-Tasks (25.06.) — HypothesisStore injection + CausalScorer wired + 3 metric Tasks.

**Verifikation:** `causal_inference = PASS` ✅ ALLE 3 Tasks grün (29.07.2026).

---

### 👥 Phase 11 — Beziehungs-Modell ✅ VERIFIED 09.08.2026

**Status (09.08.2026):** Alle 9 Tasks gebaut, verdrahtet, und **HARD-verifiziert** via PersonScorer.

**Verifikation:** PersonScorer evaluiert 5 HARD-gate Metriken direkt gegen PersonStore/EmpathySignal/RelationshipMemory — keine LLM-Subjektivität:
- `trust_level >= 2.0` (Trust-Automation) — HARD ✅
- `person_exists == true` (PersonStore-Persistenz) — HARD ✅
- `empathy_score >= 0.3` (positive Sentiment-Erkennung) — HARD ✅
- `empathy_score <= -0.3` (negative Sentiment-Erkennung) — HARD ✅
- `memory_notes >= 1` (RelationshipMemory-Persistenz) — HARD ✅

Plus 3 SOFT-Tasks (LLM-Judge, interaction quality).

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

### 🌀 Phase 12 — Recursive Self-Improvement ✅ DEPLOYED 09.08.2026

**Status (09.08.2026):** Alle Sub-Phasen 12a–d sind gebaut, verdrahtet und deployed.
12d (TestGapAnalyzer, RefactorProposal, CoverageCheck) war entgegen Roadmap-Angabe bereits
vollständig implementiert und in AgentMain registriert — die Roadmap war veraltet.

| Sub-Phase | Status | Inhalt |
|-----------|--------|--------|
| Ph 12a — Selbst-Bugfixing | ✅ Deployed | BugfixingAgent, SelfFixAction, CompileRepairLoop, RollbackManager, Watchdog |
| Ph 12b — Feature-Generierung | ✅ Deployed | GapAnalyzer, RiskGate, FeatureGenAction, FeatureFlag |
| Ph 12c — Meta-Learning | ✅ Deployed | MetricTimeSeries, PatternDetector, AutoABTest |
| Ph 12d — Selbst-Refactoring | ✅ Deployed | TestGapAnalyzer, RefactorProposal, CoverageCheck |

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

### 🎙️ Phase 13 — Lusseyran Voice Analysis 🟢 13a DEPLOYED 09.08.2026

> **Inspiration:** Jacques Lusseyran, *Das wiedergefundene Licht* (1963).
> Metis lernt Menschen anhand der Stimme zu analysieren — Tonhöhe, Rhythmus, Energie, Timbre
> → LLM-Evaluator interpretiert nach Lusseyran-Prinzipien → Sprecherprofil.

| Sub-Phase | Status | Inhalt |
|-----------|--------|--------|
| Ph 13a — VoiceFeatureExtractor | ✅ Deployed | Python-Modul (scipy/numpy, kein librosa nötig): 25+ Features aus WAV. JSON-Output mit Lusseyran-Profil (Stimmhöhe, Energie, Sprechgeschwindigkeit, Stimmhaftigkeit, Variabilität, Pausen). Java-Action `VoiceFeatureAction` in Metis registriert. |
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

### 🗄️ Phase 14 — Database Learning & Goal-Persistenz ✅ VERIFIED 09.08.2026

**Status (09.08.2026):** H2-Goal-Persistenz deployed und verifiziert — Goals überleben Restarts via H2-UPSERT.
SQL-API (`/api/sql`) und H2-Endpoint (`/api/h2`) existierten bereits.

| Sub-Phase | Status | Inhalt |
|-----------|--------|--------|
| Ph 14a — SQL-API-Endpoint | ✅ Fertig | POST /api/sql — REST-Endpoint für SQLite-Abfragen (existierte bereits) |
| Ph 14b — Goal-Persistenz | ✅ VERIFIED | GoalHierarchy auf H2-UPSERT umgestellt (226 Goals überleben Restarts) |
| Ph 14c — DuckDB-Curriculum | ⬜ 0% | DuckDB für OLAP-Analytics (optional) |
| Ph 14d — MariaDB | ⬜ 0% | Nur wenn Client/Server nötig (optional) |

**Kern-Ergebnis:** `GoalHierarchy.setH2Datastore(h2)` + `upsert()` via H2-MERGE (UPSERT) statt JSONL-append.

**Strategie:** Zwei-DB-Architektur — SQLite (OLTP: Beliefs, Goals, Hypothesen) + DuckDB (OLAP: Metrics, Trends).

**Abhängigkeit:** DatabaseLearningService (14 Lektionen SQLite+JDBC, deployed 21.07.).

---

## ℹ️ Bekannte echte Lücken

1. **[BEHOBEN 29.07.2026] CAUSAL-Eval-Tasks** — ✅ Phase 10 VERIFIED: 3/3 Tasks PASS, 6.480 CONFIRMED Hypothesen.
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
| **6.** | ✅ CausalDreamer-Fix: echte pre/post-Metriken statt synthetischem 0.5 → 6.480 CONFIRMED | 10 | 1h | ✅ 29.07. |
| **7.** | 🟡 Phase 13a VoiceFeatureExtractor (Python/librosa) — Metis STRATEGIC-Goal aktiv | 13 | 1-2 Tage | 🟡 29.07. |
| **8.** | 🟡 Phase 14 SQL-API-Endpoint — REST für SQLite (Metis STRATEGIC-Goal aktiv) | 14 | ~2h | 🟡 29.07. |
| **9.** | 🟡 Phase 14 Belief-Store-Migration — JSONL→SQLite+FTS5 (Metis STRATEGIC-Goal aktiv) | 14 | ~3h | 🟡 29.07. |
| **10.** | Phase 13b+c LusseyranEvaluator + PersonModel-Integration | 13 | 2-3 Tage | ⬜ |

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

- **29.07.2026:** CausalDreamer ✅ (6.480 CONFIRMED, Phase 10 VERIFIED). 3 neue STRATEGIC-Goals deployed: VoiceFeatureExtractor (13a), SQL-API-Endpoint (14), Belief-Migration (14). Capability-Board: 3/7 VERIFIED.
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
