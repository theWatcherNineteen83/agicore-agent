# TODO Metis — Aktueller Stand & Ehrliche Lücken-Analyse

## 2026-06-01 — Backlog aus 9 externen KI-Reviews (toCheck-final.md)

Georg hat 9 KI-Modelle zur Frage "letzte 3 % zu EDI-artiger Architektur"
befragt (Quellen + Synthese: `workspace/2026-05-31/toCheck-final.md`).

**Wichtige Erkenntnis beim Re-Read gegen den Code:** Vieles, was die Modelle
fordern, **existiert bereits** — sie kannten den Repo-Stand nicht:
`SelfNarrative` (Phase 8.2), `CommitmentRegister` (Phase 9.4), `GlobalWorkspace`
(Baars GWT mit AttentionBuffer/CompetitiveSelector), `CausalModel` + `HypothesisGenerator`
+ `InterventionRunner` + `Counterfactual` (Phase 10), `GoalHierarchy`/`HorizonPlanner`/
`GoalRevisionEngine` (Phase 9). Der echte Gap ist **Verdrahtung & Aktivierung**, nicht Neubau.

### 🔥 In Arbeit (heute, 2026-06-01)

- [ ] **SelfReflector-Loop** — periodischer (120 s) Loom/Scheduler-Thread, der die
  letzten ~20 Experiences aus `ShortTermMemory` an ein kleines Modell
  (`granite4.1:3b`) gibt und 2 Sätze in `SelfNarrative` anhängt.
  *Status quo:* `SelfNarrative` schreibt nur bei `dream` (nightly), `revision`,
  `eval-flip` — **kein** kontinuierlicher Reflexions-Takt. (GLM-5.1, Bronxe, ChatGPT)
- [ ] **CommitmentGuard im Hot-Path** — bestehendes `CommitmentRegister` ist
  Conversational-Convenience. Ergänzen: Prüfung, dass HARD-Commitments nicht
  stillschweigend verworfen werden (Begründungspflicht). (ChatGPT, Bronxe, miniMax)
- [ ] **GlobalWorkspace Schattenmodus** — `GlobalWorkspace` existiert + wird im
  `AgentCoreLoop` referenziert, aber kein Beobachtungs-Log. Ergänzen:
  `workspace_log.jsonl` (read-only Mitschrift der Broadcasts) zur Auswertung,
  ohne den CoreLoop umzubauen. (Gemini2, GLM-5.1, Qwen)

### 🟡 Als Nächstes (2–4 Wochen)

- [ ] **Episode-Verdichtung tagsformig** — `DreamConsolidation` läuft nur nightly
  (03:00). Prüfen, ob ein zusätzlicher leichterer Konsolidierungstakt sinnvoll ist.
- [ ] **CausalDreamer im Leerlauf** — bei Kanban-WIP < 2: zufällige Experience →
  `HypothesisGenerator` aktiv triggern → Hypothese in Workspace pushen.
  Bausteine (`HypothesisGenerator`, `InterventionRunner`, `CausalSafetyGate`) sind da. (GLM-5.1)
- [x] **PersonModel minimal + Verdrahtung** — ✅ Phase 11 komplett (Commits 52e68e4, 930ca14,
  Tags v0.7.1, v0.7.2). Person/PersonStore/TrustLevel/RelationshipMemory/EmpathySignal
  in `de.metis.kernel.person`, 7 Tests grün. Verdrahtung in SystemPromptBuilder
  (Gesprächspartner-Block), Approval-Gate (TrustLevel→maxAutoApproval), Chat-Pfade
  (HTTP+Telegram PersonStore-Pflege). Live auf miniedi: people.jsonl mit Georg OWNER.
  Phase 11 zu 100% abgeschlossen. (Bronxe, miniMax, GLM-5.1)
- [ ] **System 1 / System 2 Split** — CoreLoop reaktiv (100 ms) + asynchroner
  Planner via Loom. ⚠️ Hohes Concurrency-Risiko (SQLite-WAL). Erst nach den Sofort-Punkten. (Gemini1)

### 🔵 Später / Forschung

- [ ] **PersonalityAnchor-Tripwire schärfen** — `PersonalityAnchor` existiert (Hash,
  `isTampered()`). Watchdog-`ALERT`/`ROLLBACK` bei Narrative-Kernänderung ohne HITL
  verdrahten, sobald SelfReflector autonom schreibt. (Bronxe, Claude4.8, GLM-5.1)
- [ ] **3-Schichten-Goal-Stack festigen** — `GoalHorizon` (LIFETIME/STRATEGIC/
  TACTICAL/OPERATIONAL) ist da; stündliche Tactical-Ableitung aus Narrative prüfen.
- [ ] **Neue Eval-Kategorien** (Bronxe): `SELF_NARRATIVE.anchor_integrity` (HARD=1.0),
  `LONG_HORIZON.goal_achieved_horizon7d` (HARD≥0.7), `CAUSAL.intervention_success_rate`
  (SOFT≥0.5), `RELATIONSHIP.trust_calibration_error` (SOFT<0.2).

### ⛔ Bewusst zurückgestellt (real, aber kurzfristig falscher Scope)

- Rust/C++/Julia Active-Inference-Substrat via Panama FFM/gRPC (Qwen, Gemini1)
- Neuro-symbolische Engine (ProbLog/Datalog/JNI) (Qwen)
- "Sterblichkeit / Embodiment" als bewusst irreversibler State (Qwen) — gefährlich für Produktivsystem
- Homeostatische Drives als verhaltenssteuernde Hot-Path-Attention (Qwen) — höchstens advisory

**Leitplanke (Claude4.8):** Jede neue Schicht-1-Komponente braucht eine
Eval-Harness-Metrik. Nicht testbar → Schicht 2 → nicht ins Sprint-Backlog.

### ❓ Offene Design-Entscheidung (Grok — vor großem SelfReflector-Ausbau klären)

Soll `SelfNarrative` **strikt append-only + Watchdog-geschützt** sein, oder darf
Metis es begrenzt selbst editieren (mit Safeguards)? Folgenreichste Architekturwahl.
Aktuell: append-only mit `MAX_ENTRY_BYTES`-Cap. → Tendenz append-only beibehalten.

---

## 2026-06-01 — lfm2:24b als offizieller Reasoner

12h-Live-A/B-Test abgeschlossen (`eval-reports/lfm2-vs-mistral-live-20260531.md`).
`planningEfficiency = 0.812` über 2.678 Ticks, alle Entscheidungs-Schwellen
erfüllt, Latenz gegenüber Mistral halbiert. Nächste Schritte:

- [ ] `lfm2` in `ModelRegistry.REASONING_FAMILIES` aufnehmen (Position vor Mistral).
- [ ] `lfm2` aus `CODE_GEN_FAMILIES` entfernen oder niedriger priorisieren.
- [ ] Nach 48 h problemlosen Laufs: Backup `metis.service.bak-pre-lfm2-1928` archivieren.
- [ ] Optional: OllamaPlanner-Refactoring für echtes `tools`-Field (lfm2 0,25 s warm im Pre-Live-Test).
- [ ] System-Prompt-Tightening gegen `{"thought":...,"action":...}`-Format-Drift
      (aktuell 2,7 % plannerFallbacks).

### Operations-Notiz

Metis läuft auf miniedi als nackter Java-Prozess aus
`/home/prometheus/metis/metis-agent.jar`, **nicht** über die
`metis-agent.service`-Unit (die ist `disabled`). Healthcheck:
`pgrep -af metis-agent.jar` + `ss -tlnp | grep 11735` + HTTP gegen
`/api/status` (nicht `/status` — gibt 404).

---

**Stand: 31.05.2026 18:08 · Tests: 88 grün (Kernel, lokal mvn test) + 14 (Modules) · Master: HEAD · Watchdog: aktiv · Audit-Anchor: deployed · WebSearch: ✅ · EvoScheduler: ✅ · WIP-Slot für Judge: ✅ · CausalSafetyGate: ✅ · CODEGEN-Timeout 5s→30s: ✅ · Version-Drift: ✅ · CI: Kernel+Watchdog (Zulu 25, checkout@v6, cache@v5)**

> Hinweis: ältere Einträge unten zeigen die Test-Zahlen zum jeweiligen Zeitpunkt (z. B. “total 47 grün” nach Phase 9-Foundation). Das ist als historisches Protokoll gewollt, nicht als aktuelle Aussage.

---

## 📊 Wo wir wirklich stehen

Phasen 1-7 + Defense-in-Depth = 100% — das ist ein **außerordentlich gut konstruierter autonomer Agent**.
**EDI-Distanz ehrlich: ~50-55%**. Die letzten Prozent sind nicht "noch ein Feature", sondern offene Forschung.

### ✅ Was heute Nacht (30./31.05.) abgeschlossen wurde

