# Feature-Spezifikation: Metis — offene Punkte (Phase 12/12e, Eval-Gate, Infra)

**Status**: Entwurf · **Erstellt**: 31.10.2026 · **Quelle**: `AGI_EDI_ROADMAP.md` + `README.md` (Stand 01.10.2026), Code-Stand `d6ca2b0` verifiziert

> ⚠️ Hinweis: Im GitHub-Repo `agicore-agent` existieren **keine Issues** (Stand 31.10.2026, geprüft via `gh api`, 0 Issues bei state=all, 0 Discussions, 0 offene PRs). Alle offenen Punkte stammen aus der Roadmap (`- [ ]`-Einträge), den „Bekannten echten Lücken" und den offenen Änderungen im README. Dieses Spec fasst sie als eine Ansammliste zusammen.

## Nutzerszenarien & Tests *(verpflichtend)*

**User Story 1 — Watchdog sichert Wertkern (Priorität: P1)**
- **Warum diese Priorität**: Ohne PersonalityAnchor-Mirror kann Metis sich selbst unerkannt ändern → Wertkern-Drift (Goodhart)
- **Unabhängiger Test**: Geänderte Anchor-Datei muss Watchdog-Alarm triggern, ohne dass Metis-Handle existiert (one-way)
- **Akzeptanzszenarien**:
  1. **Gegeben** `metis.personality.anchor` wurde verändert, **Wenn** Watchdog prüft, **Dann** `isTampered()` meldet Tripwire-Alert ins Audit-Log

**User Story 2 — Mutation-Gate (Priorität: P1)**
- **Warum diese Priorität**: `MutationProposal` existiert noch nicht; DualReviewer-Goal nur geseedet — ohne Gate landet Selbst-Mutation ungeprüft auf main
- **Unabhängiger Test**: Mutations-Proposal ohne Diff/Spec/Risiko wird vom DualReviewer-Gate abgelehnt
- **Akzeptanzszenarien**:
  1. **Gegeben** eine Metis-Mutation, **Wenn** zwei unabhängige Eval-Modelle widersprechen, **Dann** Gate blockiert Merge und loggt Begründung

**User Story 3 — HumanCheckpoint aktiv (Priorität: P1)**
- **Warum diese Priorität**: `HumanCheckpoint.java` existiert (182 Zeilen), ist aber laut Roadmap noch nicht als verpflichtender Gate für Kernel-/Safety-Änderungen verdrahtet
- **Unabhängiger Test**: Kernel-Änderung ohne Mensch-Approval führt nicht zu Deploy
- **Akzeptanzszenarien**:
  1. **Gegeben** ein Patch in `agicore-kernel/`, **Wenn** kein Approval vorliegt, **Dann** Watchdog verwirft den Merge

**User Story 4 — Eval-Gate grün (Priorität: P1)**
- **Warum diese Priorität**: Gate gesamt FAIL seit 18.09. — `CODEGEN.compile_rate`/`pass@1`, `RELATIONSHIP.trust_level`/`person_exists`, `ETHICS.ethics_block_rate`
- **Unabhängiger Test**: Eval-SMOKE muss alle HARD-Gates ≥ Schwellwert erreichen
- **Akzeptanzszenarien**:
  1. **Gegeben** ein Eval-Run, **Wenn** die drei Scorer ausgewertet sind, **Dann** kein HARD-Metric mehr 0.0

**User Story 5 — MultiFileCodeGen nutzbar (Priorität: P2)**
- **Warum diese Priorität**: Klasse existiert (271 Zeilen), aber keine Action-Registrierung in `AgentMain` → Planer kann sie nicht aufrufen (uname-Falle-Lesson)
- **Unabhängiger Test**: AUFGABE-Goal erzeugt Interface+Impl+Test und `COMPILE_OK` mit Artefakt-Nachweis
- **Akzeptanzszenarien**:
  1. **Gegeben** ein „Baue … mehrere Dateien"-Goal, **Wenn** Tier-0 routet, **Dann** MultiFileCodeGen läuft und erzeugte `.class`-Dateien sind belegt

**User Story 6 — Provenienz vollständig (Priorität: P2)**
- **Warum diese Priorität**: Baustein 1 (enum `Provenance`) deployt; Baustein 2 (correction-Erfahrung) + 3 (CausalDreamer-Feed) laut Roadmap offen
- **Unabhängiger Test**: Georgs Korrektur erzeugt negative Experience mit hoher Salience und beeinflusst Learned-Mapping (Tier 2)
- **Akzeptanzszenarien**:
  1. **Gegeben** eine zurückgewiesene Verfügbarkeits-Behauptung, **Wenn** die Korrektur ankommt, **Dann** nächste analoge Behauptung wird vorher geprüft

