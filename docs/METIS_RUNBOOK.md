# Metis Runbook — Fehlersuche & Wartung

> **⚠️ Vor jeder Metis-Arbeit: Diese Datei lesen!**
> Letztes Update: 17.07.2026 23:00 (Session: GPU-Fix, Evolution-Debugging)
> OpenClaw: Bei `Metis`, `metis`, `miniedi`, `llama-server`, `Evolution` im Prompt → diese Datei vor allen Aktionen laden.

---

## 🔍 Erste Schritte: Immer zuerst prüfen

### 1. Healthcheck (30 Sekunden)

```bash
# API-Status
curl -s --max-time 5 http://miniedi:11735/api/status | python3 -m json.tool

# Kritische Metriken:
# - plannerLlmSuccessRate: <30% → Planner-Problem
# - lastLatencyMs: >5000ms → GPU-Fallback?
# - evolutionCycles: 0 trotz --evolution Flag → Evolution steht
# - plannerLlmCalls: wächst es? Wenn ja, Planner arbeitet
```

### 2. GPU-Check (DER häufigste Fehler!)

```bash
ssh prometheus@miniedi 'rocm-smi --showuse --showmeminfo vram'

# GPU0 (7900 XTX, 24 GB): Soll ~23-25 GB belegt (llama-server)
# GPU1 (R9700, 32 GB): Variabel (qwen35b bei Bedarf + granite-code)

# 🔥 KRITISCH: Prüfe ob llama-server wirklich auf GPU läuft:
ssh prometheus@miniedi 'ls /sys/class/kfd/kfd/proc/'
# → Die PID von llama-server MUSS in dieser Liste sein.
# → Wenn NICHT: Modell läuft auf CPU → 100% Fail!
```

### 3. Service-Check

```bash
ssh prometheus@miniedi 'systemctl is-active metis llama-server ollama-planner ollama-mutation ollama-embedding'
# Alle müssen "active" sein. DEAD: ollama, ollama-gpu0, ollama-gpu1 (absichtlich disabled)
```

---

## 🧨 Wiederkehrende Probleme & Fixes

### Problem 1: Planner spinnt / Timeouts / niedrige Erfolgsrate

**Symptome:**
- `plannerLlmSuccessRate` < 20%
- `lastLatencyMs` > 10.000ms
- `emptyPlanCount` explodiert
- `modelFallbackUses` steigt schnell

**Root-Cause-Reihenfolge (durchtesten!):**

1. **llama-server auf CPU?** ← Häufigster Fehler!
   ```bash
   ssh prometheus@miniedi 'ls /sys/class/kfd/kfd/proc/ | xargs -I{} cat /proc/{}/cmdline 2>/dev/null | grep llama-server'
   ```
   Wenn kein Treffer → CPU-Fallback!
   
   **Fix:**
   ```bash
   # Prüfen: /etc/systemd/system/llama-server.service
   # Muss enthalten: Environment=HIP_VISIBLE_DEVICES=0
   # NICHT =1 !!! (GPU0 braucht 0, GPU1 braucht 1)
   ssh prometheus@miniedi 'grep HIP /etc/systemd/system/llama-server.service'
   # Falsch → ändern + restart:
   ssh prometheus@miniedi 'echo "PW" | sudo -S sed -i "s/HIP_VISIBLE_DEVICES=1/HIP_VISIBLE_DEVICES=0/" /etc/systemd/system/llama-server.service && echo "PW" | sudo -S systemctl daemon-reload && echo "PW" | sudo -S systemctl restart llama-server'
   ```

2. **User-Unit crash-loopt?**
   ```bash
   ssh prometheus@miniedi 'systemctl --user is-active llama-server 2>/dev/null'
   ```
   Wenn "activating"/"failed": User-Unit disabled lassen! (Group= funktioniert nicht in User-Units)

3. **Ollama-Instanzen kaputt?**
   ```bash
   ssh prometheus@miniedi 'systemctl list-units --all | grep ollama'
   # NUR diese dürfen active sein: ollama-planner, ollama-mutation, ollama-embedding
   # ollama.service, ollama-gpu0.service, ollama-gpu1.service → disabled!
   ```

### Problem 2: Build & Deploy

**JAR-VERWECHSLUNG ← Hat uns schon 2× Zeit gekostet!**

```bash
# ❌ FALSCH: agicore-modules/target/agicore-modules-0.2.0-evolution.jar (664 KB, ohne Dependencies!)
# ✅ RICHTIG: agicore-modules/target/metis-agent.jar (110 MB, shaded/fat JAR!)

# Deploy-Checklist:
# 1. Source syncen:
rsync -avz --exclude='target/' --exclude='.git/' --exclude='*.jar' \
  /home/admini/agicore-agent/ prometheus@miniedi:/home/prometheus/metis-build/

# 2. Build:
ssh prometheus@miniedi 'cd /home/prometheus/metis-build && mvn -B clean package -Dmaven.test.skip=true'

# 3. RICHTIGES JAR deployen:
ssh prometheus@miniedi 'echo "PW" | sudo -S cp /home/prometheus/metis-build/agicore-modules/target/metis-agent.jar /home/prometheus/metis/metis-agent.jar'

# 4. Restart:
ssh prometheus@miniedi 'echo "PW" | sudo -S systemctl restart metis'

# 5. Verifizieren:
sleep 15 && curl -s http://miniedi:11735/api/status | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['version'])"
```

### Problem 3: Evolution hängt / 0 Cycles

**Checkliste:**

