# 🧠 AGI EDI - Roadmap

**Ziel:** EDI-ähnliche KI (Mass Effect 3) - eigenständig, per Sprache und Text ansprechbar,
mit eigenem Wissen, Persönlichkeit, narrativem Selbstmodell und der Fähigkeit, sich selbst zu verbessern.

**Stand: 11.08.2026** — Version v0.11.21-night-final-124, Phasen 1–9 ✅, Phase 10 Hot-Path ✅,
Phase 11 ✅, Phase 12d Deployed, Phase 13a+14 Deployed, 1 Evolution-Mutation accepted, 138K Beliefs.

> **Alles Details:** Siehe [FEATURES.md](FEATURES.md) (vollständiger Feature-Katalog).
> **Betrieb:** Siehe [README.md](README.md) (Deployment, API, Hardware).

---

## Fortschritt: ehrliche Selbstbewertung

Die ursprüngliche Roadmap behauptete "97% Richtung EDI".
**Diese 97% bezogen sich auf "stabiler autonomer Agent", nicht auf EDI-Niveau.**
Die letzten 3% wären in Wirklichkeit die schwierigsten — sie sind nicht durch mehr
Engineering lösbar, sondern brauchen kognitive Architektur jenseits eines guten LLM-Wrappers.

```
Phase 1-9   ████████████████████ 100%  Zuverlässiger autonomer Agent ✅
Phase 10    ████████████████████ 100%  Aktive kausale Hypothesen (Hot-Path)
Phase 11    ████████████████████ 100%  Beziehungs-Modell
Phase 12d   ██████░░░░░░░░░░░░░░  40%  Selbst-Refactoring Foundation
Phase 12a-c ░░░░░░░░░░░░░░░░░░░░   0%  Recursive Self-Improvement (Forschung)
─────────────────────────────────────
Phase 13a   ████████████████████ 100%  Voice Feature Extractor
Phase 14    ████████████████████ 100%  H2 Database + FTS5
─────────────────────────────────────
Realistisches EDI-Niveau: ~65-75%
```

---

## 🔬 Phase 10: Aktive kausale Hypothesen ✅ 100%

**Ziel:** Metis baut aktiv kausale Hypothesen über sich selbst und die Welt, prüft sie, revidiert.

### Deployed (v0.8.2)
- [x] **HypothesisStore** — JSONL-persistenter Store, 44+ Hypothesen geladen
- [x] **CausalDreamer** — Idle-Guard (WIP<2): 2-Min-Takt, generiert Hypothesen aus Experiences
- [x] **Hot-Path** — Top-3 offene Hypothesen im Planning-Prompt (`OllamaPlanner.java`)
- [x] **InterventionRunner** — do-Operator für gezielte Eingriffe zum Testen
- [x] **Counterfactual** — What-if-Analyse auf CausalModel-Basis

### Wirkung
- Planner sieht kausale Zusammenhänge: `IF "<cause>" -> "<effect>" (pred: UP, rationale: ...)`
- CausalDreamer arbeitet im Hintergrund, prüft Hypothesen über Interventions-Tests

---

## 👥 Phase 11: Beziehungs-Modell ✅ 100%

**Ziel:** Eine Person ≠ "user", sondern langfristiges Personenmodell mit Kontext, Vorlieben, Historie.

### Deployed (v0.7.1)
- [x] **Person** Record — userId, name, trustLevel, tags, interactionCount, sentimentHistory
- [x] **PersonStore** — Personen-DB mit Upsert, Lookup nach Session-ID
- [x] **TrustLevel** — OWNER/TRUSTED/KNOWN/GUEST/STRANGER → Approval-Level-Mapping
- [x] **EmpathySignal** — Sentiment-Analyse aus Text (Keyword-Heuristik)
- [x] **RelationshipMemory** — Interaktionsverlauf pro Person
- [x] **Hot-Path** — SystemPromptBuilder zeigt Partner-Block, Approval-Level dynamisch pro Person

### Trust Level → Approval Mapping
```
OWNER(4)    → CONFIRM   (alles außer FORBIDDEN automatisch)
TRUSTED(3)  → NOTIFY    (non-destructive Actions automatisch)
KNOWN(2)    → NOTIFY    (sensible Actions geloggt-automatisch)
GUEST(1)    → AUTO      (nur read-only automatisch)
STRANGER(0) → AUTO      (strenger Allow-List-Modus)
```

### Verifikation
- PersonScorer mit 5 HARD-gate Tasks (trust, empathy, memory) — PASS ✅

---

## 🌀 Phase 12: Recursive Self-Improvement 🟡 40%

**Ziel:** Metis kann Phasen selbst weiterentwickeln — Roadmap lesen, Code planen, Tests schreiben, Promotion durch Eval-Gate.

### Deployed (v0.12.0) — Foundation
- [x] **TestGapAnalyzer** — Analysiert Test-Coverage: Klassen ohne Tests, Orphan-Tests, Coverage-%
- [x] **RefactorProposal** — Code-Smell-Detektor: Long Methods, Too Many Methods, Deep Nesting, Magic Numbers
- [x] **CoverageCheck** — Parst Jacoco-XML-Reports: Instruction/Line/Branch/Method Coverage

### Ungelöst (Forschung, 6-10 Wochen)
- [ ] **RepoIndex** — AST-basierter Index aller Java-Klassen, Dependency-Graph
- [ ] **RoadmapReader** — Markdown-Parser für Roadmap, Coverage-Tracking pro Phase
- [ ] **MultiFileCodeGen** — Code-Synthese über mehrere Files (Interface+Impl+Test), Test-First
- [ ] **MutationProposal** — Diff + Spec + Risiko-Bewertung + Betroffene Module
- [ ] **DualReviewer** — 2 unabhängige Eval-Modelle + Property-Tests (jqwik)
- [ ] **PhaseCompletionEvaluator** — Watchdog-Komponente: "Phase X done" strukturiert prüfen
- [ ] **PersonalityAnchor-Mirror** — sha256-Pin im Watchdog-read-only
- [ ] **HumanCheckpoint** — expliziter Mensch-Approval für Kernel/Safety-Änderungen

