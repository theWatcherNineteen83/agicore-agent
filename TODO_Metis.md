# TODO_Metis.md — Entwicklungs-Roadmap

Letzter Stand: **2026-05-29 11:20** | Phase 7: **95%**

## Phasen-Status

| Phase | Name | Status |
|-------|------|--------|
| 1 | Core-Loop + Planner | ✅ 100% |
| 2 | Self-Evolution Grundlagen | ✅ 100% |
| 3 | Kamera-Integration + Events | ✅ 100% |
| 4 | Sprach-I/O (TTS/STT/Voice-Loop) | ✅ 100% |
| 5 | RAG + Prompt-Chaining | ✅ 100% |
| 6 | A/B-Testing + Data Flywheel | ✅ 100% |
| 7 | Watchdog + Eval + Sicherheit | 🟡 95% |

## Phase 7 (95%) — letzte offene Punkte

- [ ] MetisHttpServer /api/admin/prune endpoint im JAR (Maven-Build blockiert durch MaryTTS-Repo 409)
- [x] Watchdog systemd-Unit mit PRUNE + Audit-Log (SHA-256 Hash-Chain) ✅
- [x] Eval-Datensatz (50+ Tasks, 6 Kategorien: PLANNING, RETRIEVAL, CODEGEN, CONVERSATION, SAFETY, PERFORMANCE) ✅
- [x] EvalRunner (3-Tier: SMOKE/FULL/EXTENDED) ✅
- [x] SystemHealthProbe (VRAM/GPU/Ollama/dmesg alle 60s) ✅
- [x] PromptBank Shared Instance (EvolutionManager ↔ OllamaMutationService) ✅
- [x] ModelRegistry.pruneModel() ✅
- [x] Re-Embedding (llama3.2:3b → nomic-embed-text, 768d) ✅
- [x] GPU-Kernel-Parameter (amdgpu.vm_update_mode=3) ✅
- [x] Ollama VRAM-Limits (MAX_LOADED_MODELS=2, KEEP_ALIVE=10m) ✅
- [x] Balkonkamera (192.168.22.180:8080, Meizu m2 note) ✅

## Phase 8 — Produktionsreife

- [ ] Maven-Build fixen (MaryTTS-Repo-Alternative oder Dependency-Cache)
- [ ] MetisHttpServer /api/admin/prune deployed
- [ ] Eval-Automation (SMOKE nach Mutation, FULL vor Promotion)
- [ ] Metis-Startup-Smoke-Test (EvalRunner in AgentMain)
- [ ] Dokumentation finalisieren (AGI_EDI_ROADMAP.md)

## Betrieb (laufend)

- **miniedi** (192.168.22.204): Metis + Watchdog + Ollama live
- **Monitoring**: Watchdog (Heartbeat, ROLLBACK, PRUNE) + SystemHealthProbe (VRAM/GPU/dmesg)
- **Embedding**: nomic-embed-text (768d), Vektordatei 28 MB
- **Modelle**: qwen3.6 (Planning), deepseek-r1:32b (Mutation)
- **Kameras**: Tür (192.168.22.161:9081), Keller (RTSP), Balkon (192.168.22.180:8080)

## Chronik

### 29.05.2026 11:20 — Phase 7 Deployment
- Watchdog PRUNE action + AuditLog SHA-256 ✅
- Eval-Datensatz (EvalDatasetBuilder, EvalRunner, LiveMetisInvoker) ✅
- SystemHealthProbe in Metis integriert ✅
- PromptBank Shared Instance Fix ✅
- GPU-Kernel amdgpu.vm_update_mode=3 + Ollama Limits ✅
- Balkonkamera registriert ✅
- Maven-Build weiterhin blockiert (MaryTTS-Repo), nur ModelRegistry.pruneModel deployed
- Phase 7: 60% → 95%

### 29.05.2026 04:51 — Watchdog Eval-Integration
- EvalHarness writeReport() + Watchdog evalReportCheck()
- Phase 7 → 60%

### 29.05.2026 02:53 — Watchdog Deployment
- Systemd user unit auf miniedi, JAR 12.7 KB
- Phase 7 → 40%

### 29.05.2026 01:44 — Phase 6 Abschluss
- Data Flywheel ✅ (aabaaf1, 559 lines)
- Phase 6 → 100%

### 28.05.2026 23:50 — A/B Testing
- ABTestService (497 lines, Z-test, Auto-Promote)
- Phase 6 → 83%