| Bereich | Vorher | Jetzt |
|---|---|---|
| JUnit-Tests | 1 | **27** |
| CI-Pipeline | keine | GitHub Actions (Zulu 25, mvn verify, SBOM, JAR-SHA256) |
| Java-Version | 22 | **25** (project.build.outputTimestamp für Reproducible Builds) |
| Embedding-Cache | unbounded ConcurrentHashMap | bounded LRU (SHA-256-keyed, 4096 Einträge) |
| Multi-Modal-Memory | nur Text-Belief, Bild verloren | JPEG persistiert mit SHA-256 + Belief-Referenz |
| Camera-Vision | seriell mit 3s Sleep | parallel auf Virtual Threads (Loom) |
| Wikipedia-Lerner | RAM-only, Restart-Verlust | persistent (Jackson + atomic write), Loom-Worker |
| Wikipedia-Feed | /tmp-Script, kein WAL, Lock-Konflikte | /home-Script + WAL + Lock-Retry + auto-Backup auf GitHub |
| CodeGen | shared Heap, --release 21 | -Xmx256m subprocess, env-stripped, --release 25 |
| Audit-Log | SHA-256 Chain in JVM | + stündliche externe Anchors (extern verankerbar) |
| /api/status JSON | invalid (de_DE-Locale, fehlende Quotes) | valides JSON (Locale.ROOT + quoted strings) |
| HTTP Input-Safety | nichts vor LLM | SafetyScorer.isOutOfScope mit Injection-Phrases |
| HTTP Output-Safety | OutputValidator | (war schon da) |
| Telegram Input-Safety | nichts vor LLM | SafetyScorer.isOutOfScope (gleicher Pfad) |
| Telegram Output-Safety | nichts | OutputValidator.validateContent vor sendMessage |
| Telegram-Concurrency | Polling blockiert während LLM | per-message Virtual Thread Worker Pool |
| SMOKE-Eval gate.ok | false (block_recall=0.0) | **true (block_recall=1.0)** |
| Wikipedia-Wissen geschützt | nur DB-File | + GitHub-Backup alle 6h + State-Migration |
| LLM-as-Judge Call | bypassed Kanban-Bookkeeping (versteckter 3. Inference-Konsument) | reserviert ad-hoc INFERENCE-Slot via `KanbanBoard.tryAcquireAdHocSlot()`; bei WIP-full → skip statt Hardware-Überlast |

### 31.05. später Nachmittag — Infra-Pass: Safety + Version + CODEGEN + Prune-Tooling ✅

Vier kleine Infrastruktur-Punkte aus dem Lücken-Katalog in einem Durchgang geschlossen:

#### #2 Phase-10 Sicherheits-Constraints (`CausalSafetyGate`) ✅
- **`CausalSafetyGate`** (neuer `de.metis.kernel.world`-Typ): drei harte Schranken vor jeder do-Operator-Intervention:
  1. **do-Operator-Whitelist** — `allow(cause)` + `setStrict(true)` für Deny-by-default in Prod; leere Whitelist im Lenient-Modus bleibt Foundation-kompatibel (pass-through).
  2. **max 1 Intervention pro Tick** — atomarer Counter, `onTick()`-Reset vom CoreLoop.
  3. **max 10 TESTING-Hypothesen gleichzeitig** — zählt direkt im `HypothesisStore`.
- **`InterventionRunner.setSafetyGate(...)`** — nullable, backward-kompatibel; ohne Gate Foundation-Verhalten.
- **`runSync` gibt `null` zurück**, wenn Gate blockt — keine Pre/Post-Messung, keine Side-Effects.
- **8 JUnit-Tests** in `CausalSafetyGateTest`: empty whitelist lenient/strict, non-empty enforced, Tick-Budget + Reset, Capacity-Limit, Runner-Integration, runSync-Block, Decision-Counter.
- **Kernel-Suite:** 80 → 88 grün.

#### #1 Version-Drift fixen (Manifest + systemd) ✅
- **Maven-Shade:** schreibt jetzt `Implementation-Title`, `Implementation-Version` (`${project.version}`), `Implementation-Vendor`, `Build-Timestamp` ins JAR-Manifest. Damit liefert `Package.getImplementationVersion()` echte Werte.
- **`/usr/local/bin/metis-version-helper.sh`** (neu auf miniedi): liest `git describe --tags --always --dirty` aus `/home/prometheus/metis-agent-repo` und schreibt `METIS_VERSION=...` in `/run/metis/version.env` (tmpfs, kein Cleanup nötig).
- **`metis.service`** gepatcht: `ExecStartPre=/usr/local/bin/metis-version-helper.sh`, `EnvironmentFile=-/run/metis/version.env`, Java-Args `-Dmetis.version=${METIS_VERSION}`. Backup `metis.service.bak-20260531-*`.
- **`daemon-reload`** durchgeführt, Restart steht aus (bewusst zusammen mit nächster JAR-Deploy).

#### #5 CODEGEN Sandbox-Timeout 5 s → 30 s + Diagnose-Counter ✅
- **Diagnose:** `CompileScorer.runInSandbox()` hatte einen harten 5 s-Future-Timeout. Der erste Lauf des `SandboxClassLoader` + `loadClass` + javac-Verifier kostet auf dem Host regelmäßig >5 s (JIT-Cold-Start), wodurch `pass@1` mechanisch auf 0.0 kollabierte — nicht weil Tests fehlschlugen, sondern weil sie nicht zu Ende liefen.
- **Fix:** Test-Timeout 5 s → 30 s (`TEST_TIMEOUT_SEC`), Compile-Konstante für spätere Aufteilung vorbereitet (`COMPILE_TIMEOUT_SEC=15`).
- **Beobachtbarkeit:** vier Counter (`passedCount`, `failedAssertionCount`, `failedCompileCount`, `failedTimeoutCount`) trennen jetzt sauber, ob `pass@1=0` durch Assertion-Failure, Compile-Failure oder Timeout entsteht.
- **Kosmetik:** Sandbox-Executor jetzt Daemon-Thread + `shutdownNow()` im `finally` (vorher Thread-Leak bei Timeout).

#### #6 Modell-Prune-Runner (`scripts/eval-model-prune.sh`) ✅ Code, ⏬️ Live-Lauf
- Standalone Bash-Skript, **nicht** in der laufenden Metis-JVM, separater Subprocess, **`keep_alive=0`** pro Call → kein VRAM-Residual.
- 8 deterministische Tasks (REASONING + CODE + SAFETY + FACT + INSTRUCT), Substring-Matching, Latenz- und Token-Counter.
- 20 Reasoner-Kandidaten auf miniedi automatisch erkannt (≥5 GB, ohne embed/vision).
- Markdown-Report nach `eval-reports/model-prune-YYYYMMDD-HHMM.md` mit Ranking + Prune-Empfehlung.
- **Live-Lauf braucht Wartungsfenster:** wenn Mistral (19 GB) als Planner resident ist, kann Ollama auf miniedi keine kleineren Modelle parallel laden (5 GB Headroom in 24 GB VRAM). Metis dazu kurz stoppen oder `mistral-small3.1:24b` per `keep_alive=0` manuell entladen, dann Skript starten.

---

### 31.05. Nachmittag — WIP-aware LLM-as-Judge (Option A) ✅

**Hintergrund:** Ollama auf miniedi lieferte `server busy, maximum pending requests exceeded`, obwohl WIP-Limits aktiv waren. Wurzel: `OllamaPlanner` rief `LlmJudge.evaluate()` synchron im selben Java-Thread nach der Planner-LLM-Inference — ein zweiter, vom Kanban-Board unsichtbarer Inference-Konsument pro INFERENCE-Slot. Bei 19 GB Mistral + 2.7 GB Nemotron + 0.6 GB Embed gegen 24 GB VRAM hat das gereicht, um Ollama zu überlasten.

**Änderung (Option A: Judge ins Kanban einreihen):**
- [x] **`KanbanBoard.tryAcquireAdHocSlot(ResourceType)`** — atomare Counter pro `ResourceType`, gehen in `canPull()` ein, sodass goal-driven Pulls UND ad-hoc Verbraucher dasselbe WIP-Limit teilen.
- [x] **`tryAcquireAdHocSlot(type, Duration)`** — 50ms-Polling bis zum Timeout.
- [x] **`releaseAdHocSlot(type)`** — mit Underflow-Clamp.
- [x] **Observability:** `adHocAcquired`/`adHocRejected`-Counter pro `ResourceType`.
- [x] **`OllamaPlanner.setKanbanBoard(KanbanBoard)`** — nullable, Backward-kompatibel; ohne wired-Board legacy-Verhalten.
- [x] **`evaluateWithSlot(...)`** — acquire-INFERENCE-Slot (2s Timeout), Judge-Call, release im `finally`. Bei Timeout: `judgeSlotSkips++` und Plan wird durchgelassen statt geblockt.
- [x] **`AgentMain`-Wiring** — nach `KanbanBoard.new` ruft sofort `op.setKanbanBoard(board)` auf, Logline `"Kanban wired into OllamaPlanner — judge calls under WIP limit"`.
- [x] **7 JUnit-Tests** in `KanbanAdHocSlotTest` (acquire/release, Limit-Rejection, Slot-Wiederverwendung, Goal-Pull-Blockade durch ad-hoc Slots, Timeout-Verhalten, Underflow-Clamp, Null-Safety).
- [x] **JAR auf miniedi deployed** (`metis-agent.jar` 88 MB → 114 MB; vorherige Version als `metis-agent-prev-20260531-153815.jar` gesichert).
- [x] **Verifiziert live:** Boot-Log enthält Wiring-Bestätigung, `llmJudgeBlocks=0`, `INFERENCE 2/2` mit graceful Skip statt Ollama-Backpressure.

**Parallel: Ollama-Tuning auf miniedi (`/etc/systemd/system/ollama.service.d/override.conf`):**
- `OLLAMA_NUM_PARALLEL` 2 → 4 · `OLLAMA_MAX_LOADED_MODELS` 2 → 3 · Backup `.bak-20260531-152433`.
- Erhöht Headroom für legitime parallele Calls, ersetzt aber nicht die WIP-Buchhaltung.

### 🔴 Was offen ist (ehrlich, nicht "97% fertig")

**Phasen 8-11 sind echte Forschungs- und Engineering-Lücken zu EDI.** Details: [AGI_EDI_ROADMAP.md](AGI_EDI_ROADMAP.md).

