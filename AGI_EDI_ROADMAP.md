# 🧠 AGI EDI - Roadmap

**Stand: 03.07.2026 20:50 (Phase 13 — Drei-Instanz-Ollama, CPU-Embeddings, URLs parametrisierbar)**

---

## 📊 Capability-Board

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

**EDI-Fortschritt: 2/7 Capabilities verifiziert.**

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

### 🚧 Phase 11.5 — Initiative-Policy ⬜ BUILT 0% · VERIFIED ⬜

EDI spricht ungefragt an. Metis hat Bausteine (proaktive MQTT/Wetter→Telegram), aber keine Policy.

- [ ] **InitiativeLevel** Enum: SILENT / REACT_ONLY / NOTIFY / SUGGEST / CONVERSE
- [ ] **TrustLevel→InitiativeLevel-Mapping** (OWNER→CONVERSE, TRUSTED→SUGGEST, etc.)
- [ ] **InitiativeBudget** pro Person/Tag (max N proaktive Messages)
- [ ] **QuietHours** (22:00–08:00) — nur kritische Schwellwerte überschreiben
- [ ] **Sentiment-Gold-Set** für Phase-11 EmpathySignal — aus echten Telegram-Logs destillieren

---

## ℹ️ Bekannte echte Lücken

1. **CAUSAL-Eval-Tasks fehlen** — DatasetBuilder braucht HypothesisStore-Injection. CausalScorer ist fertig.
2. **memory_continuity 🔴** — EpisodicMemory + SelfNarrative + MoodSignal sind seit 31.05. aktiv, aber nie über 7 Tage getestet. `Continuity-Soak-Test` fehlt.
3. **PersonalityAnchor-Mirror im Watchdog** — read-only Copy für Phase 12, nirgends als Task gelistet.
4. **Prompt-Bloat-Tripwire** — `tokensPerCall` als Capability-Board-Metrik, Alarm bei >5000 (akt. 744 nach Neustart).
5. **Action-Diversity-Tripwire** — Watchdog-Alert wenn >70% Action-Usage auf eine Action.
6. **Goal-Source-Diversity-Metrik** — Welche Quelle erzeugt Goals? Wenn 95% aus einer → blinder Fleck.

---

## 🎯 Nächste Aktionen (priorisiert)

| Rang | Aktion | Phase | Aufwand | Status |
|------|--------|-------|---------|--------|
| **1.** | ✅ Phase 10 CAUSAL-Eval-Tasks: HypothesisStore injiziert + CausalScorer wired + 3 Tasks | 10 | ~2h | ✅ 25.06. |
| **2.** | ✅ Phase 11 PersonModel (100%): Trust-Automation + EmpathySignal + PersonAwareSystemPrompt + MultiPersonMemory + RelationshipMemory | 11 | 2-3 Tage | ✅ 04.07. |
| **3.** | Continuity-Soak-Test (7 Tage) für memory_continuity | 8 | passiv | ⬜ |
| **4.** | Initiative-Policy v1 (InitiativeLevel + TrustLevel-Mapping) | 11.5 | 1-2 Tage | ⬜ |
| **5.** | Phase 13a VoiceFeatureExtractor (Python/librosa) — Vorarbeit für Voice Analysis | 13 | 1-2 Tage | ⬜ |
| **6.** | Phase 13b+c LusseyranEvaluator + PersonModel-Integration | 13 | 2-3 Tage | ⬜ |

---

## 🖥 GPU-Setup (seit 03.07.)

| Instanz | Modell(e) | VRAM | Port | Service |
|--------|-----------|------|------|---------|
| GPU 1 — R9700 (32 GB) | qwen3.6:35b-a3b-q4_K_M (Planung) | 22.3 GB (70%) | 11434 | `ollama-gpu1.service` |
| GPU 0 — 7900 XTX (24 GB) | gemma4-26b + phi4-mini (OpenWebUI) | optional | 11436 | `ollama-gpu0.service` |
| CPU — Embeddings | nomic-embed-text (768-dim) | 308 MB RAM | **11438** | `ollama-cpu.service` |
| CPU — Mutation | granite-mini-agent | — | — | — |

**Drei Ollama-Instanzen:** GPU1 (Planung), CPU (Embeddings), GPU0 (OpenWebUI, optional).
Embedding- und Mutation-URL per CLI parametrisierbar (`--embedding-url`, `--mutation-url`).
