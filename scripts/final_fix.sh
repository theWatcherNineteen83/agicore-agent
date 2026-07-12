#!/bin/bash
# Finales Fix-Skript - wird auf miniedi als prometheus ausgefuehrt
SUDO_PASS="Mut Motivation Respekt Willenskraft Qualitaet"

echo "=== Schritt 1: Alle Ollama-Prozesse beenden ==="
# Als root ausfuehren
echo "$SUDO_PASS" | sudo -S pkill -9 -f "ollama serve" 2>/dev/null || true
sleep 3

# Pruefen ob Prozesse weg sind
if pgrep -f "ollama serve" > /dev/null; then
    echo "FEHLER: Ollama-Prozesse laufen noch!"
    exit 1
fi
echo "OK: Alle Ollama-Prozesse beendet"

echo ""
echo "=== Schritt 2: Ports pruefen ==="
ss -tlnp | grep 1143 || echo "Alle 1143-Ports sind frei"

echo ""
echo "=== Schritt 3: systemd neu laden ==="
echo "$SUDO_PASS" | sudo -S systemctl daemon-reload

echo ""
echo "=== Schritt 4: Services starten ==="
echo "$SUDO_PASS" | sudo -S systemctl restart ollama-planner.service
echo "$SUDO_PASS" | sudo -S systemctl restart ollama-mutation.service
echo "$SUDO_PASS" | sudo -S systemctl restart ollama-embedding.service

# Warten bis Services laufen
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
echo "=== FERTIG ==="
echo "Wenn alle Ports OK sind und GPU-Auslastung sichtbar ist, war es erfolgreich!"
