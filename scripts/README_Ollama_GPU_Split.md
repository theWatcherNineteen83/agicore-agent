# Ollama GPU Split - Dedizierte Instanzen für Metis

**Zweck:** GPU-Load-Balancing für Metis durch dedizierte Ollama-Instanzen pro Task

**Problem:** 
- GPU1 (R9700) lief mit **qwen3.6:35b + granite-mini-agent** → **100% Auslastung** → **Action-Dominance-Warnungen**
- GPU0 (7900 XTX) wurde nicht optimal genutzt

**Lösung:** 
- **GPU1 (R9700, Port 11434):** Nur `qwen3.6:35b-a3b-q4_K_M` für **Planner**
- **GPU0 (7900 XTX, Port 11436):** Nur `granite-code:3b` für **Mutation**
- **CPU (Port 11438):** Nur `nomic-embed-text` für **Embeddings**

---

## 📁 Dateien

| Datei | Zweck |
|-------|-------|
| `setup_ollama_dedicated_gpus.sh` | Hauptskript - richtet alles ein |
| `verify_ollama_setup.sh` | Überprüft die korrekte Einrichtung |
| `README_Ollama_GPU_Split.md` | Diese Dokumentation |

---

## 🚀 Schnellstart

### 1. Einrichtung durchführen
```bash
# Auf lokaler Maschine (kali) ausführen
cd /home/admini/agicore-agent/scripts
chmod +x setup_ollama_dedicated_gpus.sh
./setup_ollama_dedicated_gpus.sh
```

### 2. Einrichtung überprüfen
```bash
./verify_ollama_setup.sh
```

---

## 🔧 Manuelle Schritte

### Vor der Ausführung
- **SSH-Zugriff:** Stelle sicher, dass du mit dem SSH-Key auf miniedi zugreifen kannst:
  ```bash
  ssh -i /home/admini/keys/prometheus_ed25519 prometheus@192.168.22.204
  ```
- **Ollama installiert:** Ollama muss auf miniedi installiert sein
- **ROCm treiber:** ROCm muss korrekt installiert sein

### Nach der Ausführung
1. **Metis-Konfiguration anpassen** (wichtig!):
   ```bash
   # Auf miniedi:
   sudo nano /etc/systemd/system/metis.service
   ```
   Folgende Umgebungsvariablen anpassen:
   ```ini
   Environment="METIS_PLANNING_URL=http://localhost:11434"
   Environment="METIS_MUTATION_URL=http://localhost:11436"
   Environment="METIS_EMBEDDING_URL=http://localhost:11438"
   ```

2. **Metis neu starten:**
   ```bash
   ssh prometheus@192.168.22.204
   sudo systemctl daemon-reload
   sudo systemctl restart metis.service
   ```

---

## 📊 Systemd-Service-Konfigurationen

### ollama-planner.service (GPU1, Port 11434)
```ini
[Unit]
Description=Ollama Planner Instance (qwen3.6:35b-a3b-q4_K_M) - GPU1 (R9700)
After=network.target

[Service]
Type=simple
ExecStart=/usr/bin/ollama serve --port 11434 --model qwen3.6:35b-a3b-q4_K_M
Environment="HIP_VISIBLE_DEVICES=1"
Restart=always
RestartSec=5
User=prometheus
Group=prometheus
WorkingDirectory=/home/prometheus
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

### ollama-mutation.service (GPU0, Port 11436)
```ini
[Unit]
Description=Ollama Mutation Instance (granite-code:3b) - GPU0 (7900 XTX)
After=network.target

[Service]
Type=simple
ExecStart=/usr/bin/ollama serve --port 11436 --model granite-code:3b
Environment="HIP_VISIBLE_DEVICES=0"
Restart=always
RestartSec=5
User=prometheus
Group=prometheus
WorkingDirectory=/home/prometheus
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

### ollama-embedding.service (CPU, Port 11438)
```ini
[Unit]
Description=Ollama Embedding Instance (nomic-embed-text) - CPU only
After=network.target

[Service]
Type=simple
ExecStart=/usr/bin/ollama serve --port 11438 --model nomic-embed-text
Environment="OLLAMA_NUM_GPU=0"
Restart=always
RestartSec=5
User=prometheus
Group=prometheus
WorkingDirectory=/home/prometheus
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

---

## 🔄 Zurücksetzen (falls nötig)

Falls etwas schiefgeht, können die alten Services wiederhergestellt werden:

```bash
# Auf miniedi:
sudo systemctl stop ollama-planner.service ollama-mutation.service ollama-embedding.service
sudo systemctl disable ollama-planner.service ollama-mutation.service ollama-embedding.service
sudo rm /etc/systemd/system/ollama-planner.service /etc/systemd/system/ollama-mutation.service /etc/systemd/system/ollama-embedding.service

# Alte Services wieder aktivieren (falls sie existiert haben)
sudo systemctl enable ollama-gpu0.service ollama-gpu1.service ollama-cpu.service
sudo systemctl start ollama-gpu0.service ollama-gpu1.service ollama-cpu.service
```

---

## 📈 Erwartete Ergebnisse

### Vorher
- **GPU1 (R9700):** 100% Auslastung (qwen3.6:35b + granite-mini-agent)
- **GPU0 (7900 XTX):** Geringe Auslastung
- **Problem:** Action-Dominance-Warnungen, Fallback-Kette feuert ständig

### Nachher
- **GPU1 (R9700):** ~80% Auslastung (nur qwen3.6:35b)
- **GPU0 (7900 XTX):** ~40% Auslastung (nur granite-code:3b)
- **CPU:** ~10% Auslastung (nur nomic-embed-text)
- **Ergebnis:** Keine Action-Dominance-Warnungen mehr!

---

## 🛡️ Sicherheitshinweise

1. **Modell-Sicherheit:**
   - `granite-code:3b` ist spezialisiert auf Code-Generation
   - `qwen3.6:35b` ist für Planung und komplexe Aufgaben
   - `nomic-embed-text` ist nur für Embeddings

2. **GPU-Isolation:**
   - Jede Instanz läuft mit `HIP_VISIBLE_DEVICES` auf einer dedizierten GPU
   - Keine Überschneidung der GPU-Nutzung

3. **Ports:**
   - 11434: Planner (nur interne Nutzung durch Metis)
   - 11436: Mutation (nur interne Nutzung durch Metis)
   - 11438: Embeddings (nur interne Nutzung durch Metis)

---

## 📚 Modelle

| Modell | Größe | VRAM | Zweck |
|--------|-------|------|-------|
| qwen3.6:35b-a3b-q4_K_M | 35B | ~26GB | Planung (Metis Core) |
| granite-code:3b | 3B | ~10GB | Code-Generation (Mutation) |
| nomic-embed-text | - | 0GB | Embeddings (CPU) |

---

## 🔗 Verweise

- [Ollama Dokumentation](https://github.com/ollama/ollama)
- [Metis AGI Repository](https://github.com/theWatcherNineteen83/agicore-agent)
- [TOOLS.md (lokal)](~/TOOLS.md)
- [Neue_Roadmap.md](Neue_Roadmap.md)

---

## 📝 Changelog

| Version | Datum | Autor | Änderungen |
|---------|-------|-------|-----------|
| 1.0 | 10.07.2026 | Prometheus | Initialer Entwurf |
