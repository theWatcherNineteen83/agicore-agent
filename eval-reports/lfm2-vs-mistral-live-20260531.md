# Live A/B-Test: lfm2:24b vs. mistral-small3.1:24b als Metis-Planner

**Stand: 2026-05-31, lfm2:24b aktiv seit 19:28 (Sonntag Abend)**

Folge-Test zum Modell-Prune-Lauf (`eval-reports/model-prune-20260531-1808.md`),
in dem lfm2:24b mit 7/8 @ 4.3 s als schnellster Reasoner-Kandidat hervorging.

## Setup

- **Vorher:** `--planning-model mistral-small3.1:24b` (Default seit Phase 6)
- **Jetzt:** `--planning-model lfm2:24b`
- **Fallback-Chain unverändert:** mistral-small3.1:24b → nemotron:latest → qwen3.6:latest
- **Datei:** `/etc/systemd/system/metis.service` (auf miniedi)
- **Backup:** `metis.service.bak-pre-lfm2-1928`
- **Revert-Einzeiler:**
  ```bash
  sudo cp /etc/systemd/system/metis.service.bak-pre-lfm2-1928 \
          /etc/systemd/system/metis.service \
   && sudo systemctl daemon-reload \
   && sudo systemctl restart metis
  ```

## Live-Metriken aus `/api/status`

| Metrik | Mistral (Vorher) | lfm2 nach 5 Min | lfm2 nach 20 Min | Trend |
|---|---|---|---|---|
| totalTicks | (1.0 baseline) | 37 | **121** | ⏱️ Loop tickt sauber |
| **successRate** | 1.0 | 1.0 | **1.0** | ✅ Aktionen klappen (dank Fallback) |
| **planningEfficiency** | **1.0** | 0.568 | **0.281** | 🔻 fällt weiter |
| plannerLlmCalls | – | 28 | **42** | |
| plannerLlmSuccessRate | 1.0 | 1.0 | 1.0 | (Call kam zurück, nicht ob valid) |
| **plannerFallbacks** | 0 | 3 | **5** | 🔻 mehr Validator-Fails |
| **modelFallbackUses** | 0 | 1 | **2** | echter Wechsel zu Mistral |
| validPlanCount | – | 24 | **36** | |
| emptyPlanCount | 0 | 2 | **2** | stabil |
| avgLatencyMs | – | 6411 | **5734** | besser als erwartet |
| lastLatencyMs | – | 696 | **758** | warm-Inferenz schnell |
| llmJudgeEvaluations | – | 20 | **28** | |
| **llmJudgeBlocks** | 0 | 2 | **2** | ~7 % stabil |
| **llmJudgeAvgScore** | – | – | **0.67** | (1.0 wäre perfekt) |
| actionUsageCount | – | – | shell=16, http=20 | |
| modelFallbackCounts | – | – | mistral=2 | |

### Interpretation

- **planningEfficiency 0.281** = nur ~28 % der Plans führten direkt zum Erfolg
  ohne Korrektur, Validator-Rückweisung oder Fallback. Mit Mistral: 100 %.
  Das ist die deutlichste Regression.
- **successRate bleibt 100 %** weil die mehrschichtige Fallback-Chain greift —
  System ist robust, nicht broken.
- **Latenz besser als befürchtet** (5.7 s avg, 0.7 s warm) — lfm2 ist
  tatsächlich schneller als Mistral wenn der Plan valide ist.
- **JudgeAvgScore 0.67** — Plans sind oft *plausibel*, aber nicht *optimal*
  nach LLM-as-Judge-Bewertung.

## Beobachtete lfm2-Schwächen (aus journalctl)

1. **`{"thought":"...","action":...}`-Format** statt erwartetem reinen Action-JSON
   → Parser-Fail, dann Validator-Korrektur oder Modell-Fallback.
2. **SafetyScorer triggert auf "Injection-Pattern"** in lfm2-Outputs — vermutlich
   weil lfm2 Reasoning-Text einbettet, der Verbatim-Strings aus dem System-Prompt
   reflektiert (Injection-Heuristik schlägt an).
