#!/bin/bash
# Fix für Ollama v0.30.10 - verwendet OLLAMA_HOST statt --port
SUDO_PASS="Mut Motivation Respekt Willenskraft Qualitaet"

echo "=== Schritt 1: Alle Ollama-Prozesse beenden ==="
echo "$SUDO_PASS" | sudo -S pkill -9 -f "ollama serve" 2>/dev/null || true
sleep 3

# Pruefen
if pgrep -f "ollama serve" > /dev/null; then
    echo "FEHLER: Ollama-Prozesse laufen noch!"
    exit 1
fi
echo "OK: Alle Ollama-Prozesse beendet"

# Ports checken
echo ""
echo "=== Schritt 2: Ports checken ==="
ss -tlnp | grep 1143 || echo "Alle 1143-Ports sind frei"

echo ""
echo "=== Schritt 3: systemd-Dateien korrigieren (v0.30.10 verwendet OLLAMA_HOST) ==="

# ollama-planner.service
cat > /tmp/ollama-planner.service << 'EOF'
[Unit]
Description=Ollama Planner Instance (qwen3.6:35b-a3b-q4_K_M) - GPU1 (R9700)
After=network.target

[Service]
Type=simple
ExecStart=/usr/local/bin/ollama serve
Environment="OLLAMA_HOST=0.0.0.0:11434"
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
EOF

# ollama-mutation.service
cat > /tmp/ollama-mutation.service << 'EOF'
[Unit]
Description=Ollama Mutation Instance (granite-code:3b) - GPU0 (7900 XTX)
After=network.target

[Service]
Type=simple
ExecStart=/usr/local/bin/ollama serve
Environment="OLLAMA_HOST=0.0.0.0:11436"
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
EOF

# ollama-embedding.service
cat > /tmp/ollama-embedding.service << 'EOF'
[Unit]
Description=Ollama Embedding Instance (nomic-embed-text) - CPU only
After=network.target

[Service]
Type=simple
ExecStart=/usr/local/bin/ollama serve
Environment="OLLAMA_HOST=0.0.0.0:11438"
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
EOF

# Dateien kopieren
echo "$SUDO_PASS" | sudo -S cp /tmp/ollama-planner.service /etc/systemd/system/ollama-planner.service
echo "$SUDO_PASS" | sudo -S cp /tmp/ollama-mutation.service /etc/systemd/system/ollama-mutation.service
echo "$SUDO_PASS" | sudo -S cp /tmp/ollama-embedding.service /etc/systemd/system/ollama-embedding.service

# Alte Dateien loeschen
rm -f /tmp/ollama-*.service

# systemd neu laden
echo "$SUDO_PASS" | sudo -S systemctl daemon-reload

echo "OK: systemd-Dateien korrigiert"

echo ""
echo "=== Schritt 4: Services starten ==="
echo "$SUDO_PASS" | sudo -S systemctl restart ollama-planner.service
echo "$SUDO_PASS" | sudo -S systemctl restart ollama-mutation.service
echo "$SUDO_PASS" | sudo -S systemctl restart ollama-embedding.service

# Warten
sleep 15

echo ""
echo "=== Schritt 5: Status pruefen ==="
echo "$SUDO_PASS" | sudo -S systemctl is-active ollama-planner.service || echo "planner: INAKTIV"
echo "$SUDO_PASS" | sudo -S systemctl is-active ollama-mutation.service || echo "mutation: INAKTIV"
echo "$SUDO_PASS" | sudo -S systemctl is-active ollama-embedding.service || echo "embedding: INAKTIV"

echo ""
echo "=== Schritt 6: Port-Test ==="
for port in 11434 11436 11438; do
    if curl -s http://localhost:$port/api/tags > /dev/null 2>&1; then
        model=$(curl -s http://localhost:$port/api/tags | jq -r '.models[0].name' 2>/dev/null)
        echo "Port $port: OK (Modell: $model)"
    else
        echo "Port $port: FEHLER"
    fi
done

echo ""
echo "=== Schritt 7: GPU-Status ==="
rocm-smi --showuse --showmeminfo vram 2>/dev/null | grep -E "GPU\[|VRAM Total Used"

echo ""
echo "=== Schritt 8: Services enable ==="
echo "$SUDO_PASS" | sudo -S systemctl enable ollama-planner.service
echo "$SUDO_PASS" | sudo -S systemctl enable ollama-mutation.service
echo "$SUDO_PASS" | sudo -S systemctl enable ollama-embedding.service

echo ""
echo "=== FERTIG ==="
echo "Wenn alle Ports OK sind und GPU-Auslastung sichtbar ist, war es erfolgreich!"
