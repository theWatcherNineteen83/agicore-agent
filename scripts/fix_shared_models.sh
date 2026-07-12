#!/bin/bash
SUDO_PASS="Mut Motivation Respekt Willenskraft Qualitaet"

# Default-Modell-Verzeichnis verwenden, keine separate OLLAMA_MODELS
echo "=== Services stoppen ==="
echo "$SUDO_PASS" | sudo -S systemctl stop ollama-planner.service ollama-mutation.service ollama-embedding.service
echo "$SUDO_PASS" | sudo -S pkill -9 -f "ollama serve" 2>/dev/null || true
sleep 2

echo "=== Services reparieren (shared models dir) ==="

cat > /tmp/ollama-planner.service << 'EOF'
[Unit]
Description=Ollama Planner (qwen3.6:35b) - GPU1 (R9700)
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
[Install]
WantedBy=multi-user.target
EOF

cat > /tmp/ollama-mutation.service << 'EOF'
[Unit]
Description=Ollama Mutation (granite-code:3b) - GPU0 (7900 XTX)
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
[Install]
WantedBy=multi-user.target
EOF

cat > /tmp/ollama-embedding.service << 'EOF'
[Unit]
Description=Ollama Embedding (nomic-embed-text) - CPU
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
[Install]
WantedBy=multi-user.target
EOF

echo "$SUDO_PASS" | sudo -S cp /tmp/ollama-planner.service /etc/systemd/system/
echo "$SUDO_PASS" | sudo -S cp /tmp/ollama-mutation.service /etc/systemd/system/
echo "$SUDO_PASS" | sudo -S cp /tmp/ollama-embedding.service /etc/systemd/system/
rm -f /tmp/ollama-*.service
echo "$SUDO_PASS" | sudo -S systemctl daemon-reload

echo "=== Services starten ==="
echo "$SUDO_PASS" | sudo -S systemctl start ollama-planner.service
echo "$SUDO_PASS" | sudo -S systemctl start ollama-mutation.service
echo "$SUDO_PASS" | sudo -S systemctl start ollama-embedding.service
sleep 12

echo ""
echo "=== Status ==="
for svc in ollama-planner ollama-mutation ollama-embedding; do
    s=$(echo "$SUDO_PASS" | sudo -S systemctl is-active ${svc}.service 2>/dev/null)
    echo "$svc: $s"
done

echo ""
echo "=== Ports + Modelle ==="
for port in 11434 11436 11438; do
    tags=$(curl -s http://localhost:$port/api/tags 2>/dev/null)
    if echo "$tags" | jq -e '.models | length > 0' 2>/dev/null; then
        echo "Port $port: $(echo "$tags" | jq -r '.models[].name' | tr '\n' ', ')"
    else
        echo "Port $port: KEINE MODELLE"
    fi
done

echo ""
echo "=== GPU VRAM ==="
rocm-smi --showmeminfo vram 2>/dev/null | grep -E "GPU\[|Used"
echo "FERTIG"