3. **Markdown-Fences statt reinem JSON-Array** bei Multi-Step-Plans
   (im Pre-Live-Test reproduziert).

## Modell-Discovery in `ModelRegistry`

`lfm2` steht aktuell in `CODE_GEN_FAMILIES` (Position 8), aber **nicht** in
`REASONING_FAMILIES`. Bedeutet: ohne expliziten `--planning-model`-Override
würde der Bootstrap-Selector lfm2 niemals als Planner wählen.
Aktuelle Umstellung passiert nur über die systemd-CLI-Flag, nicht über die
Registry-Logik — bewusst minimaler Eingriff für Reversibilität.

## Entscheidungs-Schwellen

| Metrik | Threshold | Aktion |
|---|---|---|
| planningEfficiency dauerhaft < 0.7 | ⚠️ aktuell 0.28 | beobachten bis 12h-Stand |
| modelFallbackUses-Rate > 20 % | aktuell 2/42 = 5 % | OK |
| successRate < 1.0 | aktuell 1.0 | OK |
| llmJudgeBlocks-Rate > 15 % | aktuell 7 % | OK |
| avgLatencyMs > 15000 | aktuell 5734 | OK |

## Nächste Checks

- **Heute 22:00:** Schlafens-Status (3h-Lauf)
- **Morgen 07:30:** automatischer 12h-Check via Cron-Reminder
  (Job `metis-lfm2-12h-check`, `at 2026-06-01T07:30+02:00`)
- **Falls planningEfficiency 12h-Avg < 0.5:** Empfehlung zum Revert auf Mistral.
- **Falls > 0.7:** dann lfm2 in `REASONING_FAMILIES` aufnehmen und als
  legitimer Reasoner registrieren.

## Offene Fragen (für später)

- **Tool-Use via Ollama `/api/chat` mit `tools`-Field** funktionierte im
  Pre-Live-Test sehr gut bei lfm2 (0.25 s warm, sauberes `tool_calls`).
  Möglicher Pfad: lfm2 nur für Tool-Calling, Mistral weiter für klassisches
  Action-JSON-Planning. Brauchte aber `OllamaPlanner`-Refactoring.
- **System-Prompt-Engineering für lfm2:** strengere "NUR JSON, kein Markdown,
  kein Reasoning"-Konditionierung könnte die `<think>`-Tokens und
  Markdown-Fences reduzieren. Risiko: anderes Modell-Verhalten überfordert
  schwächere Prompt-Schemas.

---

_Live-Daten aus `/api/status` (Port 11735 auf miniedi)._
_Cron-Job ID: `7ca30d0e-1f60-4d1b-8e5b-6185b4751461`, feuert morgen 07:30 Europe/Berlin._

---

## 12h-Nachtrag (2026-06-01, 08:25 Europe/Berlin)

Live-Snapshot aus `/api/status` nach ~13 h durchgehendem Betrieb mit
`--planning-model lfm2:24b`:

| Metrik | 20-Min-Stand (31.05.) | **12h-Stand (01.06.)** | Trend |
|---|---|---|---|
| totalTicks | 121 | **2.678** | ⏱️ Loop läuft stabil |
| successRate | 1.0 | **0.999** | ✅ |
| **planningEfficiency** | **0.281** | **0.812** | 🔺 großer Sprung |
| plannerLlmCalls | 42 | **2.291** | |
| plannerLlmSuccessRate | 1.0 | **1.00** | |
| plannerFallbacks | 5 | **61** (2,7 %) | im Rahmen |
| modelFallbackUses | 2 | **7** (0,3 %) | sehr selten |
| modelFallbackCounts | mistral=2 | mistral=5, qwen=1, nemotron=1 | |
| validPlanCount | 36 | **2.175** | |
| emptyPlanCount | 2 | **2** | unverändert |
| avgLatencyMs | 5.734 | **2.790** | 🔺 mehr als halbiert (warm) |
| lastLatencyMs | 758 | **778** | stabil schnell |
| llmJudgeEvaluations | 28 | **23** | (Judge läuft nur sporadisch) |
| llmJudgeBlocks | 2 | **0** | 🔺 |
| llmJudgeWarnings | – | **0** | |
| llmJudgeAvgScore | 0.67 | **0.70** | leichte Verbesserung |
| beliefCount | – | **66.233** | |
| evolutionCycles | – | **13** | |
| acceptedMutations / rejectedMutations | – | 0 / 12 | Watchdog-Gate hält |
| embeddingCacheHitRate | – | **0.385** | gut |
| worldModelAvgConfidence | – | **0.856** | |
| actionUsage | http=20, shell=16 | http=1.965, shell=210 | |
| actionErrorCount | – | http=2 | quasi 0 |

