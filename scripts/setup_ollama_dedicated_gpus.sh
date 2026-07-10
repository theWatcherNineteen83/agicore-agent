#!/bin/bash
# =============================================================================
# Skript: setup_ollama_dedicated_gpus.sh
# Zweck: Dedizierte Ollama-Instanzen für Metis auf miniedi einrichten
#  - ollama-planner.service: GPU1 (R9700) mit qwen3.6:35b-a3b-q4_K_M
#  - ollama-mutation.service: GPU0 (7900 XTX) mit granite-code:3b
#  - ollama-embedding.service: CPU mit nomic-embed-text
# Datum: 10.07.2026
# Autor: Prometheus (für Georg)
# =============================================================================

set -e  # Bei Fehlern abbrechen
set -u  # Ungebundene Variablen als Fehler behandeln

# =============================================================================
# KONFIGURATION
# =============================================================================
MINIEDI_HOST="192.168.22.204"
MINIEDI_USER="prometheus"
SSH_KEY="/home/admini/keys/prometheus_ed25519"
SSH_OPTS="-i ${SSH_KEY} -o StrictHostKeyChecking=no"

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

ssh ${SSH_OPTS} ${MINIEDI_USER}@${MINIEDI_HOST} << 'EOF'
    # Services stoppen (falls sie laufen)
    echo "Stoppe alte Ollama-Services..."
    sudo systemctl stop ollama-gpu0.service ollama-gpu1.service ollama-cpu.service 2>/dev/null || true
    sudo systemctl stop ollama-router.service 2>/dev/null || true
    
    # Services deaktivieren
    echo "Deaktiviere alte Services..."
    sudo systemctl disable ollama-gpu0.service ollama-gpu1.service ollama-cpu.service 2>/dev/null || true
    sudo systemctl disable ollama-router.service 2>/dev/null || true
    
    # Prozesse töten (falls noch laufend)
    echo "Beende laufende Ollama-Prozesse..."
    pkill -f "ollama serve" 2>/dev/null || true
    sleep 2
    
    echo "Alte Services erfolgreich gestoppt."
EOF

# =============================================================================
# 2. NEUE SYSTEM-SERVICES ERSTELLEN
# =============================================================================
log_header "2. Neue systemd-Services für dedizierte GPUs erstellen"

# Service 1: ollama-planner (GPU1 = R9700, Port 11434, qwen3.6:35b)
log_info "Erstelle ollama-planner.service (GPU1, Port 11434)..."
ssh ${SSH_OPTS} ${MINIEDI_USER}@${MINIEDI_HOST} << 'EOF'
    sudo tee /etc/systemd/system/ollama-planner.service > /dev/null << 'SERVICE'
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
SERVICE
    echo "ollama-planner.service erstellt."
EOF

# Service 2: ollama-mutation (GPU0 = 7900 XTX, Port 11436, granite-code:3b)
log_info "Erstelle ollama-mutation.service (GPU0, Port 11436)..."
ssh ${SSH_OPTS} ${MINIEDI_USER}@${MINIEDI_HOST} << 'EOF'
    sudo tee /etc/systemd/system/ollama-mutation.service > /dev/null << 'SERVICE'
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
SERVICE
    echo "ollama-mutation.service erstellt."
EOF

# Service 3: ollama-embedding (CPU, Port 11438, nomic-embed-text)
log_info "Erstelle ollama-embedding.service (CPU, Port 11438)..."
ssh ${SSH_OPTS} ${MINIEDI_USER}@${MINIEDI_HOST} << 'EOF'
    sudo tee /etc/systemd/system/ollama-embedding.service > /dev/null << 'SERVICE'
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
SERVICE
    echo "ollama-embedding.service erstellt."
EOF

# =============================================================================
# 3. MODELLE VORLADEN (falls noch nicht vorhanden)
# =============================================================================
log_header "3. Modelle vorladen (falls nötig)"

ssh ${SSH_OPTS} ${MINIEDI_USER}@${MINIEDI_HOST} << 'EOF'
    echo "Prüfe, ob Modelle bereits vorhanden sind..."
    
    # qwen3.6:35b prüfen
    if ! ollama list | grep -q "qwen3.6:35b-a3b-q4_K_M"; then
        echo "Lade qwen3.6:35b-a3b-q4_K_M herunter..."
        ollama pull qwen3.6:35b-a3b-q4_K_M
    else
        echo "qwen3.6:35b-a3b-q4_K_M bereits vorhanden."
    fi
    
    # granite-code:3b prüfen
    if ! ollama list | grep -q "granite-code:3b"; then
        echo "Lade granite-code:3b herunter..."
        ollama pull granite-code:3b
    else
        echo "granite-code:3b bereits vorhanden."
    fi
    
    # nomic-embed-text prüfen
    if ! ollama list | grep -q "nomic-embed-text"; then
        echo "Lade nomic-embed-text herunter..."
        ollama pull nomic-embed-text
    else
        echo "nomic-embed-text bereits vorhanden."
    fi
    
    echo "Modell-Check abgeschlossen."
