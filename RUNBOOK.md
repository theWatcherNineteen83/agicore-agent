# Metis AGI — Runbook

**Stand: 04.06.2026 | Host: miniedi (192.168.22.204) | User: prometheus | Host: miniedi (192.168.22.204) | User: prometheus**

---

## 📋 Architektur-Check (29.05.)

| Problem | Status | Details |
|---|---|---|
| **Git auf miniedi** | ✅ Funktioniert | SSH-Key (ed25519) generiert + GitHub Deploy Key mit Write-Access. `git push/pull` funktioniert. |
| **Swap voll** | ✅ Unkritisch | 3.5/8 GB (44%), 55 GB RAM verfügbar. Claude's Annahme war falsch — kein Speicherdruck. Historischer Swap von qwen3.6:latest + deepseek-r1:32b (44 GB Modellgewicht). |
| **Start-Mechanismen** | ✅ Konsolidiert | Nur systemd system-level (`metis.service`). Keepalive.sh entfernt, user-level deaktiviert. |
| **Reboot-Sicherheit** | ✅ | Linger=yes, metis.service enabled (multi-user.target), watchdog enabled (default.target + 30s Startup-Delay). |

---

## 🔧 Dienste

| Dienst | Typ | Port | Status | Restart |
|---|---|---|---|---|
| `metis.service` | systemd (system) | 11735 | enabled | on-failure, 10s |
| `metis-watchdog.service` | systemd (user) | 11736 | enabled | always, 10s |
| `ollama.service` | systemd (system) | 11434 | enabled | — |

---

## 🚨 Failure-Modi

### 1. Metis antwortet nicht (API down)

**Symptome:** `/api/status` timeout, Watchdog meldet Heartbeat-Verlust

**Ursachen (nach Häufigkeit):**
1. KnowledgeBootstrap blockiert (~2-3 Min beim Start normal)
2. Ollama-Inferenz hängt (Modell lädt/entlädt)
3. Prozess gecrasht (systemd restartet in 10s)

**Aktionen:**
```bash
# Prüfen ob Prozess läuft
ps aux | grep metis-agent

# Prüfen ob API antwortet (nach Bootstrap)
curl -s --max-time 5 http://localhost:11735/api/status

# Logs checken
journalctl -u metis.service --since "5 min ago" --no-pager
tail -50 /home/prometheus/metis/nohup.log

# Manuell neustarten
sudo systemctl restart metis.service
```

### 2. Ollama antwortet nicht

**Symptome:** Fallback-Storm in `/api/status`, hohe Latenz

**Aktionen:**
```bash
# Ollama Status
curl -s http://localhost:11434/api/ps
systemctl status ollama

# Neustart
sudo systemctl restart ollama
sleep 5
# Metis neu starten (verbindet sich neu)
sudo systemctl restart metis.service
```

### 3. GPU-Crash / ROCm-Fehler

**Symptome:** `dmesg` zeigt `amdgpu`-Errors, Ollama kann Modelle nicht laden

**Aktionen:**
```bash
# GPU-Status prüfen
rocminfo 2>&1 | head -20
dmesg | grep -i "amdgpu\|error" | tail -10

# VRAM leeren + Ollama neustarten
sudo systemctl restart ollama
sleep 5
sudo systemctl restart metis.service
```

### 4. Watchdog killt Metis (HALT)

**Symptome:** Metis-Prozess tot, Watchdog-Log zeigt `HALT`

**Häufige Ursachen:**
- CPU >90% für >60s (große Modelle im Swap)
- 6 Heartbeats verpasst (>30s ohne API-Antwort)
- qwen3.6:latest war der Hauptverursacher → ist jetzt gepruned

**Aktionen:**
```bash
# Watchdog-Log prüfen
journalctl --user -u metis-watchdog --since "10 min ago" --no-pager | grep -E "WARNUNG|SCHWERWIEGEND|HALT"

# Nach HALT: Metis startet automatisch via systemd (Restart=on-failure)
# Falls nicht:
sudo systemctl restart metis.service
```

### 5. Disk voll