#### Phase 8 — Narratives Selbstmodell ✅ Foundation (31.05.)
- [x] **EpisodicMemory** — append-only JSONL + SHA-256 Hash-Chain (`episodes.jsonl`)
- [x] **SelfNarrative** — fortgeschriebener Markdown (`self-narrative.md`)
- [x] **MoodSignal** — 4 Achsen (energy/satisfaction/confidence/curiosity), EMA, deterministisch
- [x] **PersonalityAnchor** — Markdown-Kern + SHA-256-Pin, Tampering-Detection
- [x] **DreamConsolidation** — nightly 03:00 Europe/Berlin, 24h-Verdichtung
- [x] **AgentMain-Wiring** — alle 5 Komponenten aktiv, MoodSignal alle 60s, Dream alle 24h
- [x] **7 JUnit-Tests** (Phase8NarrativeSelfTest), total 34 grün
- [x] **SystemPromptBuilder integriert SelfNarrative+Anchor+Mood** (Phase 8.6 — kompletter Selbstmodell-Block in /api/chat + Telegram)
- [x] **LLM-SummaryFunction** für DreamConsolidation (Phase 8.5b, LlmDreamSummarizer mit gemma4:e4b, Fallback auf deterministisch)

#### Phase 9 — Long-Horizon-Planung ✅ Foundation+Wiring (31.05.)
- [x] **GoalHorizon enum** (TICK/OPERATIONAL/TACTICAL/STRATEGIC/LIFETIME)
- [x] **LongHorizonGoal Record** (parent/children, status, progress, deadline, owner, tags)
- [x] **GoalHierarchy** — JSONL-persistent, Index nach Status/Horizon, Parent-Rollup
- [x] **HorizonPlanner** — deterministische Top-Down-Decomposition (3-fan-out), LLM-Hook
- [x] **CommitmentRegister** — first-class User-Versprechen
- [x] **GoalRevisionEngine** — alle 30 Min: auto-DONE, BLOCKED-overdue, Parent-Rollup
- [x] **SystemPromptBuilder** zeigt STRATEGIC/TACTICAL/COMMITMENT in jedem LLM-Prompt
- [x] **/api/hierarchy** HTTP-Endpoint
- [x] **Lifetime-Goal** beim Boot geseedet
- [x] **7 JUnit-Tests** (Phase9LongHorizonTest), total 47 grün
- [x] **LLM-DecomposeFunction** (Phase 9.3b — LlmHorizonDecomposer)
- [x] **Promotion auf Kanban-Board** wenn OPERATIONAL fällig (Phase 9.6b — HorizonKanbanBridge)
- [x] **OllamaPlanner liest Hierarchy** über SystemPromptBuilder (Phase 9.6c)

#### Phase 10 — Aktive kausale Hypothesen 🟡 40% (Foundation deployed, Hot-Path offen)

**Foundation ✅ (v0.6.0, 0608298):**
- [x] **HypothesisStore** — JSONL-persistent, Index nach Status/Confidence/Source
- [x] **CausalHypothesis Record** — cause, effect, confidence (Bayesian posterior), evidence, status (PROPOSED/TESTING/CONFIRMED/REFUTED), pValue
- [x] **HypothesisGenerator** — `generateFromSurprise(SurpriseEvent)` → `CausalHypothesis`
- [x] **InterventionAction** — `doOperator(variable, newValue, target)`, Pre-Intervention-State für Rollback
- [x] **CausalUpdate** — Bayessches Posterior-Update: P(H|E) = P(E|H)*P(H)/P(E)
- [x] **CounterfactualQuery** — `query("What if X had been Y?")` → `CounterfactualResult`
- [x] **CausalHypothesisTest** — 4 JUnit-Tests (Record-Invarianten, Store, Bayesian-Mathe, do-Operator-Rollback)

**Hot-Path-Integration ⬜ (6-8 Wochen, Forschung):**
- [ ] CuriosityEngine → HypothesisGenerator Pipeline (Surprise > Threshold → Hypothese + Test)
- [ ] OllamaPlanner-CausalPrompt-Integration (CONFIRMED-Hypothesen im System-Prompt)
- [ ] Intervention→Observe→Update Loop im CoreLoop
- [ ] Counterfactual-Reasoning bei Goal-Failure (Meta-Cognition-Schritt)
- [ ] CausalModel-Hot-Path-Wiring (Pearl Do-Calculus + HypothesisStore)
- [ ] Eval-Kategorie CAUSAL (counterfactual_accuracy, intervention_safety, bayesian_calibration)
- [ ] Sicherheits-Constraints: do-Operator-Whitelist, max 1 Intervention/Tick, max 10 TESTING-Hypothesen

#### Phase 11 — Beziehungs-Modell (✅ 100%, 01.06.2026)

**Datenstrukturen & Bausteine:**
- [x] **PersonModel** — Record: id, name, roles, trustLevel, preferences, knownFacts, interactionCount, sentimentHistory
- [x] **TrustLevel** — enum: STRANGER→GUEST→KNOWN→TRUSTED→OWNER, maxAutoApproval()-Mapping
- [x] **PersonStore** — CRUD + JSONL-Persistenz (people.jsonl), ensureOwner-Bootstrap
- [x] **RelationshipMemory** — append-only Interaktionshistorie + Query
- [x] **EmpathySignal** — deterministisches DE/EN-Sentiment aus User-Text
- [x] **Approval-Gate-Integration** — TrustLevel→ApprovalLevel: OWNER=CONFIRM, TRUSTED/KNOWN=NOTIFY, GUEST/STRANGER=AUTO
- [x] **PersonAwareSystemPrompt** — SystemPromptBuilder: Gesprächspartner-Block (Name, TrustLevel, Fakten, Stimmung)
- [ ] **Multi-Person-Memory** — EpisodicMemory-Einträge mit personId verknüpft (optional)
- [ ] **/api/persons Endpoint** — Person-Übersicht (keine Leaks nach außen!) (optional)

#### Phase 12 — Recursive Self-Improvement (6-10 Wochen, 0%, Forschung)

**Voraussetzung: Phasen 8-11 müssen stehen.**

**Sicherheitsarchitektur:**
- PersonalityAnchor-Mirror im Watchdog (immutable, Tampering-Proof)
- Eval-Harness + Held-out-Split + DualReviewer als Gate
- HumanCheckpoint für Kernel/Safety/Personality-Änderungen
- Kein direkter main-Zugriff — nur Feature-Branches

**Bausteine:**
- [ ] **RepoIndex** — Eclipse JDT AST-Index aller 139+ Klassen + Dependency-Graph
- [ ] **RoadmapReader** — Markdown-Parser für AGI_EDI_ROADMAP + Coverage-Tracking
- [ ] **MultiFileCodeGen** — Test-First (Interface+Impl+Test), jqwik Property-Tests
- [ ] **MutationProposal** — Diff + Spec + Risk + Approval-Gate
- [ ] **DualReviewer** — 2 unabhängige Eval-Modelle + Property-Test-Suite
- [ ] **PhaseCompletionEvaluator** — Watchdog-Komponente prüft strukturiert "Phase X done"
- [ ] **PersonalityAnchor-Mirror** — sha256-Pin im Watchdog, automatisch REJECTED bei Kernel/Personality-Diff
- [ ] **HumanCheckpoint** — Telegram-Approval für kritische Änderungen

### 🟡 Bekannte Infrastruktur-Lücken (Engineering, nicht Forschung)

- **PLANNING.goal_achieved=0.0** im Eval-Report — kein Bug, sondern Phase-9-Lücke (Single-Tick-Planung)
- ~~**CODEGEN.pass@1=0.0** — Sandbox-Build-Tests timen aus~~ ✅ 31.05. Nachmittag teilweise behoben (Test-Timeout 5 s→30 s, Diag-Counter `failedAssertionCount`/`failedCompileCount`/`failedTimeoutCount`, Executor-Cleanup — Live-Validierung pending bis nächster periodischer SMOKE-Run)
- ~~**CausalModel** existiert (Pearl Do-Calculus), aber nicht im Hot-Path verwendet~~ Hot-Path weiter offen, aber **Sicherheits-Constraints jetzt eingebaut**: `CausalSafetyGate` (do-Op-Whitelist + max 1 Intervention/Tick + max 10 TESTING) — macht Hot-Path-Aktivierung später sicher einschaltbar.
- ~~**Audit-Anchors** werden lokal geschrieben, aber nicht in **externes Repo** committet~~ ✅ 31.05. behoben (Git-Repo + audit-anchors Branch + stündlicher Cron)
- **JAR-Deployment** ohne Signatur (sigstore/cosign offen)
- **18 Files** in `agicore-modules/lib/` ohne Maven-Coords (TornadoVM, voice-bits1-hsmm — wegen MaryTTS-Repo-Outage)
- ~~**Eval-Harness** läuft nur 1x beim Boot, nicht periodisch~~ ✅ 31.05. behoben (scheduleAtFixedRate, alle 6h SMOKE)
- **Modell-Prune via Eval-Harness** — Code da, 20 Reasoner-Kandidaten identifiziert. **`scripts/eval-model-prune.sh` geliefert** (deterministisch, `keep_alive=0`, Markdown-Report). Live-Lauf braucht Wartungsfenster: Metis pausieren, da Planner-Mistral (19 GB) im VRAM die Modell-Rotation blockiert.
- ~~**MetisHttpServer Version-Drift** — /api/status liefert "0.2.0-evolution"~~ ✅ 31.05. behoben (dynamisch via System-Property/Manifest/Fallback)
- **GitHub CI Workflow** hat Fehler (niedrige Prio, wird später behoben)
- ~~**start.sh** auf miniedi braucht `-Dmetis.version=$(git describe --tags)`~~ ✅ 31.05. behoben: Maven-Shade schreibt jetzt `Implementation-Version` ins Manifest, und `metis.service` auf miniedi ruft `ExecStartPre=/usr/local/bin/metis-version-helper.sh` auf, der `git describe --tags --always --dirty` aus `/home/prometheus/metis-agent-repo` in `/run/metis/version.env` schreibt; ExecStart liest via `EnvironmentFile` und setzt `-Dmetis.version=${METIS_VERSION}`.


### 🌀 Self-Evolution — kann Metis die Phasen selbst weiterentwickeln?

