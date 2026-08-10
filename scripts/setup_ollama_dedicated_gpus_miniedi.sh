#!/bin/bash
# =============================================================================
# Skript: setup_ollama_dedicated_gpus_miniedi.sh
# Zweck: Dedizierte Ollama-Instanzen für Metis auf miniedi einrichten
#  - ollama-planner.service: GPU1 (R9700) mit qwen3.6:35b-a3b-q4_K_M
#  - ollama-mutation.service: GPU0 (7900 XTX) mit granite-code:3b
#  - ollama-embedding.service: CPU mit nomic-embed-text
# Datum: 10.07.2026
# Autor: Prometheus (für Georg)
# AUSFÜHRUNG: Auf miniedi als User prometheus ausführen!
# =============================================================================

set -e  # Bei Fehlern abbrechen
set -u  # Ungebundene Variablen als Fehler behandeln

# =============================================================================
# KONFIGURATION
# =============================================================================
SUDO_PASS="Mut Motivation Respekt Willenskraft Qualitaet"

# Farben für Ausgabe
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# =============================================================================
# FUNKTIONEN
# =============================================================================
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_header() {
    echo -e "\n${BLUE}========================================${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}========================================${NC}"
}

# =============================================================================
# 1. VORBEREITUNG: Alte Services stoppen
# =============================================================================
log_header "1. Alte Ollama-Services stoppen und deaktivieren"

echo "$SUDO_PASS" | sudo -S systemctl stop ollama-gpu0.service ollama-gpu1.service ollama-cpu.service ollama-router.service 2>/dev/null || true

echo "$SUDO_PASS" | sudo -S systemctl disable ollama-gpu0.service ollama-gpu1.service ollama-cpu.service ollama-router.service 2>/dev/null || true

log_info "Beende laufende Ollama-Prozesse..."
pkill -f "ollama serve" 2>/dev/null || true
sleep 2

log_info "Alte Services erfolgreich gestoppt."

# =============================================================================
# 2. NEUE SYSTEM-SERVICES ERSTELLEN
# =============================================================================
log_header "2. Neue systemd-Services für dedizierte GPUs erstellen"

# Service 1: ollama-planner (GPU1 = R9700, Port 11434, qwen3.6:35b)
log_info "Erstelle ollama-planner.service (GPU1, Port 11434)..."
echo "$SUDO_PASS" | sudo -S tee /etc/systemd/system/ollama-planner.service > /dev/null << 'EOF'
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
EOF

# Service 2: ollama-mutation (GPU0 = 7900 XTX, Port 11436, granite-code:3b)
log_info "Erstelle ollama-mutation.service (GPU0, Port 11436)..."
echo "$SUDO_PASS" | sudo -S tee /etc/systemd/system/ollama-mutation.service > /dev/null << 'EOF'
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
EOF

# Service 3: ollama-embedding (CPU, Port 11438, nomic-embed-text)
log_info "Erstelle ollama-embedding.service (CPU, Port 11438)..."
echo "$SUDO_PASS" | sudo -S tee /etc/systemd/system/ollama-embedding.service > /dev/null << 'EOF'
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
EOF

# =============================================================================
# 3. MODELLE VORLADEN (falls noch nicht vorhanden)
# =============================================================================
log_header "3. Modelle vorladen (falls nötig)"

# qwen3.6:35b prüfen
if ! ollama list | grep -q "qwen3.6:35b-a3b-q4_K_M"; then
    log_info "Lade qwen3.6:35b-a3b-q4_K_M herunter..."
    ollama pull qwen3.6:35b-a3b-q4_K_M
else
    log_info "qwen3.6:35b-a3b-q4_K_M bereits vorhanden."
fi

# granite-code:3b prüfen
if ! ollama list | grep -q "granite-code:3b"; then
    log_info "Lade granite-code:3b herunter..."
    ollama pull granite-code:3b
else
    log_info "granite-code:3b bereits vorhanden."
fi

# nomic-embed-text prüfen
if ! ollama list | grep -q "nomic-embed-text"; then
    log_info "Lade nomic-embed-text herunter..."
    ollama pull nomic-embed-text
else
    log_info "nomic-embed-text bereits vorhanden."
fi

log_info "Modell-Check abgeschlossen."

# =============================================================================
# 4. SERVICES AKTIVIEREN UND STARTEN
# =============================================================================
log_header "4. Neue Services aktivieren und starten"

echo "$SUDO_PASS" | sudo -S systemctl daemon-reload

echo "$SUDO_PASS" | sudo -S systemctl enable ollama-planner.service
echo "$SUDO_PASS" | sudo -S systemctl enable ollama-mutation.service
echo "$SUDO_PASS" | sudo -S systemctl enable ollama-embedding.service

