# Implementierungsplan: Metis — offene Punkte (Phase 12/12e, Eval-Gate, Infra)

**Branch**: `docs/spec-kit-offene-punkte` · **Datum**: 31.10.2026

## Technischer Kontext
- **Sprache/Version**: Java 25 (Zulu JDK), Maven-Multi-Module
- **Primäre Abhängigkeiten**: Maven, JUnit 5, Jacoco, jqwik (DualReviewer-Property-Tests), JavaParser (Repos, AST)
- **Speicherung**: H2-Database + SQLite (Beliefs), Audit-Hashkette (Watchdog)
- **Testen**: JUnit 5, Mockito, Testcontainers, Eval-Harness (CompileScorer, RelationshipScorer, EthicsScorer)
- **Zielplattform**: Linux-Server (miniedi, 192.168.22.204), 2× AMD GPU
- **Projekttyp**: Server-Anwendung (agent-framework), Multi-Module
- **Leistungsziele**: Planner-Latenz < 5 s, Eval-SMOKE vollständig in < 15 min
- **Einschränkungen**: Watchdog läuft als separate JVM (one-way, kein Metis-Handle); Phase 10+11 erreicht; Roadmap = Quelle der Wahrheit

## Projektstruktur (Java)
```
agicore-kernel/   # Kernel: Belief, Provenance, PersonalityAnchor, CausalDreamer, HypothesisGenerator
agicore-modules/  # Module: selfrefactor, multifile, review, checkpoint, eval, self
agicore-watchdog/ # Watchdog: WatchdogMain, AuditLog, TripwireSeverity, WatchdogAction
specs/offene-punkte/   # ← dieses Spec-Kit-Bundle (spec.md, plan.md, tasks.md)
```

## Verfassungs-Check (Constitution Check)
*GATE: vor der Recherche bestehen, nach dem Design erneut prüfen.*
- [x] Kein Metis-Zugriff auf Watchdog-Handle (one-way) — bestehende Architektur
- [x] Test-First für alle Code-Änderungen (Red-Green-Refactor)
- [x] Sicherheitsarchitektur nicht verhandelbar (Watchdog read-only)
- [x] Roadmap als Quelle für offene Punkte (Issues sind leer, Stand 31.10.)

## Komplexitäts-Nachverfolgung
| Verstoß | Warum nötig | Einfachere Alternative abgelehnt weil |
|---|---|---|
| jqwik als neue Dependency | DualReviewer braucht Property-Tests (Phase 12a) | rein manuelle Tests zu wenig deterministisch |
| H2 zusätzlich zu SQLite | Goal-Persistenz + Indexierung (Phase 14) | nur SQLite: kein FTS/JSON |
| JAR-Signatur (cosign) | Lieferkette (Sigstore) | nur MD5-Checksumme: keine kryptografische Herkunft |

## Phasen-Überblick
- **Phase 1 (P1):** Sicherheits-Gates — PersonalityAnchor-Mirror, MutationProposal, DualReviewer-Wiring, HumanCheckpoint-Wiring, Eval-Gate grün
- **Phase 2 (P2):** Selbst-Refactoring & Provenienz — MultiFileCodeGen-Action, correction-Feedback, CausalDreamer-Feed, API-Hänger-Fix
- **Phase 3 (P3):** Lieferkette — JAR-Signatur, Maven-Coords für TornadoVM/voice-bits1