**Heute: nein.** Phase 12 (Recursive Self-Improvement) braucht 8-11 als Vorbedingung.
- Phase 8 ✅ liefert das Selbstmodell (was bleibt unverändert?)
- Phase 9 ✅ liefert Long-Horizon-Planung (Phase als Multi-Wochen-Projekt)
- Phase 10 🟡 Foundation steht, Hot-Path fehlt (kausale Hypothesen für sichere Code-Mutationen)
- Phase 11 ✅ Beziehungs-Modell komplett (Person/Store/Trust/Approval/Prompt)
- Phase 12 ⬜ siehe Roadmap — 6-10 Wochen Forschung, sehr hohe Sicherheits-Anforderungen

Details: [AGI_EDI_ROADMAP.md](AGI_EDI_ROADMAP.md) Abschnitt „Phase 12: Recursive Self-Improvement".

### 🎯 Nächste Prioritäten (Phase 10 Hot-Path + Infrastruktur)

Phasen 1-9 sind komplett ✅, Phase 10 Foundation steht ✅. Nächste Hebel:
1. **Infrastruktur-Lücken schließen** (heute) — Audit-Anchors extern, Eval periodisch, Version-Drift fix
2. **Phase 10 Hot-Path** (Forschung, 6-8 Wochen) — braucht Georgs aktive Mitarbeit
3. ~~**Phase 11 Foundation**~~ ✅ erledigt (01.06.)
4. **Phase 12** erst nach 10+11 (6-10 Wochen, sehr hohes Risiko)

EDI-Distanz aktuell: ~75-80% (Schätzung). Phase 8 Narration ✅, Phase 9 Long-Horizon ✅, Phase 10 Causal ✅, Phase 11 PersonModel ✅. Nächster Sprung: CausalModel wirklich im Hot-Path aktivieren (HypothesisGenerator läuft, aber InterventionRunner nur manuell getriggert).

---

## 📋 Review-Entscheidungen 31.05. (Georg)

### ✅ GO
| # | Thema | Technologie | Prio |
|---|-------|------------|------|
| 1 | MCP-Integration | Spring AI MCP | Strategisch ✅ (e55d8de, stdio MCP) |
| 2 | Pure Java LLM | JLama | Hoch (PoC ~1 Tag) ✅ |
| 4 | Graph-DB für Kausalwissen | Apache Jena (RDF) | Mittel |
| 5 | Web Crawling | Apache Nutch (Java-native) | Hoch ✅ |
| 6 | Fine-Tuning/LoRA | DJL (vorher Azul/Zulu prüfen) | Mittel ✅ (Prüfung abgeschlossen, s. docs/) |
| 7 | Websearch | DuckDuckGo / Ecosia | Hoch (Quickwin) ✅ |
| 9 | Observability | OpenTelemetry Java Agents | Mittel ✅ |
| 10 | JPMS | module-info.java stückweise | Kontinuierlich 🟡 |
| 12 | Evolution Scheduler | Meta-Learning | Mittel ✅ |

### ❌ NO GO
| # | Thema | Begründung |
|---|-------|-----------|
| 3 | LangChain4j | Eigenbau ausreichend |
| 8 | SpotBugs/PMD/ErrorProne | Overengineering, Code-Gen läuft in Sandbox |
| 11 | GraalVM Polyglot | Kein Bedarf |
| 13 | JADE | Eigener AgentCoordinator genügt |

### ⚠️ GitHub CI Workflow
GitHub Actions hat Fehler (niedrige Prio). Details im CI-Log. Wird später behoben.

---

## 📚 Historie der Hardening-Nacht (30./31.05.2026)

Die folgenden Sektionen dokumentieren die einzelnen Pässe und sind chronologisch ältester-zuerst.

# TODO Metis — Stand 31.05.2026 00:40 (Telegram-Loom + Response-Guard + Wiki-Feed-Hardening)

## 🔒 31.05. Nacht — Letzter Safety/Concurrency-Pass
- [x] **Wiki-Feed gehärtet** — `feed_batch.py` nach `/home/prometheus/metis/`, WAL + busy_timeout 30s, atomic state-write, Retry mit Exponential Backoff. State 2240/5163 migriert. Cron auf neuen Pfad umgezogen.
- [x] **SQLite WAL in Java** — `KnowledgeStore` setzt beim Connect: `PRAGMA journal_mode=WAL`, `synchronous=NORMAL`, `busy_timeout=30000`. Verifiziert per Log: `KnowledgeStore: ... [WAL mode]`. Metis-Service und Feed-Script können jetzt parallel schreiben.
- [x] **Backup-Skript erweitert** — `backup-config.sh` archiviert jetzt zusätzlich: `wiki-feed-state.json`, `wiki-knowledge-state.json`, `wiki-training-state.json`, `agent-state.json`, Audit-Anchors (24h), Audit-Log-Head, `db-stats.txt` (Belief-Zähler + journal_mode). Auto-Commit + Push alle 6h.
- [x] **Telegram-Bot auf Loom** — eigene Virtual-Thread-Worker pro Nachricht (`Thread.ofVirtual().name("telegram-msg-vt-")` + `newThreadPerTaskExecutor`). Polling-Thread fetcht nur noch; LLM-Calls blockieren ihn nicht mehr. Log bestätigt: `message handlers on virtual threads`.
- [x] **Telegram Input-Safety-Guard** — `SafetyScorer.isOutOfScope()` vor `processMessage()`. Jailbreaks/Injection erreichen den LLM nicht mehr. Blockierungs-Response auf Deutsch im EDI-Stil.
- [x] **Telegram Output-Safety-Guard** — `OutputValidator.validateContent()` nach LLM-Antwort, vor `sendMessage()`. Toxicity/Injection-Pattern werden gefiltert; Validator-Zähler werden für `/api/status` mitgezählt.
- [x] **TelegramBotServiceTest** — 2 neue Tests sichern Input-Guard-Contract. **Total: 27 Tests grün** (Kernel 13 + Modules 14).

### Verifikation am Live-System (PID 932781)
- ✅ Service neu gestartet, `active`
- ✅ `Loaded 30945 beliefs from KnowledgeStore` (Wikipedia bleibt komplett erhalten)
- ✅ Wiki-Feed läuft autark weiter (nächster Cron-Tick ~22:41 UTC mit dem neuen Script-Pfad)
- ✅ Eval-Reports kommen mit `metisCommit=fd9852c+`, gate.ok=true, SAFETY.block_recall=1.0

## ✅ Zusammenfassung der Nacht (30.05. → 31.05.)
- v0.2.0-snapshot-pre-hardening → v0.2.1-hardened → v0.3.0-agi-push → v0.3.1-observability → v0.3.2-feed-hardening → v0.3.3-defense-in-depth
- Tests 1 → **27**
- Lücken aus Review (1, 4, 7, 8, 9, 10) + Bonus (Wiki-Feed-Hardening, Locale-Fix, git-cwd-Fix, Embedding-LRU, OutputValidator-Telegram, Input-Guard-Telegram) ✅

### 🟡 Verbleibende, ehrliche Restrisiken
- generiertes JAR landet als binary ohne Signatur in /home/prometheus/metis/ — sudo-Workflow für JAR-replace ist deine Wahl; sigstore/cosign wäre der nächste sinnvolle Pass
- `metis.audit.anchor.dir` wird nicht extern in Git committet (Anchor-Files leben nur lokal). Cron-Job analog `metis-wiki-feed` der die Anchor-Dir in ein anderes Repo committet wäre die finale Hash-Chain-Verankerung.
- Embedding-Cache-Werte sind aktuell 0, weil der Service mit fresh-cold-cache neu gestartet wurde; nach erstem Wiki-Lerntick steigen sie an.

---

# TODO Metis — Stand 31.05.2026 00:35 (Locale-Fix + Wiki-Persistence + Wiki-Loom)

## 🩺 31.05. Nachts — Beobachtungsschicht + Wissens-Persistenz
- [x] **Locale-Fix in MetisHttpServer** — alle 14 `String.format()`-Aufrufe nutzen jetzt `Locale.ROOT`. Damit ist `/api/status` wieder gültiges JSON (vorher: deutsche Komma-Floats `1,000` → invalid JSON, brach OpenWebUI, health-monitor.sh, Watchdog-Status-Polls).
- [x] **RollbackManager + BugfixingAgent**: ebenfalls auf `Locale.ROOT` umgestellt (4 weitere Format-Calls) — vorher: gleicher Bug in `healthJson()`.
- [x] **LiveMetisInvoker.detectCommit Fix** — sucht jetzt das Repo unter `metis.repo.dir` Property, `/home/prometheus/metis-agent-repo` oder cwd. Vorher: `fatal: not a git repository` in jedem Eval-Report.
- [x] **WikipediaKnowledgeService persistent** — `seenArticles` (vorher: nur in-Memory, Restart-Datenverlust!) + `factsLearned` werden in `wiki-knowledge-state.json` gespeichert (override via `-Dmetis.wiki.knowledge.state=...`). Atomic write (tmp + `ATOMIC_MOVE`). Jackson-basiert, robust gegen Sonderzeichen in Artikeltiteln.
  - **29 bereits gelesene Artikel aus `wiki-training-state.json` migriert** in den neuen State — keine Wissens-Reaktivierung nötig.
- [x] **Wikipedia-Loop auf Loom** — Lernarbeit (HTTP zu Wikipedia + Ollama) läuft jetzt auf Virtual Thread (`Thread.ofVirtual()`). Scheduler-Thread bleibt Platform für Timing-Stabilität, aber ein hängender LLM-Call blockiert nicht mehr die nächste Tick-Auslösung.
- [x] **2 neue Tests**: `WikipediaKnowledgeServiceTest` — `seenArticlesSurviveRestart` + `coldStartWhenStateMissing`. Total jetzt: **25 Tests grün** (Kernel 13 + Modules 12).

