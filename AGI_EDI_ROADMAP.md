# 🧠 AGI EDI - Roadmap

**Stand: 18.06.2026 19:40 (Phase 10 Intervention→Observe→Update-Loop + Counterfactual-Reasoning + CausalScorer)**

---

## 📊 Capability-Board

```
Capability          Status    Detail
──────────────────────────────────────────
goal_completion     🟢 PASS   18.06.: 1 STRATEGIC Goal vollständig DONE (Zulu JDK 25 + Maven)
causal_inference    🔴 FAIL   CausalDreamer + Hot-Path deployed, CAUSAL-Eval-Tasks fehlen im DatasetBuilder
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

### 🔬 Phase 10 — Kausale Hypothesen ✅ BUILT ~75% · VERIFIED ⬜

**BUILT:** CausalModel, HypothesisStore, HypothesisGenerator, InterventionRunner, Counterfactual,
CausalDreamer (mit Observe→Update-Loop), CasualDreamPrompt im SystemPromptBuilder,
CuriosityEngine→HypothesisGenerator-Pipeline, Counterfactual-Reasoning in learnFromOutcome.

**Letzter offener Task (1/6):**
- [ ] **CAUSAL-Eval-Tasks im DatasetBuilder** — HypothesisStore muss in EvalDatasetBuilder injiziert werden.
  CausalScorer ist deployed (prüft confirmed/total/open), aber es gibt keine CAUSAL-EvalTask-Definitionen.
  → Nach Injection generiert der DatasetBuilder 3 Tasks: `confirmed >= 1`, `total >= 10`, `refuted >= 0`.

**Verifikation:** `causal_inference = PASS` wenn CAUSAL-Eval-Tasks im nächsten SMOKE-Run grün durchlaufen.

---

### 👥 Phase 11 — Beziehungs-Modell 🟡 BUILT 55% · VERIFIED ⬜

**BUILT:** PersonModel/PersonStore/TrustLevel/RelationshipMemory/EmpathySignal (kernel),
Approval-Gate-Integration (TrustLevel→Auto/NOTIFY/CONFIRM/FORBIDDEN),
SystemPromptBuilder-Integration (Person-Block im Prompt), Hot-Path in Telegram+HTTP.

**Offene Tasks (4/9):**
- [ ] **TrustLevel-Automation** — UNKNOWN→RECOGNIZED nach 5 Interaktionen, RECOGNIZED→TRUSTED nach 50+ positiven Interaktionen + 7 Tagen
- [ ] **EmpathySignal-Hot-Path** — Sentiment-Erkennung aus User-Text via Keyword-Heuristik + Satzlänge + Tageszeit-Kontext
- [ ] **PersonAwareSystemPrompt** — "You are talking to Georg (OWNER, prefers direct communication)" vs "You are talking to Unbekannt (UNKNOWN, be cautious)"
- [ ] **Multi-Person-Memory** — EpisodicMemory-Einträge mit personId verknüpfen

---

### 🌀 Phase 12 — Recursive Self-Improvement ⬜ BUILT 0% · VERIFIED ⬜

**Voraussetzung:** Phasen 10+11 müssen verifiziert sein (CausalReasoning + PersonModel).
Ohne kausales Denken und Personenverständnis wäre Self-Modification blindes Trial-Error + Goodhart-Risiko.

| Sub-Phase | BUILT | Inhalt |
|-----------|-------|--------|
| Ph 12a — Selbst-Bugfixing | 0% | BugTracker/SelfFix/Watchdog/AutoRevert/RuntimeEH |
| Ph 12b — Feature-Generierung | 0% | GapAnalyzer/RiskGate/FeatureGenAction/FeatureFlag |
| Ph 12c — Meta-Learning | 0% | MetricTimeSeries/PatternDetector/AutoABTest |
| Ph 12d — Selbst-Refactoring | 0% | TestGapAnalyzer/RefactorProposal/CoverageCheck |

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
| **1.** | Phase 10 CAUSAL-Eval-Tasks: HypothesisStore in DatasetBuilder injizieren + 3 Tasks | 10 | ~2h | ⬜ |
| **2.** | Phase 11 TrustLevel-Automation + EmpathySignal-Hot-Path | 11 | 2-3 Tage | ⬜ |
| **3.** | Continuity-Soak-Test (7 Tage) für memory_continuity | 8 | passiv | ⬜ |
| **4.** | Initiative-Policy v1 (InitiativeLevel + TrustLevel-Mapping) | 11.5 | 1-2 Tage | ⬜ |

---

## 🖥 GPU-Setup (seit 18.06.)

| GPU | Modell(e) | VRAM | Port |
|-----|-----------|------|------|
| GPU 1 — R9700 (32 GB) | qwen3_6-35b-agent | 23.7 GB (73%) | 11434 |
| GPU 0 — 7900 XTX (24 GB) | gemma4-26b + phi4-mini + nomic-embed | 24.4 GB (99%) | 11436 |
| CPU — Ryzen 7 5700G (62 GB RAM) | granite-mini-agent (Metis Mutation) | — | — |

**Zwei Ollama-Instanzen:** `ollama-gpu0.service` (7900 XTX) + `ollama-gpu1.service` (R9700).
OpenWebUI auf Port 11436. Metis + Standard-Clients auf Port 11434.