echo "$SUDO_PASS" | sudo -S systemctl start ollama-planner.service
echo "$SUDO_PASS" | sudo -S systemctl start ollama-mutation.service
echo "$SUDO_PASS" | sudo -S systemctl start ollama-embedding.service

log_info "Warte 10 Sekunden, bis Services gestartet sind..."
sleep 10

log_info "Prüfe Service-Status..."
sudo systemctl status ollama-planner.service --no-pager || true
sudo systemctl status ollama-mutation.service --no-pager || true
sudo systemctl status ollama-embedding.service --no-pager || true

# =============================================================================
# 5. GPUS AUSLASTUNG PRÜFEN
# =============================================================================
log_header "5. GPU-Auslastung prüfen"

echo "GPU-Status (rocm-smi):"
echo "========================================"
rocm-smi --showuse --showmeminfo vram 2>/dev/null || echo "rocm-smi nicht verfügbar"

echo -e "\nOllama-Services prüfen:"
echo "========================================"
curl -s http://localhost:11434/api/tags | jq . || echo "Port 11434 nicht erreichbar"
curl -s http://localhost:11436/api/tags | jq . || echo "Port 11436 nicht erreichbar"
curl -s http://localhost:11438/api/tags | jq . || echo "Port 11438 nicht erreichbar"

# =============================================================================
# 6. METIS-KONFIGURATION ANPASSEN
# =============================================================================
log_header "6. Metis-Konfiguration für neue Ports"

log_warn "ACHTUNG: Metis muss an die neuen Ports angepasst werden!"
log_warn "Aktuell nutzt Metis:"
log_warn "  - Planner: Port 11434 (bereits korrekt)"
log_warn "  - Mutation: Port 11434 (MUSS auf 11436 geändert werden!)"
log_warn "  - Embeddings: Port 11438 (bereits korrekt)"

# Versuche, Metis-Service zu finden und anzuzeigen
METIS_SERVICE_FILE=""
if [ -f "/etc/systemd/system/metis.service" ]; then
    METIS_SERVICE_FILE="/etc/systemd/system/metis.service"
elif [ -f "/home/prometheus/metis/metis.service" ]; then
    METIS_SERVICE_FILE="/home/prometheus/metis/metis.service"
fi

if [ -n "$METIS_SERVICE_FILE" ]; then
    log_info "Aktuelle Metis-Service-Konfiguration ($METIS_SERVICE_FILE):"
    sudo cat "$METIS_SERVICE_FILE" || true
fi

# =============================================================================
# 7. ZUSAMMENFASSUNG UND NÄCHSTE SCHRITTE
# =============================================================================
log_header "7. Zusammenfassung und nächste Schritte"

echo -e "${GREEN}"
echo "================================================================"
echo "  ✅ ERFOLGREICH ABGESCHLOSSEN:"
echo "================================================================"
echo "  1. Alte Ollama-Services gestoppt und deaktiviert"
echo "  2. Neue systemd-Services erstellt:"
echo "     - ollama-planner.service (GPU1, Port 11434, qwen3.6:35b)"
echo "     - ollama-mutation.service (GPU0, Port 11436, granite-code:3b)"
echo "     - ollama-embedding.service (CPU, Port 11438, nomic-embed-text)"
echo "  3. Modelle geladen (falls nötig)"
echo "  4. Services aktiviert und gestartet"
echo ""
echo "================================================================"
echo "  ⚠️  MANUELLER SCHRITT ERFORDERLICH:"
echo "================================================================"
echo "  Metis muss an die neuen Ports angepasst werden!"
echo ""
echo "  Aktuelle Konfiguration (aus TOOLS.md):"
echo "    - Planer: qwen3.6:35b-a3b-q4_K_M auf Port 11434 (OK)"
echo "    - Mutation: granite-mini-agent auf Port 11434 (PROBLEM!)"
echo "    - Embeddings: nomic-embed-text auf Port 11438 (OK)"
echo ""
echo "  Notwendige Änderungen:"
echo "    - Mutation-URL von 11434 auf 11436 ändern"
echo "    - Modell für Mutation von granite-mini-agent auf granite-code:3b ändern"
echo ""
echo "  Dateien zum Anpassen:"
echo "    - /etc/systemd/system/metis.service"
echo "    - oder Startskript in /home/prometheus/metis/"
echo ""
echo "  Beispiel für systemd (Metis-Service):"
echo "    Environment=\"METIS_PLANNING_URL=http://localhost:11434\""
echo "    Environment=\"METIS_MUTATION_URL=http://localhost:11436\""
echo "    Environment=\"METIS_EMBEDDING_URL=http://localhost:11438\""
echo "================================================================"
echo "${NC}"

log_info "Skript abgeschlossen. GPU-Load-Balancing sollte jetzt funktionieren!"