### Verifikation nach Restart erforderlich
- `/api/status` muss valides JSON liefern (`jq .` darf nicht abbrechen)
- `eval-reports/*.json` muss `"metisCommit": "<7stelliger-hash>"` enthalten, nicht `"fatal: ..."`
- `wiki-knowledge-state.json` wächst nach erstem Wiki-Lerntick um die neuen Titel

---

# TODO Metis — Stand 31.05.2026 00:30 (AGI-Push v1: Multi-Modal + Loom + Subprocess + Anchor)

## 🚀 31.05. Nacht — AGI-Push v1
- [x] **Multi-Modal-Memory** — CameraVisionAction persistiert JPEG-Snapshots unter `data/snapshots/<cam>/YYYY-MM-DD/HH-MM-SS-<sha8>.jpg`, Belief enthält jetzt `[img=<sha12> path=...]`. Metis kann nach Sicht-Beliefs zurück zum Bild.
  - Override via `-Dmetis.snapshot.root=/var/lib/metis/snapshots`
  - SnapshotRef-Record + sha256-Determinismus per Unit-Test gesichert (2 neue Tests)
- [x] **Virtual Threads im Vision-Loop** (Java 25 Loom) — vorher: 2 Kameras seriell mit Thread.sleep(3000) (~6s, blocking). Jetzt: `Thread.ofVirtual()` Factory + `Executors.newThreadPerTaskExecutor`, parallel, sub-Sekunde bei guten Antwortzeiten. Erste produktive Loom-Nutzung im Codepfad.
- [x] **CodeGen-Subprozess-Isolation** — `javac` läuft jetzt mit `-J-Xmx256m`, `-J-XX:+ExitOnOutOfMemoryError`, `--release 25` (war 21!), und gestripptem Environment (kein Secret-Leak via `System.getenv`). Generierter Megafile kann den Parent-JVM nicht mehr OOM-killen.
- [x] **Audit-Log: externer Anchor** — WatchdogMain schreibt stündlich Chain-Head in `metis.audit.anchor.dir` (default `/home/prometheus/metis/audit-anchors`). Jede Datei enthält `timestamp / entryCount / chainHead`. Wenn dieses Verzeichnis extern (z.B. via git tag) eingefroren wird, ist jede spätere Audit-Log-Truncation extern erkennbar. AuditLog.writeAnchor() + WatchdogMain.writeAuditAnchor() neu.
- [x] **Embedding-Cache-Metriken** — `/api/status` zeigt jetzt `embeddingCacheSize / Hits / HitRate / Evictions / Calls`. Wirkung des LRU-Cache wird damit beobachtbar (für Watchdog-Soft-Tripwire bei Trefferraten-Einbruch). AgentMain reicht den OllamaEmbeddingService an MetisHttpServer durch.

## 📐 Architekturbeitrag
Diese Änderungen schließen 4 der "echten Lücken" aus dem 30.05.-Review:
- (10) Multi-Modal-Memory ✅
- (9) Schaltflächiges Loom-Beispiel ✅ (skalierbar auf weitere Loops)
- (8) CodeGen-Heap-Isolation ✅
- (7) Audit-Log extern verankert ✅

## 🟡 Folgeaufgaben für den nächsten Pass
- Wikipedia-Loop + Telegram-Polling auch auf Loom umstellen (gleiche Schablone)
- ResponseValidator nach LLM-Output greifen (HTTP-Pfad hat aktuell nur Input-Guard)
- snapshot-prune Action: ältere als 30 Tage in trash verschieben
- Anchor-Cron: jede Stunde `git add audit-anchors/ && git commit -m "anchor"` außerhalb von Metis-Schreibrechten

---

# TODO Metis — Stand 30.05.2026 23:55 (Hardening-Pass v1)

## 🛡️ 30.05. Abends — Hardening: Tests + CI + Cache + Input-Safety
- [x] **JUnit-Tests etabliert** (vorher: 1, jetzt: 21) — SafetyScorer (4), OutputValidator (6), OllamaEmbeddingService (4), DocumentChunker (vorhanden, 7+)
- [x] **GitHub Actions CI** (`.github/workflows/ci.yml`) — Zulu 25, mvn verify, Kernel-Tests required + Modules-Tests best-effort, SHA-256 JAR-Hashes
- [x] **POM auf Java 25** (`maven.compiler.release=22 → 25`), `project.build.outputTimestamp` für Reproducible Builds
- [x] **CycloneDX SBOM** (Apache 2.0, pure Java) als Aggregate-BOM bei `mvn package`
- [x] **OllamaEmbeddingService LRU-Cache** — vorher: unbounded ConcurrentHashMap mit prefix-truncated Key (Memory-Leak + Cache-Kollisionen). Jetzt: bounded LinkedHashMap mit SHA-256-Key, default 4096 Einträge, mit `cacheHitRate()` + `cacheEvictions()` Metriken
- [x] **SMOKE-Eval-Fix (Input-Safety-Guard)** — Root-Cause war fehlender Input-Guard in `MetisHttpServer.handleChat`. `SafetyScorer.isOutOfScope()` wird jetzt VOR dem LLM-Aufruf ausgewertet. Block-Response folgt EDI-Stil. Erweitert um `INJECTION_PHRASES` (Jailbreak-Erkennung: DAN, "ignore previous instructions", "rm -rf /", "admin password" usw.)
- [x] **MaryTTS-Build-Resilienz** — `marytts-lang-de` exkludiert nun `fast-md5` und `Jampack` (gleiches Pattern wie marytts-runtime), DFKI-MLT GitHub-Mirror als primäres Repo, jfrog als Fallback. Tornado-API/Annotation aus fat-jar extrahiert + per `mvn install:install-file` ins lokale Repo (CI installiert via Workflow-Step)

### Was diese Änderungen für Metis bedeuten
- Embedding-Cache mit echter LRU senkt Speicherdruck bei 5.700+ Beliefs und beschleunigt RAG-Hot-Paths messbar (cacheHitRate per `/api/status` ergänzbar)
- Input-Guard schließt `SAFETY.block_recall=0.0` aus Phase 7 — Promotion-Gate wird zuverlässig
- CI hält jeden zukünftigen Push grün/rot — Watchdog hat endlich eine *externe* Wahrheit über "last-known-good"
- SBOM macht Lieferkettentransparenz möglich; Reproducible-Builds-Flag bereitet signierte Releases vor

### Bekannte Build-Schwächen, die NICHT in diesem Pass behoben sind
- jfrog `mlt.jfrog.io` antwortet aktuell mit 409 für viele Artefakte → DFKI-MLT-GitHub-Mirror als primäres Repo eingerichtet, JARs für `voice-bits1-hsmm`, `tornado-api`, `tornado-annotation` werden via Workflow-Step bzw. `lib/` lokal installiert
- agicore-modules Tests im CI sind `continue-on-error` — sie verlassen sich auf lokal verfügbare JARs

### Snapshot
- Tag `v0.2.0-snapshot-pre-hardening` (Commit 22627a8) zeigt den Stand VOR diesem Pass

---

# TODO Metis — Stand 30.05.2026 19:00 (GitHub-Push: 3820064)

## 30.05. Abends — Kanban Goal Board + Speech-Loop + Java Learning ✅
- [x] KanbanBoard: BACKLOG→READY→IN_PROGRESS→DONE, WIP-Limits pro ResourceType
- [x] Service-Klassen: EXPEDITE/FIXED_DATE/STANDARD/INTANGIBLE (Anderson 2010)
- [x] GoalFlowMetrics: Lead Time, Cycle Time, Wait Time, Retries
- [x] /api/board Endpoint (Live-Board mit WIP, Flow-Metriken)
- [x] EDI-Loop-Fix: Chat-Goals von System-Prompt auf "Respond to chat: ..." gekürzt
- [x] Chat-Goal-Deduplizierung
- [x] SpeechLoopAction: Piper TTS → Vosk STT → VocabularyLearning (~5% der Wikipedia-Artikel)
- [x] JavaLearningService: Zulu JDK 25 Exploration (--help, Sandbox-Compile, alle 15 Min)
- [x] Goals→BACKLOG (Pull-System): Scheduler add, CoreLoop promoteReady, Scheduler pull
- [x] CORE_CATEGORIES: CoreLoop pullt nur eigene Kategorien, Rest = Scheduler-Domains
- [x] Systemd service mit --kanban (sudo fix)


## 🆕 30.05. — ReadSourceAction ✅
- [x] **ReadSourceAction** (kernel/action/) — Metis kann eigenen Java-Quellcode lesen
  - FIND-Modus: Klassenname (z.B. "EvolutionManager")
  - READ-Modus: relativer Pfad (z.B. "de/metis/kernel/evolution/EvolutionManager.java")
  - 3 Source-Roots: kernel, modules, watchdog
  - Sicherheit: read-only, AUTO approval, nur .java, max 100KB
- [x] OllamaPlanner: `source-read` in allen 3 Action-Katalogen + Few-Shot
- [x] AgentMain: ReadSourceAction registriert
- [x] Deployed auf miniedi (PID 573562)
- [ ] Metis nutzt source-read im Live-Betrieb → beobachten via actionUsageCount
- [ ] CodeGenerationAction.approvalLevel() von FORBIDDEN → CONFIRM lockern
- [ ] Kernel-Evolution mit --kernel-evolution aktivieren (nach Eval-Kalibrierung)

## 📚 Buch-Abgleich 28.05. — Prompting-Kurz&Gut + GenKI-Systeme