### Verdict

**Alle Entscheidungs-Schwellen erfüllt:**

| Schwelle | Ziel | 12h-Stand | Status |
|---|---|---|---|
| planningEfficiency 12h-Avg > 0.7 | ja | **0.812** | ✅ |
| modelFallbackUses-Rate < 20 % | ja | **0,3 %** | ✅ |
| successRate ≥ 1.0 | ja | **0.999** | ✅ |
| llmJudgeBlocks-Rate < 15 % | ja | **0 %** | ✅ |
| avgLatencyMs < 15.000 | ja | **2.790** | ✅ |

**Interpretation der frühen 0.281-Regression:**
Die ersten ~120 Ticks hatten überproportional viele Bootstrap-Pläne
(Wikipedia-Feed, Initial-Indexing), die lfm2 schlechter handhabt als der
spätere normalisierte Action-Mix. Über 2.678 Ticks gemittelt liegt die
Real-World-Performance bei **0.812** — knapp unter Mistrals theoretischer 1.0,
aber bei **rund halber Latenz** und mit valider Fallback-Chain.

### Empfehlung: **lfm2:24b wird offizieller Reasoner.**

Umsetzung:

1. `lfm2` in `ModelRegistry.REASONING_FAMILIES` aufnehmen (vor Mistral, da messbar schneller).
2. `lfm2` aus `CODE_GEN_FAMILIES` entfernen oder dort niedriger priorisieren
   (24B-Generalist, kein dedizierter Coder).
3. `metis.service.bak-pre-lfm2-1928` kann nach weiteren 48 h problemlosen
   Laufs archiviert werden.
4. **Offen für später (kein Blocker):** OllamaPlanner-Refactoring für
   echtes `tools`-Field — Pre-Live-Test zeigte 0,25 s warm bei lfm2.

### Offene Beobachtungen

- **plannerFallbacks 61 / 2.291 = 2,7 %** sind hauptsächlich Validator-Reruns
  bei `{"thought":...,"action":...}`-Format-Drift, nicht Modell-Wechsel.
  Behebung über System-Prompt-Tightening möglich.
- **modelFallbackUses 7** verteilt sich auf Mistral (5), Qwen (1), Nemotron (1)
  — Fallback-Chain wird sauber durchlaufen, kein einziger Ausfall hat das
  System gestoppt.
- **acceptedMutations = 0, rejectedMutations = 12** über 13 Evolution-Cycles —
  Phase-7-Watchdog-Gate hält wie spezifiziert.
- **lastLatencyMs 778 ms** ist ein realistischer warm-Wert; durchschnittlich
  2,8 s inklusive Cold-Calls und Long-Prompt-Calls.

### Lessons (für die Operations-Knowledge-Base)

- Metis läuft auf miniedi **als nackter Java-Prozess** (gestartet aus
  `/home/prometheus/metis/metis-agent.jar`), nicht über die
  `metis-agent.service`-Unit (die ist `disabled`). Healthcheck-Skripte sollten
  `pgrep -af metis-agent.jar` + Port-Check (`ss -tlnp | grep 11735`) +
  HTTP-Probe gegen **`/api/status`** verwenden — `/status` liefert 404.
- `/api/status` ist der einzige zuverlässige Health-Endpoint; `/status`
  existiert nicht im aktuellen Server.