1. **Pausiert?** `curl -s http://miniedi:11735/api/evolution/status`
2. **Mutation-Modell geladen?**
   ```bash
   curl -s http://miniedi:11434/api/ps | python3 -c "import json,sys; [print(m['name']) for m in json.load(sys.stdin).get('models',[])]"
   ```
   Sollte `granite-code:3b` enthalten.
3. **Source-Symlinks ok?**
   ```bash
   ssh prometheus@miniedi 'find /home/prometheus/metis/agicore-modules/src -name "OllamaPlanner.java"'
   ```
   Wenn leer: Symlink zeigt ins Leere!
   ```bash
   ssh prometheus@miniedi 'ln -sfn /home/prometheus/metis-build/agicore-modules /home/prometheus/metis/agicore-modules'
   ssh prometheus@miniedi 'ln -sfn /home/prometheus/metis-build/agicore-kernel /home/prometheus/metis/agicore-kernel'
   ```
4. **AutoTuner entlädt Mutation-Modell?**
   ```bash
   ssh prometheus@miniedi 'journalctl -u metis --no-pager --since "5 min ago" | grep "unloaded granite"'
   ```
   Wenn ja: AutoTuner-Fix prüfen (ResourceAutoTuner.java muss mutationModel ausschließen).
5. **Manuell triggern:** `curl -s -X POST http://miniedi:11735/api/evolution/trigger`

### Problem 4: Mutation-URL falsch

**Metis muss die richtige Ollama-Instanz für Mutation nutzen!**

```bash
ssh prometheus@miniedi 'ps aux | grep metis-agent | grep -o "mutation-url [^ ]*"'
# Sollte sein: --mutation-url http://127.0.0.1:11434
# (Der Planner-Instanz — AutoTuner schützt granite-code jetzt vor Eviction)
```

Falls falsch (`:11436`):
```bash
ssh prometheus@miniedi 'echo "PW" | sudo -S sed -i "s|--mutation-url http://127.0.0.1:11436|--mutation-url http://127.0.0.1:11434|" /etc/systemd/system/metis.service && echo "PW" | sudo -S systemctl daemon-reload && echo "PW" | sudo -S systemctl restart metis'
```

---

## 📋 Periodische Checks (bei jedem Metis-Statuscheck)

| Check | Befehl | Gut | Schlecht |
|-------|--------|-----|----------|
| API erreichbar | `curl -s --max-time 5 http://miniedi:11735/api/status` | JSON-Response | Timeout/Error |
| Planner-Erfolg | `.plannerLlmSuccessRate` | >0.30 | <0.20 |
| Planner-Latenz | `.lastLatencyMs` | <5000 | >30000 |
| GPU-Proc | `ls /sys/class/kfd/kfd/proc/` | llama-server-PID | leer |
| VRAM GPU0 | `rocm-smi` GPU0 Used | <25 GB | >24 GB (overcommit!) |
| RAM | `free -h` available | >2 GB | <1 GB |
| Swap | `free -h` swap used | <2 GB | >6 GB |
| GitHub sync | `git status` im Repo | clean | uncommitted changes |
| Evolution | `/api/evolution/status` cycles | >0 | 0 trotz --evolution |
| Kanban | `/api/board` inProgress | >0 | 0 |
| Goals aktiv | `/api/hierarchy` ACTIVE | ≥2 (LIFETIME + STRATEGIC) | nur LIFETIME |

---

## 🏗️ Architektur-Referenz (Stand 17.07.2026)

```
miniedi (192.168.22.204) — Ryzen 7 5700G, 62 GB RAM, 2× GPU

GPU 0: 7900 XTX (24 GB VRAM)
  ├─ llama-server :8086 (Qwen3.6-27B-Q4_K_XL, HIP_VISIBLE_DEVICES=0)
  └─ ollama-mutation :11436 (granite-code:3b, HIP=0)

GPU 1: R9700 (32 GB VRAM)
  ├─ ollama-planner :11434 (qwen3.6:35b, KEEP_ALIVE=2m, HIP=1)
  └─ gemma4-api :11439 (System-Unit, gemma4:12b)

CPU:
  └─ ollama-embedding :11438 (nomic-embed-text)

System-Dienste:
  ├─ metis.service (Java 25, Xmx24g, Port 11735)
  └─ metis-watchdog.service (Port 11736, Eval + Rollback)

DISABLED (nicht wieder aktivieren!):
  ❌ ollama.service, ollama-gpu0.service, ollama-gpu1.service
  ❌ llama-server.service (User-Unit, Group= broken)
  ❌ ollama-router :11437
```

---

## 📦 Dateien & Pfade

| Was | Pfad |
|-----|------|
| Metis Source (kali) | `/home/admini/agicore-agent/` |
| Metis Build (miniedi) | `/home/prometheus/metis-build/` |
| Metis JAR deployed | `/home/prometheus/metis/metis-agent.jar` |
| Metis WorkingDir | `/home/prometheus/metis/` |
| Metis State | `/home/prometheus/metis/agent-state.json` |
| Watchdog JAR | `/home/prometheus/metis/watchdog/agicore-watchdog.jar` |
| System-Unit Metis | `/etc/systemd/system/metis.service` |
| System-Unit llama-server | `/etc/systemd/system/llama-server.service` |
| GitHub Remote | `https://github.com/theWatcherNineteen83/agicore-agent.git` |
| GPU-Proc-Check | `/sys/class/kfd/kfd/proc/` |
| Passwort (sudo) | `tools/credentials/local-maschine.md` — "Mut Motivation Respekt Willenskraft Qualitaet" |
