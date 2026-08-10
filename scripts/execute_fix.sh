#!/bin/bash
# Skript wird auf miniedi ausgefuehrt
SUDO_PASS="Mut Motivation Respekt Willenskraft Qualitaet"

echo "$SUDO_PASS" | sudo -S pkill -9 -f "ollama serve" 2>/dev/null || true
sleep 3

echo "$SUDO_PASS" | sudo -S sed -i 's|/usr/bin/ollama|/usr/local/bin/ollama|g' /etc/systemd/system/ollama-planner.service
echo "$SUDO_PASS" | sudo -S sed -i 's|/usr/bin/ollama|/usr/local/bin/ollama|g' /etc/systemd/system/ollama-mutation.service
echo "$SUDO_PASS" | sudo -S sed -i 's|/usr/bin/ollama|/usr/local/bin/ollama|g' /etc/systemd/system/ollama-embedding.service

echo "$SUDO_PASS" | sudo -S systemctl daemon-reload

echo "$SUDO_PASS" | sudo -S systemctl start ollama-planner.service
echo "$SUDO_PASS" | sudo -S systemctl start ollama-mutation.service
echo "$SUDO_PASS" | sudo -S systemctl start ollama-embedding.service

sleep 10

echo "=== STATUS ==="
echo "$SUDO_PASS" | sudo -S systemctl status ollama-planner.service ollama-mutation.service ollama-embedding.service --no-pager 2>&1 || true

echo ""
echo "=== PORTS ==="
curl -s http://localhost:11434/api/tags | jq -r '.models[0].name' 2>/dev/null || echo "11434: FEHLER"
curl -s http://localhost:11436/api/tags | jq -r '.models[0].name' 2>/dev/null || echo "11436: FEHLER"
curl -s http://localhost:11438/api/tags | jq -r '.models[0].name' 2>/dev/null || echo "11438: FEHLER"

echo ""
echo "=== GPU ==="
rocm-smi --showuse --showmeminfo vram 2>/dev/null || echo "rocm-smi nicht verfuegbar"

echo ""
echo "FERTIG!"
