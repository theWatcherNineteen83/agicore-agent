#!/bin/bash
# =============================================================================
# Skript: fix_ollama_miniedi.sh
# Zweck: Korrektur für miniedi - manuelle Ausführung als prometheus
# Datum: 10.07.2026
# Autor: Prometheus (für Georg)
# AUSFÜHRUNG: Auf miniedi als User prometheus ausführen!
# =============================================================================

SUDO_PASS="Mut Motivation Respekt Willenskraft Qualitaet"

# 1. Alle Ollama-Prozesse beenden
echo "$SUDO_PASS" | sudo -S pkill -9 -f "ollama serve" || true
sleep 3

# 2. Prüfen ob Prozesse wirklich tot sind
if pgrep -f "ollama serve" > /dev/null; then
    echo "Warnung: Ollama-Prozesse laufen noch! Versuche es manuell:"
    echo "sudo pkill -9 -f 'ollama serve'"
    exit 1
fi

# 3. Neue Services starten
echo "Starte neue Ollama-Services..."
echo "$SUDO_PASS" | sudo -S systemctl start ollama-planner.service
echo "$SUDO_PASS" | sudo -S systemctl start ollama-mutation.service
echo "$SUDO_PASS" | sudo -S systemctl start ollama-embedding.service

# 4. Warten und Status prüfen
sleep 10
echo ""
echo "Service-Status:"
echo "$SUDO_PASS" | sudo -S systemctl status ollama-planner.service --no-pager || true
echo "$SUDO_PASS" | sudo -S systemctl status ollama-mutation.service --no-pager || true
echo "$SUDO_PASS" | sudo -S systemctl status ollama-embedding.service --no-pager || true

# 5. Ports prüfen
echo ""
echo "Port-Test:"
curl -s http://localhost:11434/api/tags | jq -r '.models[0].name' 2>/dev/null || echo "Port 11434: FEHLER"
curl -s http://localhost:11436/api/tags | jq -r '.models[0].name' 2>/dev/null || echo "Port 11436: FEHLER"
curl -s http://localhost:11438/api/tags | jq -r '.models[0].name' 2>/dev/null || echo "Port 11438: FEHLER"

# 6. GPU-Status
echo ""
echo "GPU-Status:"
rocm-smi --showuse --showmeminfo vram 2>/dev/null || echo "rocm-smi nicht verfügbar"

echo ""
echo "Fertig! Prüfe die Ausgabe oben."
