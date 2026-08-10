#!/bin/bash
# Komplettes Fix-Skript für Ollama v0.30.10
SUDO_PASS="Mut Motivation Respekt Willenskraft Qualitaet"

echo "=== Schritt 1: Alle Ollama-Prozesse beenden ==="
echo "$SUDO_PASS" | sudo -S pkill -9 -f "ollama serve" 2>/dev/null || true
sleep 3
if pgrep -f "ollama serve" > /dev/null; then
    echo "FEHLER: Ollama-Prozesse laufen noch!"
    exit 1
fi
echo "OK: Alle Ollama-Prozesse beendet"

echo ""
echo "=== Schritt 2: Separate Modell-Verzeichnisse erstellen ==="
sudo mkdir -p /home/prometheus/ollama-planner-models
sudo mkdir -p /home/prometheus/ollama-mutation-models
sudo mkdir -p /home/prometheus/ollama-embedding-models
sudo chown -R prometheus:prometheus /home/prometheus/ollama-*-models

# Modelle in die richtigen Verzeichnisse kopieren
if [ -f "/home/prometheus/.ollama/models/manifests/qwen3.6:35b-a3b-q4_K_M" ]; then
    sudo cp -r /home/prometheus/.ollama/models/manifests/qwen3.6* /home/prometheus/ollama-planner-models/
    sudo cp -r /home/prometheus/.ollama/models/blobs/*qwen3.6* /home/prometheus/ollama-planner-models/blobs/ 2>/dev/null || true
fi

if [ -f "/home/prometheus/.ollama/models/manifests/granite-code:3b" ]; then
    sudo cp -r /home/prometheus/.ollama/models/manifests/granite-code* /home/prometheus/ollama-mutation-models/
    sudo cp -r /home/prometheus/.ollama/models/blobs/*granite-code* /home/prometheus/ollama-mutation-models/blobs/ 2>/dev/null || true
fi

if [ -f "/home/prometheus/.ollama/models/manifests/nomic-embed-text" ]; then
    sudo cp -r /home/prometheus/.ollama/models/manifests/nomic-embed-text* /home/prometheus/ollama-embedding-models/
    sudo cp -r /home/prometheus/.ollama/models/blobs/*nomic-embed-text* /home/prometheus/ollama-embedding-models/blobs/ 2>/dev/null || true
fi

echo "OK: Modell-Verzeichnisse erstellt"

echo ""
echo "=== Schritt 3: systemd-Services mit separaten Modell-Verzeichnissen ==="

# ollama-planner.service
cat > /tmp/ollama-planner.service << 'EOF'
[Unit]
Description=Ollama Planner (qwen3.6:35b) - GPU1 (R9700)
After=network.target

[Service]
Type=simple
ExecStart=/usr/local/bin/ollama serve
Environment="OLLAMA_HOST=0.0.0.0:11434"
Environment="OLLAMA_MODELS=/home/prometheus/ollama-planner-models"
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
Description=Ollama Mutation (granite-code:3b) - GPU0 (7900 XTX)
After=network.target

[Service]
Type=simple
ExecStart=/usr/local/bin/ollama serve
Environment="OLLAMA_HOST=0.0.0.0:11436"
Environment="OLLAMA_MODELS=/home/prometheus/ollama-mutation-models"
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
Description=Ollama Embedding (nomic-embed-text) - CPU
After=network.target

[Service]
Type=simple
ExecStart=/usr/local/bin/ollama serve
Environment="OLLAMA_HOST=0.0.0.0:11438"
Environment="OLLAMA_MODELS=/home/prometheus/ollama-embedding-models"
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
rm -f /tmp/ollama-*.service

echo "$SUDO_PASS" | sudo -S systemctl daemon-reload
echo "OK: systemd-Services korrigiert"

echo ""
echo "=== Schritt 4: Services starten ==="
echo "$SUDO_PASS" | sudo -S systemctl restart ollama-planner.service
echo "$SUDO_PASS" | sudo -S systemctl restart ollama-mutation.service
echo "$SUDO_PASS" | sudo -S systemctl restart ollama-embedding.service

# Warten
sleep 15

echo ""
echo "=== Schritt 5: Port-Test ==="
for port in 11434 11436 11438; do
    if curl -s http://localhost:$port/api/tags > /dev/null 2>&1; then
        model=$(curl -s http://localhost:$port/api/tags | jq -r '.models[0].name' 2>/dev/null)
        echo "Port $port: OK (Modell: $model)"
    else
        echo "Port $port: FEHLER"
    fi
done

echo ""
echo "=== Schritt 6: GPU-Status ==="
rocm-smi --showuse --showmeminfo vram 2>/dev/null | grep -E "GPU\[|VRAM Total Used"

echo ""
echo "=== Schritt 7: Services enable ==="
echo "$SUDO_PASS" | sudo -S systemctl enable ollama-planner.service
echo "$SUDO_PASS" | sudo -S systemctl enable ollama-mutation.service
echo "$SUDO_PASS" | sudo -S systemctl enable ollama-embedding.service

echo ""
echo "=== FERTIG ==="