**Symptome:** Schreibfehler in Logs

**Aktionen:**
```bash
df -h /home
du -sh /home/prometheus/metis/eval-reports/
du -sh /home/prometheus/.ollama/models/
# Alte eval-reports älter als 7 Tage löschen
find /home/prometheus/metis/eval-reports/ -name "*.json" -mtime +7 -delete
```

### 6. Zwei metis-agent Prozesse (Port-Konflikt)

**Symptome:** `ss -tlnp | grep 11735` zeigt mehrere Listener, API antwortet inkonsistent

**Ursache:** Alte Start-Mechanismen (keepalive, user-level systemd) gleichzeitig aktiv

**Aktionen:**
```bash
# ALLE metis-agent Prozesse killen
sudo pkill -f metis-agent
sleep 2
# Nur systemd startet neu (Restart=on-failure)
sudo systemctl restart metis.service
# Verifizieren: nur EIN Prozess
pgrep -c -f metis-agent.jar
```

---

## 🔄 Deployment-Prozess

```bash
# 1. Auf Kali bauen
cd ~/agicore-agent
git pull
mvn clean package -DskipTests

# 2. JARs auf miniedi kopieren
scp agicore-modules/target/metis-agent.jar miniedi:/home/prometheus/metis/
scp agicore-watchdog/target/agicore-watchdog-0.2.0-evolution.jar miniedi:/home/prometheus/metis/watchdog/agicore-watchdog.jar

# 3. Neustarten
ssh miniedi "sudo systemctl restart metis.service && systemctl --user restart metis-watchdog"

# 4. Verifizieren (nach ~3 Min Bootstrap)
curl -s http://192.168.22.204:11735/api/status | python3 -m json.tool | head -20
```

---

## 📊 Health-Check (Schnelltest)

```bash
# Alles-ok Check
curl -s http://192.168.22.204:11735/api/status | python3 -c "
import sys,json; d=json.load(sys.stdin)
ok = d.get('successRate',0) > 0.8 and d.get('modelFallbackUses',999) < 10
print('✅ HEALTHY' if ok else '❌ UNHEALTHY')
print(f\"Ticks: {d.get('totalTicks',0)} | Success: {d.get('successRate',0)} | Fallbacks: {d.get('modelFallbackUses',0)}\")
"

# Watchdog-Status
ssh miniedi "systemctl --user status metis-watchdog --no-pager | grep Active"

# VRAM
ssh miniedi "curl -s http://localhost:11434/api/ps | python3 -c \"
import sys,json
for m in json.load(sys.stdin).get('models',[]):
    print(f'{m[\\\"name\\\"]}: {m[\\\"size\\\"]/1e9:.1f} GB')
\""
```

---

## 📁 Wichtige Pfade

| Pfad | Inhalt |
|---|---|
| `/home/prometheus/metis/metis-agent.jar` | Metis fat JAR |
| `/home/prometheus/metis/watchdog/agicore-watchdog.jar` | Watchdog JAR |
| `/home/prometheus/metis/agent-state.json` | Persistenter Agent-Status |
| `/home/prometheus/metis/metis-knowledge.db` | SQLite KnowledgeStore |
| `/home/prometheus/metis/metis-vectors.bin` | 5.000+ Embedding-Vektoren |
| `/home/prometheus/metis/eval-reports/` | SMOKE-Eval Reports |
| `/home/prometheus/metis/nohup.log` | Alte keepalive-Logs |
| `/etc/systemd/system/metis.service` | Systemd Unit (system-level) |
| `/home/prometheus/.config/systemd/user/metis-watchdog.service` | Watchdog Unit (user-level) |

---

## 🔑 Zugangsdaten

| System | User | Auth |
|---|---|---|
| miniedi SSH | prometheus | SSH-Key von Kali (`ssh miniedi`) |
| miniedi sudo | prometheus | Passphrase (lokal in `tools/credentials/local-maschine.md`) |
| GitHub | theWatcherNineteen83 | Token auf Kali konfiguriert |
| GitHub (miniedi) | — | ⚠️ SSH-Key fehlt |