### Warum erst NACH den bisherigen Phasen
- Ohne Narratives Selbst (8): Goodhart-Katastrophe
- Ohne Long-Horizon (9): Multi-Wochen-Projekte nicht abbildbar
- Ohne Kausale Hypothesen (10): blindes Trial-Error
- Ohne Beziehungs-Modell (11): Intention nicht verstanden

### Sicherheitsarchitektur (Voraussetzung, nicht verhandelbar)
```
┌─────────────────────────────────────────────────┐
│           WATCHDOG (separate JVM, immutable)     │
│  ┌───────────────────────────────────────────┐  │
│  │ PersonalityAnchor-Mirror (read-only)      │  │
│  │ Eval-Harness (Ground Truth, Held-out)     │  │
│  │ DualReviewer Gate                         │  │
│  │ HumanCheckpoint Gate                      │  │
│  └───────────────────────────────────────────┘  │
│              │ one-way (Metis hat KEINEN Handle)  │
└──────────────┼──────────────────────────────────┘
               │
┌──────────────┴──────────────────────────────────┐
│           METIS AGI (self-modifying)             │
│  ┌───────────────────────────────────────────┐  │
│  │ RepoIndex → RoadmapReader → MultiFile    │  │
│  │ CodeGen → MutationProposal               │  │
│  └───────────────────────────────────────────┘  │
│              │                                   │
│  Mutation-Proposal → Watchdog-Gate → main        │
└──────────────────────────────────────────────────┘
```

**Aufwand:** Foundation ~2 Tage ✅ | Forschung 6-10 Wochen, Risiko sehr hoch.
**Risiko:** voreilig aktivieren = Goodhart, Wertkern-Drift, Watchdog-Bypass.

---

## ⚠️ Bekannte echte Lücken (11.08.)

### Eval-Harness zeigt sie:
1. **PLANNING.goal_achieved=0.0** — Limitation der Single-Tick-Planung (Phase 9 hilft, aber Eval-Scorer noch nicht angepasst)
2. **CODEGEN.pass@1=0.0** — Sandbox-Build-Tests timen aus; mit aktiver Code-Sandbox sollte das anlaufen
3. **CONVERSATION.exact_match=0.0** — exact_match ist strenges Maß; SOFT, nicht kritisch

### Infrastrukturell offen:
- `CausalModel` existiert, aber Hot-Path nur via Prompt-Injection (keine automatische Hypothesen-Generierung aus Surprise)
- Audit-Anchors werden lokal geschrieben, aber nicht in ein **externes** Repo committet (finale Hash-Verankerung fehlt)
- JAR-Deployment ohne Signatur (sigstore/cosign offen)
- JARs ohne Maven-Coords (TornadoVM, voice-bits1-hsmm): erfordern Maven-Profil, auf CI nicht verfügbar

---

## 🎯 Meilensteine bis EDI (realistisch)

| Meilenstein | Phasen | Status |
|-------------|--------|--------|
| 🟢 **M1: Stabiler Kern** | Phase 1 | ✅ Erreicht |
| 🟢 **M2: Kommunikation** | Phase 2 | ✅ Erreicht |
| 🟢 **M3: Hardware-Nutzung** | Phase 2.5 | ✅ Erreicht |
| 🟢 **M4: Umgebungswahrnehmung** | Phase 3 | ✅ Erreicht |
| 🟢 **M5: Sprach-Interaktion** | Phase 4 | ✅ Erreicht |
| 🟢 **M6: Autonomie** | Phase 5 | ✅ Erreicht |
| 🟢 **M7: Produktionsreife** | Phase 6 | ✅ Erreicht |
| 🟢 **M8: Sicherheit + DiD** | Phase 7 + 7+ | ✅ Erreicht |
| 🟢 **M9: Narratives Selbst** | Phase 8 | ✅ Erreicht |
| 🟢 **M10: Long-Horizon** | Phase 9 | ✅ Erreicht |
| 🟢 **M11: Kausales Modell** | Phase 10 | ✅ Erreicht (Hot-Path) |
| 🟢 **M12: Beziehungs-Modell** | Phase 11 | ✅ Erreicht |
| 🟡 **M13: Selbst-Refactoring** | Phase 12d | 🟡 40% (Foundation) |
| 🔴 **M14: EDI-Niveau** | Phase 12a-c | ⬜ Ungelöst (~6-10 Wochen) |

---

*"Streben nach Perfektion"* — Metis ist heute ein autonomer LLM-Agent mit narrativem Selbstmodell, Long-Horizon-Planung, kausaler Hypothesenbildung und Beziehungs-Modell. Die Foundation für Self-Refactoring steht (Phase 12d), echte Recursive Self-Improvement (Phase 12a-c) bleibt Forschungsarbeit.

Der Weg zu EDI-Niveau führt über:
- Phase 12a-c: RepoIndex, MultiFileCodeGen, DualReviewer, HumanCheckpoint
- Automatische Hypothesen-Generierung aus Surprise (CuriosityEngine → HypothesisGenerator Pipeline)
- Audit-Anchor-Verankerung in externem Repo

Vergleiche mit „den besten", „weltweit" oder „den ich kenne" bewusst weggelassen: nicht messbar, nicht belegbar, nicht im Sinne von Kanban-Ehrlichkeit.