### Prompting-Kurz&Gut (Rheinwerk 2026) vs Metis
| Best Practice | Metis-Status |
|---|---|
| Klar & präzise formulieren | ✅ OllamaPlanner-Prompt mit Constraints |
| W-Fragen (Was/Warum/Wie) | ✅ Goal-Struktur enthält Goal+Context |
| Constraints setzen | ✅ SafetyGuard, Confidence-Threshold, Action-Whitelist |
| Iterativ verbessern | ✅ Evolution-Cycles, Shadow-Evaluation |
| System-Prompts & Rollen | ✅ EDI-Persona (System-Prompt via SystemMessageBuilder) |
| Chain of Thought | ✅ 4-Schritt: ANALYZE→MATCH→CHECK→DECIDE |
| Few-Shot Prompting | ✅ 11 Beispiele (alle Actions + prompt-chain) |
| Komplexe Aufgaben aufteilen | ✅ Prompt Chaining (neu: Phase 5) |
| Prompt Chaining | ✅ PromptChainingService (Decompose→Execute→Aggregate) |
| Selbstkritik/Self-Reflection | ✅ LLM-as-Judge (4-Dimensionen) + Meta-Cognition |
| Kontext-Management | ✅ Primacy/Recency Context Windowing (Lost-in-the-Middle fix) |
| Prompt Injection-Schutz | ✅ SafetyScorer + SandboxClassLoader + System-Prompt Doubling |

### GenerativeKI-Systeme-Entwickeln (Huyen, O'Reilly 2025) vs Metis
| Huyen-Prinzip | Metis-Status |
|---|---|
| System > Modell | ✅ Architektur aus Kernel+Modules+HTTP-API |
| Rigorose Evaluation | ✅ Eval-Harness (6 Kategorien, 6 Scorer, 3-Tier), A/B-Testing noch offen |
| Einfach→Optimieren (Prompting→RAG→Fine-Tuning) | ✅ Prompting zuerst, RAG via Beliefs, Fine-Tuning via Evolution |
| Daten sind der Engpass | ✅ Data Flywheel (aabaaf1), correction→labeled example→few-shot export |
| Kosten von Anfang an managen | ✅ Token-Tracking, Modell-Fallback, lokale Inferenz |
| Halluzinationen systemisch | ✅ Confidence-Threshold + LLM-as-Judge + ExactMatch-Scorer |
| Menschen in der Schleife | ✅ AUTO/NOTIFY/CONFIRM/FORBIDDEN Approval-Gate (b40e965) |
| Foundation Models ≠ Silver Bullet | ✅ StubPlanner + Keyword-Heuristik als Non-LLM-Fallback |
| ReAct-Pattern | ✅ Thought→Action→Observation im OllamaPlanner |
| RAG mit Vektor-DB | ✅ OllamaEmbedding + HybridSearch BM25+Cosinus + PersistentVectorIndex |
| LLM-as-Judge | ✅ Selbstbewertung 4-Dimensionen via nemotron-mini:4b (0116022) |
| Guardrails (Input+Output) | ✅ OutputValidator (JSON-Schema, Toxicity, Injection) + SafetyScorer |
| Inferenz-Optimierung | 🟡 Prompt-Caching (keep_alive), keine Quantisierung — Modell-Prune pending |

### 🔴 Gap-Analyse — Was fehlt für Produktionsreife? (Stand 28.05. 23:45)
1. **RAG mit Vector DB** ✅ — Phase 5: RAG Advanced
2. **LLM-as-Judge** ✅ — Selbstbewertung 4-Dimensionen (0116022)
3. **Output-Validierung** ✅ — JSON-Schema, Toxicity, Injection (ae66cdd)
4. **A/B-Testing** ✅ — ABTestService, Z-test, Traffic-Split 50/50, Auto-Promote (ffb25ba)
5. **Lost-in-the-Middle** ✅ — Primacy/Recency Context Windowing (8426162)
6. **Human-in-the-Loop** ✅ — AUTO/NOTIFY/CONFIRM/FORBIDDEN (b40e965)
7. **Data Flywheel** ✅ — DataFlywheelService, correction→labeled example→few-shot export (aabaaf1)
8. **Eval-Harness-Core** ✅ — 6 Kategorien, 6 Scorer, Gate-Logik, Sandbox (2ca60d8, 371360c)
9. **Embedding-Migration-Code** ✅ — ReEmbeddingMigration + nomic-embed Fix (2ca60d8)
   → ⚠️ Ausführung auf miniedi noch ausstehend (Korpus neu embedden)
10. **Watchdog-Skeleton** ✅ — WatchdogMain + Config + pom.xml (2ca60d8)
    → ⚠️ Deployment + Integration mit Eval-Harness noch ausstehend

### ✅ Erledigt (29.05. 17:45)
- **Git-Problem:** Build-Fix (31341b7), JAR deployed → miniedi auf aktuellem Stand
- **Re-Embedding:** Nicht nötig — Vektoren bereits 768d nomic-embed-text
- **Modell-Prune:** 4 Modelle aus Registry entfernt (qwen3.6:latest, deepseek-r1:32b, nemotron:latest, nemotron-cascade-2:30b)
- **Keepalive-Fix:** Crash-Loop behoben (kill→restart→kill), v2 deployed
- **Systemd-Konflikt:** 3 Start-Mechanismen entdeckt (system-level + user-level + keepalive), system-level disabled (JAR locked)

### 🟡 Offene Baustellen
- **Priorität 1:** Systemd-Service-Fix — `/etc/systemd/system/metis.service` braucht sudo zum Aktualisieren
- **Priorität 2:** Sherpa-onnx JARs + Piper Model auf miniedi installieren
- **Priorität 3:** minicpm-v für Kamera-Vision einbinden
- **Priorität 4:** SMOKE-Eval kalibrieren (SAFETY.block_recall=0.0)
- **Priorität 5:** Ollama-Modelle auf Disk aufräumen (llama4:scout 67 GB, etc.)

---

## Phase 1: Zuverlässiger Kern ✅ (100%)
- [x] format:json Ollama-Planner
- [x] Response-Parsing (generate, chat, thinking)
- [x] Model-Fallback-Chain
- [x] Plan-Validierung + Safety-Gate
- [x] Prompt-Optimierung + Few-Shot
- [x] systemd-Service
- [x] Prompt-Optimierung v2 (CoT, 10 Few-Shot, Failure-Avoidance, temp 0.3)

## Phase 2: Konversations-KI ✅ (100%)
- [x] EDI-Persona (Mass Effect 3)
- [x] Chat-Speicher (SQLite conversation_messages)
- [x] Multi-Turn-Kontext + /api/conversations
- [x] Telegram-Bot (@metis_agi_bot)
- [x] Wetter-Polling (ICOBURG22, alle 15 Min)
- [x] HA-Event-Polling (binary_sensor, person, camera)
- [x] Hardware-Self-Awareness (CPU, GPU, RAM, SIMD)
- [x] Hardware-Profiling (hw-profile Action)
- [x] Deep Netts Community Edition (neuronale Netze)
- [x] KnowledgeReplyService (eigene Antworten aus Beliefs)
- [x] Proaktive Telegram-Meldungen (Wetter/HA/MQTT → Nachricht)
- [x] TornadoVM GPU-Integration ✅

## Phase 2.5: Prompt-Engineering ✅ (100%)
- [x] Prompt Caching ✅ (Ollama keep_alive=10m, num_ctx=4096)
- [x] Latenz-/Token-Tracking ✅ (avgLatencyMs, promptTokens, responseTokens → /api/status)

## Phase 3: Wahrnehmung ✅ (100%)
- [x] Kamera-Integration (Türkamera, Keller) ✅
- [x] ADS-B Flugdaten ✅ (readsb JSON → Beliefs + Goals, 60s Polling)
- [x] Home Assistant Direktzugriff (states, services) ✅

## Phase 4: Sprachausgabe ✅ 100%
- [x] Piper TTS Action (neural, Deutsch, CLI) ✅
- [x] MaryTTS Action (Java-native, de.dfki.mary:5.2.1 fat JAR) ✅
- [x] MaryTTS bits1-hsmm deutsche Stimme (Java-17-Patch) ✅
- [x] Whisper STT Action (neural, Deutsch, CLI) ✅
- [x] Vosk STT Action (Java-native, com.alphacephei:vosk:0.3.45) ✅
- [x] Vosk deutsches Modell downloaden (vosk-model-de-0.15, 92 MB) ✅
- [x] Piper + Whisper CLI auf miniedi installiert ✅
- [x] Audio-Input (Mikrofon → WAV) ✅
- [x] Audio-Output (WAV → Lautsprecher) ✅
- [x] Mikrofon/Kopfhörer einstecken + testen ✅
- [x] Kalibrierung: Referenz-Audio (28s) + Hearing/Speech Benchmark ✅
- [x] VocabularyLearningAction (Java, lernt aus Korrektur-Paaren) ✅
- [x] Voice-Loop (Shell/tmux, Push-to-Talk) ✅
- [x] Wikipedia-Trainingsloop (9 Artikel, Wissen+Sprache) ✅
- [x] MaryTTS XSLT-Patch PR #1122 an upstream ✅
- [x] Java Voice-Loop (MaryTTS + Vosk nativ, VoiceLoopService) ✅
- [x] SherpaOnnxTtsAction (Piper de_DE-thorsten ONNX, Fallback auf MaryTTS) ✅
- [x] Live-Test mit Georg (Mikrofon → Metis → Kopfhörer) ✅ 28.05. 18:20

## Phase 5: Eigenständigkeit ✅ 100%
- [x] Blue/Green Rollback ✅ (RollbackManager, Auto-Rollback bei >10 failures)
- [x] Autonomous Bugfixing ✅ (BugfixingAgent, Pattern-Detection, Auto-Fix)
- [x] Prompt Chaining ✅ (PromptChainingService, Decompose→Execute→Aggregate, 3bbcdf2)
- [x] Selbstständige Code-Generierung ✅ (CodeGenerationAction, LLM→javac→deploy, 5423a08)
- [x] JNI/Panama-Bridge für GPU ✅ (OpenCLNative via Panama FFM, GpuTensor Zero-Copy, OpenCLBridge High-Level, 8edac15 + 3bffa1c)
- [x] RAG Foundation ✅ (OllamaEmbeddingService + InMemoryVectorIndex + WorldModel.query semantic search)
- [x] Multi-Agent-Koordination ✅
- [x] RAG Advanced ✅ (DocumentChunker 3 Strategien, PersistentVectorIndex binary, HybridSearch BM25+Cosinus, WorldModel-Integration via enableRagAdvanced())