EOF

# =============================================================================
# 4. SERVICES AKTIVIEREN UND STARTEN
# =============================================================================
log_header "4. Neue Services aktivieren und starten"

ssh ${SSH_OPTS} ${MINIEDI_USER}@${MINIEDI_HOST} << 'EOF'
    echo "Lade systemd-Konfiguration neu..."
    sudo systemctl daemon-reload
    
    echo "Aktiviere neue Services..."
    sudo systemctl enable ollama-planner.service
    sudo systemctl enable ollama-mutation.service
    sudo systemctl enable ollama-embedding.service
    
    echo "Starte neue Services..."
    sudo systemctl start ollama-planner.service
    sudo systemctl start ollama-mutation.service
    sudo systemctl start ollama-embedding.service
    
    echo "Warte 10 Sekunden, bis Services gestartet sind..."
    sleep 10
    
    echo "Prüfe Service-Status..."
    sudo systemctl status ollama-planner.service --no-pager || true
    sudo systemctl status ollama-mutation.service --no-pager || true
    sudo systemctl status ollama-embedding.service --no-pager || true
EOF

# =============================================================================
# 5. GPUS AUSLASTUNG PRÜFEN
# =============================================================================
log_header "5. GPU-Auslastung prüfen"

ssh ${SSH_OPTS} ${MINIEDI_USER}@${MINIEDI_HOST} << 'EOF'
    echo "GPU-Status (rocm-smi):"
    echo "========================================"
    rocm-smi --showuse --showmeminfo vram 2>/dev/null || echo "rocm-smi nicht verfügbar, versuche mit amdgpu-top..."
    
    echo -e "\nOllama-Services prüfen:"
    echo "========================================"
    curl -s http://localhost:11434/api/tags | jq . || echo "Port 11434 nicht erreichbar"
    curl -s http://localhost:11436/api/tags | jq . || echo "Port 11436 nicht erreichbar"
    curl -s http://localhost:11438/api/tags | jq . || echo "Port 11438 nicht erreichbar"
EOF

# =============================================================================
# 6. METIS-KONFIGURATION ANPASSEN (falls nötig)
# =============================================================================
log_header "6. Metis-Konfiguration für neue Ports"

log_warn "ACHTUNG: Metis muss an die neuen Ports angepasst werden!"
log_warn "Aktuell nutzt Metis:"
log_warn "  - Planner: Port 11434 (bereits korrekt)"
log_warn "  - Mutation: Port 11434 (MUSS auf 11436 geändert werden!)"
log_warn "  - Embeddings: Port 11438 (bereits korrekt)"

ssh ${SSH_OPTS} ${MINIEDI_USER}@${MINIEDI_HOST} << 'EOF'
    echo "Aktuelle Metis-Konfiguration (falls als systemd-Service):"
    echo "========================================"
    sudo systemctl cat metis.service 2>/dev/null || echo "metis.service nicht gefunden"
    
    echo -e "\nMetis-Prozess prüfen:"
    echo "========================================"
    pgrep -af metis-agent.jar || echo "Metis läuft nicht"
EOF

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
echo "    - /home/prometheus/metis/metis-agent.jar CLI-Args"
echo "    - oder systemd-Service: /etc/systemd/system/metis.service"
echo "    - oder Konfig-Datei: application.properties"
echo ""
echo "  Beispiel für systemd (Metis-Service):"
echo "    Environment=\"METIS_PLANNING_URL=http://localhost:11434\""
echo "    Environment=\"METIS_MUTATION_URL=http://localhost:11436\""
echo "    Environment=\"METIS_EMBEDDING_URL=http://localhost:11438\""
echo "================================================================"
echo "${NC}"

# =============================================================================
# 8. TEST-LAUF (optional)
# =============================================================================
read -p "Möchtest du einen Test-Lauf durchführen (Metis-Healthcheck)? (j/n): " -n 1 -r
if [[ $REPLY =~ ^[Jj]$ ]]; then
    echo ""
    log_header "8. Test-Lauf: Metis-Healthcheck"
    ssh ${SSH_OPTS} ${MINIEDI_USER}@${MINIEDI_HOST} << 'EOF'
        echo "Metis-Healthcheck (Port 11735):"
        echo "========================================"
        curl -s --max-time 10 http://localhost:11735/api/status | jq . || echo "Metis nicht erreichbar oder läuft nicht"
        
        echo -e "\nGPU-Status nach Metis-Start:"
        echo "========================================"
        rocm-smi --showuse --showmeminfo vram 2>/dev/null || echo "rocm-smi nicht verfügbar"
    EOF
fi

echo ""
log_info "Skript abgeschlossen. GPU-Load-Balancing sollte jetzt funktionieren!"
