# Metis Runbook (Repo-Stand)

> **Stand 10.11.2026.** Das maßgebliche Betriebs-Runbook (inkl. Zugangsdaten) liegt im
> Prometheus-Workspace. Repo-Fakten: README.md, RUNBOOK.md, FEATURES.md, AGI_EDI_ROADMAP.md.

## Architektur (10.11.2026)

- **Host:** miniedi (192.168.22.204), Ubuntu 24.04, Ryzen 7 5700G, 62 GB RAM
- **GPUs:** RX 9070 XT 16 GB (GPU0) + Radeon AI PRO R9700 32 GB (GPU1) — beide gfx1201, ROCm 7.2.4
- **Inferenz:** eine Ollama-Instanz auf `:11434`, `HIP_VISIBLE_DEVICES=0,1`,
  `OLLAMA_SCHED_SPREAD=1`, `OLLAMA_CONTEXT_LENGTH=32768`, `OLLAMA_MAX_LOADED_MODELS=3`
- **Kein llama.cpp/Shim mehr:** `:8086`, `:8087`, `:11445` sind am 10.11.2026 entfernt
  (Units + Binär-Symlink unter `/home/prometheus/metis-backup-llamacpp-20261110/`)

## Modell-Rollen

| Rolle | Modell | Endpoint |
|---|---|---|
| Planung | `qwen3.8:27b` | `/v1/chat/completions` |
| Mutation | `nemotron-3.5-bolt:e4b` (think=true) | `/api/generate` |
| Embedding | `nomic-embed-text` (768d, aus GGUF importiert) | `/api/embeddings` |
| Judge | `qwen3.8:27b` | `/api/generate` |
| Fallback-Kette | `bolt` → `gemma4:26b` → `granite4.1:30b` | im Code (`OllamaPlanner`) |

## Dienste

| Dienst | Port | Status |
|---|---|---|
| `metis.service` | 11735 | enabled |
| `metis-watchdog.service` | 11736 | enabled |
| `ollama.service` | 11434 | enabled (beide GPUs) |
| `quantum-bridge.service` | 11740 | enabled |
| `ollama-planner` / `ollama-mutation` / `ollama-embedding` | — | disabled |
| `llama-server` / `llama-embed` / `metis-llama-shim` | — | entfernt |

## Healthcheck (30 s)

```bash
curl -s --max-time 5 http://miniedi:11735/api/status | python3 -m json.tool
# Erwartung: plannerLlmSuccessRate 1.0, modelFallbackUses 0, totalTicks wächst
rocm-smi --showmeminfo vram
curl -s http://127.0.0.1:11434/api/ps
```

## Build

```bash
cd /home/prometheus/metis-build
mvn -B clean package -Dmaven.test.skip=true
# -> agicore-modules/target/metis-agent.jar (~113 MB)
cp -a /home/prometheus/metis/metis-agent.jar /home/prometheus/metis/metis-agent.jar.bak-$(date +%Y%m%d)
install -o prometheus -g prometheus -m 755 agicore-modules/target/metis-agent.jar /home/prometheus/metis/metis-agent.jar
systemctl restart metis.service
```

**Build-Fixes 10.11.2026:** `jakarta.mail` als Dependency ergänzt (`org.eclipse.angus:jakarta.mail:2.0.3`),
`Flags.addFlag/removeFlag` → `add/remove`, toter `ImapFlagSync` (referenzierte nicht existierendes
`ImapMailClient`) entfernt (Backup unter `/home/prometheus/metis-backup-mailsrc-20261110/`).

## Eval

```bash
curl -s -X POST http://127.0.0.1:11735/api/admin/trigger-eval \
  -H 'Content-Type: application/json' -d '{"tier":"SMOKE"}'
# -> 202 accepted, Lauf im Hintergrund; Report unter /home/prometheus/metis/eval-reports/
```

**Letzter Stand (SMOKE, 10.11.): Gate PASS** — PLANNING 0.83, RETRIEVAL 1.00,
CODEGEN.compile_rate 0.50, CONVERSATION 1.00, SAFETY 1.00, PERFORMANCE 1.00,
CAUSAL 1.00, RELATIONSHIP 1.00/1.00, ETHICS 1.00.

## Bekannte Punkte

- `CODEGEN.compile_rate 0.50`: die Hälfte der Codegen-Tasks kompiliert (Modellantworten
  halten die geforderte Klassensignatur nicht immer ein).
- Tick-`successRate` ~0.3: Action-Fehler (`feature-gen`-Timeouts, `jlama`, Webcam/MQTT),
  nicht der Planer.
- Clients mit eigenem `num_ctx` (z. B. 262144) überschreiben den Ollama-Default und
  erzwingen CPU-Spill.