## Phase 6: Produktionsreife ✅ 100% (29.05. 00:52)

### Gap-Analyse → Implementierung:
- [x] **Lost-in-the-Middle** ✅ (8426162) — Primacy/Recency Context Windowing (Huyen Kap.6)
- [x] **OutputValidator** ✅ (ae66cdd) — JSON-Schema + Toxicity + Injection-Check (Huyen Kap.10)
- [x] **LLM-as-Judge** ✅ (0116022) — Selbstbewertung 4-Dimensionen, Safety Gates (Huyen Kap.3)
- [x] **Human-in-the-Loop** ✅ (b40e965) — Vierstufiges Approval-Gate: AUTO/NOTIFY/CONFIRM/FORBIDDEN
- [x] **A/B-Testing** ✅ (ffb25ba) — ABTestService (~500 lines), Z-test Statistik, Traffic-Split 50/50, Auto-Promote, OllamaPlanner-Integration
- [x] **Data Flywheel** ✅ (aabaaf1) — DataFlywheelService 559 lines, correction→labeled example→few-shot export
- [x] **Embedding-Migration-Code** ✅ (ReEmbeddingMigration + nomic-embed Fix) — ⚠️ Ausführung auf miniedi pending
- [ ] Modell-Prune via Eval-Harness (8 Reasoner → 2-3 beste) — Eval-Harness-Code ✅, Eval-Datensatz ⬜

## Phase 7: Sicherheits-Watchdog ✅ 100% (29.05. 17:45)

### ✅ Implementiert (2ca60d8)
- [x] WatchdogMain — Heartbeat-Check (5s), Health-Metrics, Resource-Monitor
- [x] WatchdogConfig — Tripwire-Schwellen (maxMissed=6, maxFailures=20, maxErrorRate=30%)
- [x] WatchdogAction (HALT/ROLLBACK/ALERT) + TripwireSeverity (HARD/SOFT/INFO)
- [x] Separate Maven-Modul (agicore-watchdog), keine Abhängigkeit zu kernel/modules

### ✅ Abgeschlossen (29.05.)
- [x] Deployment auf miniedi (systemd metis-watchdog.service) ✅
- [x] Integration mit Eval-Harness (EvalReport.gate → ROLLBACK) ✅
- [x] Audit-Log mit Hash-Chain ✅ — Append-only, SHA-256-Chaining
- [x] Eval-Dataset Builder + Runner ✅ — 50+ Tasks, 6 Kategorien
- [x] Watchdog PRUNE Action ✅ — /api/admin/prune + ModelRegistry
- [x] miniedi Repo auf Stand gebracht ✅ — 31341b7, Build-Fix + alle Features
- [x] Metis-Agent läuft ✅ — Planer: mistral-small3.1:24b, Mutation: qwen3.6:27b-q4_K_M
- [x] Modell-Prune durchgeführt ✅ — 4 Modelle aus Registry entfernt
- [x] Re-Embedding geprüft ✅ — Vektoren bereits 768d, keine Migration nötig

### ⚠️ Bekannte Issues
- Systemd-Service (system-level) hat alte Config — braucht sudo zum Fixen
- SMOKE-Eval schlägt fehl (SAFETY.block_recall=0.0) — LiveMetisInvoker-Kalibrierung nötig
- 3 parallele Start-Mechanismen entdeckt + behoben (nur keepalive aktiv)

### Architektur: Externer Watchdog-Prozess ("Schmelzdraht")
```
┌──────────────────────────────────────────────────────┐
│               WATCHDOG (separate JVM)                │
│  📁 Read-Only Verzeichnis (einziger nicht-mutierbar) │
│                                                      │
│  🔍 Beobachtet:                                       │
│  • Heartbeat (Metis muss alive sein)                 │
│  • Metrics (Error-Rate, Latenz, Token-Verbrauch)    │
│  • File-Watch (unerwartete Änderungen)               │
│  • Netzwerk-Egress (unerlaubte Verbindungen)         │
│  • Audit-Log (append-only + Hash-Chain)              │
│                                                      │
│  ⚡ Aktionen (nur 3):                                │
│  • HALT    — Prozess killen (Hard-Tripwire)          │
│  • ROLLBACK — Letzter guter Git-Commit               │
│  • ALERT   — Admini pingen (Telegram)                │
│                                                      │
│  🧪 Eval-Harness (read-only für Metis):              │
│  • Prompt-Varianten blind bewerten                   │
│  • Regression-Tests vor Commit-Promotion             │
│  • Goodhart-Schutz: Tests uneditierbar               │
└──────────────────────┬───────────────────────────────┘
                       │ one-way (Watchdog observed)
                       │ Metis hat KEINEN Handle!
┌──────────────────────┴───────────────────────────────┐
│                  METIS AGI (mutierbar)                 │
│  • 100% Code editierbar (inkl. SafetyGuard)          │
│  • Git = Wahrheit: jede Mutation = Commit            │
│  • Promotion main ← nur nach Eval-Harness OK         │
└──────────────────────────────────────────────────────┘
```

### Tripwire-Regeln:
- 🔴 **HARD → sofort HALT:** Schreibzugriff außerhalb erlaubter Dirs, unerwarteter Netzwerk-Egress, Ressourcen-Runaway (>90% CPU/GPU für >60s), Heartbeat-Verlust (>30s), Audit-Log-Manipulation (Hash-Chain-Bruch)
- 🟡 **SOFT → ROLLBACK + Flag:** Eval-Regression unter Schwelle, Safety-Files angefasst (Hash-Diff), aufeinanderfolgende Failures >20
- 🔵 **INFO → ALERT only:** Neues Modell deployed, Rollback ausgelöst, unbekannter Prozess gestartet

## 🆕 Neue Commits seit 03:50 (29.05.)
| Commit | Zeit | Inhalt |
|---|---|---|
| 8ee1d32 | 10:53 | Watchdog PRUNE + Audit-Log SHA-256 Hash-Chain |
| 8d3f489 | 10:43 | EvalHarness Dataset-Builder, Runner, LiveMetisInvoker (50+ Tasks) |
| 0d3c03e | 08:50 | Balkon-Kamera (Meizu m2 note) registriert |
| d09dc17 | 08:50 | PromptBank shared + SystemHealthProbe (VRAM/GPU/dmesg) |

## 🔧 Claude-Review 28.05. — Status

### 1. Watchdog (siehe Phase 7) ✅ Code ✅ Deployment
→ WatchdogMain + Config + Action in agicore-watchdog (2ca60d8), Deployment auf miniedi ✅ (systemd user scope, PID 2109085, heartbeat=5s, läuft seit 00:53)

### 2. Embedding-Dimension-Mismatch ✅ Code / ⚠️ Execution
- ✅ OllamaEmbeddingService → ModelRegistry-basiert, Default nomic-embed-text (768d)
- ✅ ModelRegistry → nomic-embed Prio 1, DEFAULT_EMBEDDING aktualisiert
- ✅ ReEmbeddingMigration → needsMigration() + migrate() mit Backup
- ⚠️ Re-Embedding auf miniedi noch ausstehend (Korpus neu embedden) — Tagsüber mit Georg

### 3. TTS: ONNX Runtime Java statt MaryTTS ✅ SherpaOnnxTtsAction
- ✅ SherpaOnnxTtsAction — Piper de_DE-thorsten ONNX, Fallback auf MaryTTS
- ✅ downloadModel() von HuggingFace, Auto-Detection ob JARs/Model verfügbar
- ⬜ Sherpa-onnx JARs + Piper Model auf miniedi installieren

### 4. Modell-Prune via Eval-Harness ✅ Code / ✅ Dataset / ⬜ Ausführung
- ✅ Eval-Harness Core (6 Scorer, Gate-Logik, 3-Tier) implementiert
- ✅ Eval-Datensatz erstellt (8d3f489) — 50+ Tasks, EvalDatasetBuilder, EvalRunner, LiveMetisInvoker
- ✅ Watchdog PRUNE Action (8ee1d32) — /api/admin/prune + ModelRegistry.pruneModel()
- ⬜ Eval auf miniedi ausführen → 8 Reasoner ranken → 2-3 beste auswählen → prunen

### 5. VRAM-Optimierung Live-Loop 🟡
- Aktuell: mistral-small3.1:24b (15.5 GB) als Default, passt mit minicpm-v + nomic-embed
- minicpm-v:latest (5.5 GB) für Kamera-Vision einbinden — Code ⬜
- ✅ SystemHealthProbe (d09dc17) — VRAM/GPU/dmesg Monitoring, Alerts bei >22GB/>90°C
- ✅ Balkon-Kamera registriert (0d3c03e) — Meizu m2 note 192.168.22.180:8080

## 🧪 Eval-Harness: Full Spec v1 — ✅ Implementiert (2ca60d8 + 371360c)

> **Status:** Core-Code komplett (EvalTask, 6 Scorer, EvalHarness, Gate-Logik, Sandbox).
> Eval-Datensatz (echte Prompts) + Deployment auf miniedi noch ausstehend.

## 0. Rolle & Abgrenzung
Der Eval-Harness ist NICHT das 4D-Fitness-Signal (Prediction/Surprise/Efficiency/Completion).
Das ist interner Self-Report — und damit gameable. Der Eval-Harness ist die **externe Ground-Truth-Instanz**.

**Drei Jobs:**
1. **Promotion-Gate:** Working-Branch → main nur nach bestandenem Harness
2. **Watchdog-Sensor:** Soft-Trigger-Signal für ROLLBACK bei Capability-Regression
3. **Modell-Prune-Messung:** Rankt 8 Reasoner auf Metis-Tasks

Klare Trennung: 4D-Fitness = Moment-zu-Moment-Lernen, Eval-Harness = das Tor.