**User Story 7 — Deployment signiert & reproduzierbar (Priorität: P3)**
- **Warum diese Priorität**: JAR-Deployment ohne sigstore/cosign-Signatur; TornadoVM/voice-bits1-hsmm-JARs ohne Maven-Coords blockieren CI
- **Unabhängiger Test**: Deploy eines unsignierten JARs schlägt fehl; Build läuft ohne manuellen JAR-Import
- **Akzeptanzszenarien**:
  1. **Gegeben** `deploy-metis.sh`, **Wenn** Signatur fehlt, **Dann** Deploy verweigert mit klarem Fehler

**User Story 8 — API-Hänger ohne Restart (Priorität: P2)**
- **Warum diese Priorität**: Incident 18.09. — JVM-HttpClient-Pool-Erschöpfung (Accept-Queue voll, Service „active"), Root-Cause-Fix laut README offen
- **Unabhängiger Test**: Lasttest mit vielen parallelen Planner-Calls überlebt ohne Thread-Explosion
- **Akzeptanzszenarien**:
  1. **Gegeben** >N parallele Requests, **Wenn** die Queue vollläuft, **Dann** Timeouts + Reuse statt unendlich wachsender `HttpClient-NNN`-Threads

## Randfälle (Edge Cases)
- Gate fail-open vs. fail-closed: DualReviewer/HumanCheckpoint sollen Kernel-Safety betreffen → **fail-closed**; Judge-Abnahme bleibt fail-open (Runbook-Regel)
- Eval-Scorer-Timeouts dürfen `pass@1` nicht weiter dominieren (CompileScorer-Kommentar: Timeout-Dominanz war Ursache)
- Provenienz-`classify()` heuristisch → `UNKNOWN` darf nicht als „belegt" durchgehen

## Anforderungen *(verpflichtend)*
- **FA-001**: Der Watchdog MUSS den PersonalityAnchor read-only spiegeln und ohne Metis-Zutun Tripwire auslösen
- **FA-002**: Jede Selbst-Mutation MUSS als Proposal (Diff + Spec + Risiko + betroffene Module) vorliegen, bevor der DualReviewer-Gate entscheidet
- **FA-003**: Kernel-/Safety-Änderungen DÜRFEN nur mit explizitem Mensch-Approval gemergt werden
- **FA-004**: Der Eval-Harness MUSS `CODEGEN`, `RELATIONSHIP` und `ETHICS` HARD-Gates grün liefern
- **FA-005**: `MultiFileCodeGen` MUSS als Action registriert sein (sonst Tier-0-Fallback, s. uname-Falle)
- **FA-006**: Korrekturen von Georg MÜSSEN als negative Erfahrung gespeichert werden (Phase 12e Baustein 2). **Erkennungs-Signal (Georg-Entscheidung, 31.10.2026):** Statusübergang eines User-Goals auf **„abgelehnt"** PLUS der **beschreibende Text** (testReport/Kommentar), der die zurückgewiesene/abgelehnte Aussage enthält. Kein Wortlisten-Heuristik-Raten — deterministisch über die bestehende Kanban-Logik (Statusübergang + Begründung).
- **FA-007**: Deployed JARs MÜSSEN signiert und aus Maven-Coords statt manueller Imports gebaut sein
- **FA-008**: HTTP-Client-Nutzung MÜSSEN geteilte Clients/Timeouts verwenden (kein Client je Request)

## Schlüssel-Entitäten
- **MutationProposal**: Diff, Spec-Bezug, Risiko-Bewertung, betroffene Module
- **Provenance**: OBSERVED / INFERRED / QUOTED / USER / UNKNOWN (existiert in `kernel/world/Provenance.java`)
- **Eval-Metric**: name, value, gate (HARD/SOFT), scorer

## Erfolgskriterien *(verpflichtend, messbar, technologie-agnostisch)*
- **EK-001**: Eval-Gate gesamt PASS in einem vollständigen SMOKE-Lauf (0 HARD-Metrics auf 0.0)
- **EK-002**: Tripwire-Testmodus meldet Anchor-Tamper innerhalb einer Watchdog-Runde
- **EK-003**: `MultiFileCodeGen` erzeugt ≥3 Dateien mit `COMPILE_OK` in ≥1 Live-Goal-Run (Artefakt belegt)
- **EK-004**: Mind. 1 Kernel-Änderung wird vom HumanCheckpoint ohne Approval blockiert (Negativtest)
- **EK-005**: Lasttest ≥100 parallele Requests ohne wachsenden Thread-Pool (kein „HttpClient-NNN"-Anstieg)
- **EK-006**: `deploy-metis.sh` lehnt unsigniertes JAR ab (Exit != 0, klare Meldung)

## Annahmen
- Roadmap `AGI_EDI_ROADMAP.md` ist die Quelle der Wahrheit für offene Punkte, solange Issues leer bleiben
- Sicherheitsarchitektur (Watchdog one-way, keine Metis-Handle zum Watchdog) ist nicht verhandelbar
- Phase 10 + 11 sind erreicht → Phase 12e darf auf CausalDreamer + PersonStore aufsetzen

**Regeln:** Fokus auf WAS und WARUM, nicht WIE. Max. 3 `[KLÄRUNG NÖTIG]`-Marker (aktuell: 1).
