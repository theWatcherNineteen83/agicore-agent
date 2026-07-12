#!/bin/bash
SUDO_PASS="Mut Motivation Respekt Willenskraft Qualitaet"

# 1. Alle Ollama-Prozesse finden und töten
PIDS=$(ps aux | grep 'ollama serve' | grep -v grep | awk '{print $2}')
for pid in $PIDS; do
    echo "$SUDO_PASS" | sudo -S kill -9 $pid 2>/dev/null || true
    sleep 1
done

# 2. Prüfen ob Ports frei sind
echo "Prüfe Ports..."
ss -tlnp | grep 1143

# 3. Services neu starten
echo "Starte Services neu..."
echo "$SUDO_PASS" | sudo -S systemctl daemon-reload
echo "$SUDO_PASS" | sudo -S systemctl restart ollama-planner.service
echo "$SUDO_PASS" | sudo -S systemctl restart ollama-mutation.service
echo "$SUDO_PASS" | sudo -S systemctl restart ollama-embedding.service

# 4. Warten
sleep 10

# 5. Status prüfen
echo "=== STATUS ==="
echo "$SUDO_PASS" | sudo -S systemctl status ollama-planner.service ollama-mutation.service ollama-embedding.service --no-pager 2>&1 | head -30

echo ""
echo "=== PORTS ==="
curl -s http://localhost:11434/api/tags | jq -r '.models[0].name' 2>/dev/null || echo "11434: FEHLER"
curl -s http://localhost:11436/api/tags | jq -r '.models[0].name' 2>/dev/null || echo "11436: FEHLER"
curl -s http://localhost:11438/api/tags | jq -r '.models[0].name' 2>/dev/null || echo "11438: FEHLER"

echo ""
echo "=== GPU ==="
rocm-smi --showuse --showmeminfo vram 2>/dev/null | head -10

echo "FERTIG!"