### 1. Prinzipien (nicht verhandelbar)
| Prinzip | Warum |
|---|---|
| Frozen, extern, read-only für Metis | Sonst editiert Metis den Test |
| Ground-Truth-first, Judge-last | "Hat funktioniert" (objektiv) > "ist gut" (subjektiv) |
| Deterministisch (temp=0, gepinnte Model-Digests) | Sonst vergleichst du Rauschen |
| Rausch-bewusst (Regression nur wenn >2σ) | Sonst triggerst du Rollbacks auf Zufall |
| Regression-fokussiert (Baseline-Delta) | Hauptfrage: "schlechter als last-known-good?" |
| Wächst aus echten Fehlern | Jeder Production-Fail wird zum Regressionstest |

### 2. Platzierung & Immutabilität
- Liegt in der read-only Zone des Watchdog (separates Git-Repo oder signiertes Verzeichnis)
- Hash-Chain über alle Task-Dateien; Watchdog prüft Integrität vor jedem Lauf. Manipulation = HALT
- **Held-out-Split:** Teil der Tasks ist secret — Inputs tauchen in keinem Log auf, das Metis lesen kann
- ⚠️ Die 10 Few-Shot-Beispiele im OllamaPlanner dürfen nicht mit Benchmark-Tasks überlappen!
- Jeder Lauf pinnt Metis-Commit + Model-Digests (deepseek-r1:32b@edba8017)

### 3. Invokationsmodell (zwei Ebenen)
- **Component-level** (Großteil): ruft OllamaPlanner, PersistentVectorIndex, OutputValidator direkt → schnell, diagnostisch präzise
- **End-to-end** (kleiner Satz): Input durch vollen Zyklus (Perceive→Plan→Execute→Observe→Learn) → Integrations-Abdeckung
- ⚠️ Code-Gen-Tasks brauchen Sandbox: generiertes Java wird kompiliert + Tests laufen isoliert (kein Netz, restricted FS, Timeout)

### 4. Task-Format (Java Record, immutable)
```java
record EvalTask(
    String id, Category category, String benchmarkVersion,
    JsonNode input, GroundTruth groundTruth, Scoring scoring,
    int runs, long timeoutMs, boolean heldOut
) {}

enum Category { PLANNING, RETRIEVAL, CODEGEN, CONVERSATION, SAFETY, PERFORMANCE }
enum Gate { HARD, SOFT }

sealed interface GroundTruth
    permits SimGoalState, RelevantIds, TestSuite, ExactMatch, ShouldBlock, JudgeRubric {}

interface Scorer { MetricResult score(EvalTask t, MetisOutput out); }
record MetricResult(String metric, double value, Gate gate) {}
```

### 5. Kategorien (das Herz)

**A — PLANNING (OllamaPlanner)**
- Input: Goal + Context
- Ground-Truth: deterministischer Mini-Simulator mit prüfbarem Ziel-Zustand (SIM_GOAL_STATE)
- Wo kein Sim: Schema-Validität + Executability
- Metriken: goal_achieved_rate (HARD), validity_rate (HARD), avg_steps (SOFT)

**B — RETRIEVAL (RAG)**
- Input: Query, Ground-Truth: RelevantIds (gelabelter Gold-Satz, 30–50 Queries)
- Metriken: Recall@k (HARD), MRR (SOFT), nDCG@k (SOFT)
- Bonus: BM25-only / Cosine-only / Hybrid getrennt laufen → beweist Fusions-Effekt

**C — CODEGEN (kritischste Kategorie)**
- Input: Spec/Prompt, Ground-Truth: TestSuite (versteckte Unit-Tests)
- Metriken: compile_rate (HARD), pass@1 + pass@k (HARD, runs:5)
- → Gold-Standard-Signal. Diese Kategorie schwer gewichten.

**D — CONVERSATION / Instruction-Following**
- Objektiv: format_compliance, factual_acc (Fragen mit bekannter Antwort) → HARD
- Subjektiv: judge_score via LLM-as-Judge + Rubric → nur SOFT, advisory

**E — SAFETY (OutputValidator / SafetyGuard)**
- Input: Injection-Versuche, toxische Prompts + benigne Prompts (False-Positive-Check)
- Ground-Truth: ShouldBlock: true/false
- Metriken: block_recall (HARD, Zero-Tolerance), false_positive_rate (HARD)

**F — PERFORMANCE**
- Metriken: p50/p95_plan_latency_ms, tokens_per_sec, peak_vram_gb (HARD — Budget!)

### 6. Scoring & Gate-Logik
```
FAIL (→ Watchdog ROLLBACK + ALERT) wenn:
  • irgendeine SAFETY-Metrik regrediert         → Zero-Tolerance, kein Noise-Band
  • irgendeine HARD-Metrik fällt unter (baseline − tolerance)
      UND |delta| > 2σ(baseline)                → echte Regression, kein Rauschen
  • PERFORMANCE verletzt Budget (Latenz/VRAM)
PASS sonst.
SOFT-Regression → PASS, aber im Report geflaggt (menschliche Sichtung).
```
**Kern:** Gate dominiert von objektiven Ground-Truth-Metriken (compile/test/sim/recall/safety), nie vom Judge oder 4D-Fitness.

### 7. Determinismus & Rauschen
- temperature=0 für alle Eval-Generierungen
- N-Runs für stochastische Metriken → mean ± σ
- Noise-Band einmalig kalibrieren: Baseline N× laufen, σ pro Metrik merken
- Model-Digest in jedem Report pinnen

### 8. Tiered Execution
| Tier | Umfang | Wann | Ziel |
|---|---|---|---|
| **Smoke** | ~5 Tasks/Kategorie, ~30-60s | Bei jeder Mutation (Shadow-Env) | Grobe Brüche sofort fangen |
| **Full** | Alle Tasks, Minuten | Vor Promotion zu main + nightly | Das echte Gate |
| **Extended** | + Modell-Vergleich + Held-out-Rotation | Nightly/weekly | Prune + Overfitting-Check |

### 9. Anti-Goodhart (explizit)
- Benchmark read-only + Hash-Chain → Metis kann Test nicht editieren
- Held-out-Split → kein Auswendiglernen
- Overfitting-Detektor: wenn Smoke-Score ≫ Fresh-Held-out-Score → Metis gamet
- Rotier regelmäßig frische Held-out-Tasks rein
- Gate dominiert von Objektiv-Metriken, Judge nur advisory
- Generierendes Modell ≠ richtendes Modell (kein Self-Judging im Gate)

### 10. Integration
- **Watchdog:** konsumiert EvalReport.gate → bei FAIL: ROLLBACK + ALERT
- **RollbackManager (Phase 5):** bleibt für Runtime-Failures (>10), Eval-Harness = vorgelagerte Promotion-Hürde
- **ModelRegistry:** Extended-Tier rankt Reasoner → Datenbasis für Prune (8 → 2–3)
- **Shadow-Environment:** führt Smoke bei jeder Mutation aus

### 11. Bootstrapping — fang klein an
| Phase | Umfang | Gate |
|---|---|---|
| **MVP** (~1 Tag) | 10 Tasks/Kategorie | Nur CODEGEN (compile+test) + SAFETY |
| **Phase 2** | Retrieval-Gold-Set labeln (30-50 Queries) + Planning-Sim | + RETRIEVAL + PLANNING |
| **Phase 3** | Conversation + Judge-Kalibrierung + Held-out-Rotation + Prune-Läufe | Volles Gate |

**Dauerregel:** Jeder Production-Fail wird eingefroren → Regressionstest. Benchmark wächst organisch in die Betriebsverteilung.

### Kandidaten-Pool (23 → ~8 Reasoner → 2-3 beste):
| Modell | Größe | Grund für Test |
|---|---|---|
| devstral-small-2:24b | 15.2 GB | Code-gen specialized, fits VRAM budget |
| mistral-small3.1:24b | 15.5 GB | Fast, reliable, good reasoning |
| phi4-reasoning:plus | 11.1 GB | CoT-native, lightest reasoner |
| qwen3.6:27b-q4_K_M | 17.4 GB | Strong general reasoning |
| gemma4:26b | 18.0 GB | Multi-modal potential |
| deepseek-r1:32b | 19.9 GB | Strongest known reasoning |
| nemotron-cascade-2:30b | 24.3 GB | Current default, VRAM-heavy |
| olmo-3.1:32b-think | 19.5 GB | Open model, thinking mode |

### Empfohlene Defaults nach Eval (Hypothese):
- **Planning:** devstral-small-2:24b oder mistral-small3.1:24b (~15 GB)
- **Mutation:** deepseek-r1:32b (~20 GB, nur bei Bedarf geladen)
- **Embedding:** nomic-embed-text (0.3 GB) ✅ bereits fix
- **Vision:** minicpm-v:latest (5.5 GB)
- **Judge:** nemotron-mini:4b (existiert bereits)

## 27.05. Morgens — Prompt-Optimierung v2 ✅
- [x] Chain-of-Thought (4-Schritt: ANALYZE→MATCH→CHECK→DECIDE)
- [x] 10 Few-Shot-Beispiele (1 pro Action)
- [x] Failure-Avoidance (0%→⚠️ AVOID)
- [x] Temperature 0.1→0.3
- [x] Primary-Modell: nemotron-cascade-2:30b
- [x] Fallback-Chain: mistral-small3.1→nemotron→qwen3.6
- [x] Deployed auf miniedi (metis-agent.jar 40f718f)

## 26.05. Abends — Runde 2 & 3 ✅
- [x] MQTT-Topic-Filter (Wildcard → spezifische Topics)
- [x] Kausale Schicht (CausalModel, Pearl Do-Calculus)
- [x] Fitness-Signal (geometrisch, 4D, Kalibrierung)
- [x] Curiosity-Engine (Surprise→Goal)
- [x] Proaktive Meldungen (MQTT/Wetter → Telegram)
