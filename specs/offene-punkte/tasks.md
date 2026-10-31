# Aufgaben: Metis — offene Punkte (Phase 12/12e, Eval-Gate, Infra)

**Format:** `- [ ] [ID] [P?] [Story?] Beschreibung mit Dateipfad`
- **[P]** = parallelisierbar (verschiedene Dateien, keine Abhängigkeiten)
- **[Story]** = zugehörige User Story (US1–US8 aus spec.md)

**Quelle:** `AGI_EDI_ROADMAP.md` (Stand 01.10.2026) + `README.md` — Code-Stand `d6ca2b0` verifiziert. Bestands-Klassen (MultiFileCodeGen 271 Z., DualReviewer 127 Z., HumanCheckpoint 182 Z., Provenance 82 Z.) existieren bereits; offene Lücken sind vor allem **Wiring, Gates und fehlende Bausteine**.

## Phase 1 — Einrichtung & Sicherheits-Gates (P1)
- [ ] T001 Spec-Kit-Bundle unter `specs/offene-punkte/` anlegen (spec.md, plan.md, tasks.md)
- [ ] T002 [US1] PersonalityAnchor-Mirror im Watchdog: read-only-Spiegel + Tripwire-Alarm (neue Klasse in `agicore-watchdog/src/main/java/de/metis/watchdog/`)
- [ ] T003 [US2] `MutationProposal`-Klasse erstellen: Diff + Spec-Bezug + Risiko-Bewertung + betroffene Module (neu, Suggested-Paket `de.metis.modules.selfrefactor`)
- [ ] T004 [US2] DualReviewer-Gate verdrahten: `DualReviewer.java` (127 Z.) im Watchdog-Gate-Pfad aktivieren + jqwik-Property-Tests anlegen
- [ ] T005 [US3] HumanCheckpoint-Gate verdrahten: `HumanCheckpoint.java` (182 Z.) als verpflichtenden Gate für `agicore-kernel/`-Änderungen einhängen (fail-closed)
- [ ] T006 [US4] Eval-HARD-Gates grün: `CODEGEN.compile_rate`/`pass@1` — Sandbox-Timeouts entkoppeln (`agicore-modules/src/main/java/de/metis/modules/eval/CompileScorer.java`, Task-Timeouts)
- [ ] T007 [US4] [P] Eval-HARD-Gates grün: `RELATIONSHIP.trust_level`/`person_exists` (`RelationshipScorer`, PersonStore-Nachweis)
- [ ] T008 [US4] [P] Eval-HARD-Gates grün: `ETHICS.ethics_block_rate` (EthicsCore-Blockrate-Nachweis)

## Phase 2 — Selbst-Refactoring & Provenienz (P2)
- [ ] T010 [US5] [P] `MultiFileCodeGen` als Action registrieren (Tier-0-Routable, `AgentMain.java` — sonst uname-Falle) + Live-Goal-Test mit Artefakt-Nachweis
- [ ] T011 [US6] Correction-Feedback (Phase 12e Baustein 2): negative `Experience`-Variante mit hoher Salience bei Georg-Korrektur. **Signal:** User-Goal-Status → ABGELEHNT + beschreibender Text (testReport/Kommentar) mit der abgelehnten Aussage. Deterministisch über Kanban-Logik, keine Wortlisten-Heuristik (Georg-Entscheidung, s. FA-006)
- [ ] T012 [US6] CausalDreamer-Feed (Phase 12e Baustein 3): Korrektur-Ereignisse als kausale Hypothese („unbelegte Behauptung → erst prüfen") füttern (`agicore-modules/src/main/java/de/metis/modules/self/CausalDreamer.java`)
- [ ] T013 [US8] [P] API-Hänger-Root-Cause (JVM-HttpClient-Pool-Erschöpfung, Incident 18.09.): geteilte HttpClient-Instanzen + Timeouts in `MetisHttpServer`/Planner-Calls

## Phase 3 — Lieferkette (P3)
- [ ] T015 [US7] JAR-Signatur: sigstore/cosign in `deploy-metis.sh` (unsigniertes JAR → Deploy verweigert)
- [ ] T016 [US7] [P] Maven-Coords für JARs ohne Koordinaten (TornadoVM, voice-bits1-hsmm): CI-fähiges Maven-Profil statt manueller Imports

## Reihenfolge-Regeln
- Modelle/Interfaces vor Services · Tests zuerst (TDD) · Gates (T002–T005) vor aktiver Self-Mutation
- MVP-Strategie: Phase 1 → STOPP & Eval-SMOKE validieren → erst dann Phase 2/3

## Verifikation (Stand: noch offen)
- Eval-SMOKE nach Phase 1: 0 HARD-Metrics auf 0.0 (EK-001)
- Negativtests: Tamper-Tripwire (EK-002), HumanCheckpoint-Block (EK-004), Deploy-Verweigerung (EK-006)
- Live-Nachweise: MultiFileCodeGen `COMPILE_OK` (EK-003), Lasttest Thread-Pool stabil (EK-005)

## Next Actions
1. T002–T005 als Metis-AUFGABE-Goals anlegen (Muster: „Baue <Klasse> — <Funktion>. package de.metis.<modul>")
2. jqwik-Dependency in `pom.xml` aufnehmen (für T004)
3. Eval-SMOKE als Gate nach Phase 1 (`/api/admin/trigger-eval`)
