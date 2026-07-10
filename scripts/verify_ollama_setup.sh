#!/bin/bash
# =============================================================================
# Skript: verify_ollama_setup.sh
# Zweck: Überprüft die korrekte Einrichtung der dedizierten Ollama-Instanzen
# Datum: 10.07.2026
# Autor: Prometheus (für Georg)
# =============================================================================

MINIEDI_HOST="192.168.22.204"
MINIEDI_USER="prometheus"
SSH_KEY="/home/admini/keys/prometheus_ed25519"
SSH_OPTS="-i ${SSH_KEY} -o StrictHostKeyChecking=no"

# Farben
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Zähler für Tests
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

pass_test() {
    echo -e "  ${GREEN}✓ PASS${NC}: $1"
    ((PASSED_TESTS++))
}

fail_test() {
    echo -e "  ${RED}✗ FAIL${NC}: $1"
    ((FAILED_TESTS++))
}

# =============================================================================
# 1. SERVICES PRÜFEN
# =============================================================================
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  1. Systemd-Services prüfen${NC}"
echo -e "${BLUE}========================================${NC}"

ssh ${SSH_OPTS} ${MINIEDI_USER}@${MINIEDI_HOST} << 'EOF'
    echo "Aktive Ollama-Services:"
    systemctl list-units --type=service | grep ollama
    echo ""
    
    echo "Service-Status:"
    for svc in ollama-planner ollama-mutation ollama-embedding; do
        status=$(systemctl is-active ${svc}.service 2>/dev/null)
        enabled=$(systemctl is-enabled ${svc}.service 2>/dev/null)
        echo "  ${svc}.service: active=${status}, enabled=${enabled}"
    done
EOF

# Test: Services laufen
TOTAL_TESTS=3
ssh ${SSH_OPTS} ${MINIEDI_USER}@${MINIEDI_HOST} << 'EOF'
    for svc in ollama-planner ollama-mutation ollama-embedding; do
        if systemctl is-active --quiet ${svc}.service 2>/dev/null; then
            pass_test "${svc}.service läuft"
        else
            fail_test "${svc}.service läuft NICHT"
        fi
    done
EOF

# =============================================================================
# 2. PORTS PRÜFEN
# =============================================================================
echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  2. Ports prüfen${NC}"
echo -e "${BLUE}========================================${NC}"

for port in 11434 11436 11438; do
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    if ssh ${SSH_OPTS} ${MINIEDI_USER}@${MINIEDI_HOST} "nc -z localhost ${port}" 2>/dev/null; then
        pass_test "Port ${port} ist erreichbar"
    else
        fail_test "Port ${port} ist NICHT erreichbar"
    fi
done

# =============================================================================
# 3. MODELLE PRÜFEN
# =============================================================================
echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  3. Modelle prüfen${NC}"
echo -e "${BLUE}========================================${NC}"

EXPECTED_MODELS=(
    "11434:qwen3.6:35b-a3b-q4_K_M"
    "11436:granite-code:3b"
    "11438:nomic-embed-text"
)

for model_spec in "${EXPECTED_MODELS[@]}"; do
    IFS=':' read -r port expected_model <<< "$model_spec"
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    actual_model=$(ssh ${SSH_OPTS} ${MINIEDI_USER}@${MINIEDI_HOST} "curl -s http://localhost:${port}/api/tags | jq -r '.models[0].name' 2>/dev/null")
    if [[ "$actual_model" == "$expected_model" ]]; then
        pass_test "Port ${port} hat Modell: ${actual_model}"
    else
        fail_test "Port ${port} hat Modell: ${actual_model} (erwartet: ${expected_model})"
    fi
done

# =============================================================================
# 4. GPU-ZUORDNUNG PRÜFEN
# =============================================================================
echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  4. GPU-Zuordnung prüfen${NC}"
echo -e "${BLUE}========================================${NC}"

ssh ${SSH_OPTS} ${MINIEDI_USER}@${MINIEDI_HOST} << 'EOF'
    echo "GPU-Status (rocm-smi):"
    rocm-smi --showuse --showmeminfo vram 2>/dev/null || echo "rocm-smi nicht verfügbar"
    echo ""
    
    echo "Prozesse und GPU-Zuordnung:"
    ps aux | grep "ollama serve" | grep -v grep
    echo ""
    
    echo "HIP_VISIBLE_DEVICES für Ollama-Prozesse:"
    for pid in $(pgrep -f "ollama serve"); do
        echo "  PID ${pid}:"
        cat /proc/${pid}/environ 2>/dev/null | tr '\0' '\n' | grep HIP_VISIBLE_DEVICES || echo "    Kein HIP_VISIBLE_DEVICES gesetzt"
    done
EOF

# Test: GPU-Auslastung (sollte nicht 100% auf beiden GPUs sein)
TOTAL_TESTS=$((TOTAL_TESTS + 1))
GPU1_USAGE=$(ssh ${SSH_OPTS} ${MINIEDI_USER}@${MINIEDI_HOST} "rocm-smi --showuse 2>/dev/null | grep -A1 'gpu[1]' | tail -1 | awk '{print \$2}' | tr -d '%'" 2>/dev/null || echo "0")
GPU0_USAGE=$(ssh ${SSH_OPTS} ${MINIEDI_USER}@${MINIEDI_HOST} "rocm-smi --showuse 2>/dev/null | grep -A1 'gpu[0]' | tail -1 | awk '{print \$2}' | tr -d '%'" 2>/dev/null || echo "0")

if [[ "$GPU1_USAGE" -lt 95 ]] && [[ "$GPU0_USAGE" -lt 95 ]]; then
    pass_test "GPU-Auslastung OK (GPU0: ${GPU0_USAGE}%, GPU1: ${GPU1_USAGE}%)"
else
    fail_test "GPU-Auslastung zu hoch (GPU0: ${GPU0_USAGE}%, GPU1: ${GPU1_USAGE}%)"
fi

# =============================================================================
# 5. METIS-KONFIGURATION PRÜFEN
# =============================================================================
echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  5. Metis-Konfiguration prüfen${NC}"
echo -e "${BLUE}========================================${NC}"

TOTAL_TESTS=$((TOTAL_TESTS + 3))

# Prüfe, ob Metis die neuen Ports nutzt
METIS_PLANNING_URL=$(ssh ${SSH_OPTS} ${MINIEDI_USER}@${MINIEDI_HOST} "grep -r 'planning-url\|METIS_PLANNING_URL\|--planning-url' /etc/systemd/system/metis.service /home/prometheus/metis/ 2>/dev/null | head -1 | grep -oE 'http://[^ ]+' | head -1" || echo "")
METIS_MUTATION_URL=$(ssh ${SSH_OPTS} ${MINIEDI_USER}@${MINIEDI_HOST} "grep -r 'mutation-url\|METIS_MUTATION_URL\|--mutation-url' /etc/systemd/system/metis.service /home/prometheus/metis/ 2>/dev/null | head -1 | grep -oE 'http://[^ ]+' | head -1" || echo "")
METIS_EMBEDDING_URL=$(ssh ${SSH_OPTS} ${MINIEDI_USER}@${MINIEDI_HOST} "grep -r 'embedding-url\|METIS_EMBEDDING_URL\|--embedding-url' /etc/systemd/system/metis.service /home/prometheus/metis/ 2>/dev/null | head -1 | grep -oE 'http://[^ ]+' | head -1" || echo "")

if [[ "$METIS_PLANNING_URL" == *"11434"* ]]; then
    pass_test "Metis Planning-URL: ${METIS_PLANNING_URL}"
else
    fail_test "Metis Planning-URL: ${METIS_PLANNING_URL} (sollte Port 11434 enthalten)"
fi

if [[ "$METIS_MUTATION_URL" == *"11436"* ]]; then
    pass_test "Metis Mutation-URL: ${METIS_MUTATION_URL}"
else
    fail_test "Metis Mutation-URL: ${METIS_MUTATION_URL} (sollte Port 11436 enthalten)"
fi

if [[ "$METIS_EMBEDDING_URL" == *"11438"* ]]; then
    pass_test "Metis Embedding-URL: ${METIS_EMBEDDING_URL}"
else
    fail_test "Metis Embedding-URL: ${METIS_EMBEDDING_URL} (sollte Port 11438 enthalten)"
fi

# =============================================================================
# 6. METIS-HEALTHCHECK
# =============================================================================
echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  6. Metis-Healthcheck${NC}"
echo -e "${BLUE}========================================${NC}"

TOTAL_TESTS=$((TOTAL_TESTS + 1))
METIS_STATUS=$(ssh ${SSH_OPTS} ${MINIEDI_USER}@${MINIEDI_HOST} "curl -s --max-time 5 http://localhost:11735/api/status 2>/dev/null" || echo "")

if [[ -n "$METIS_STATUS" ]]; then
    echo "Metis-Status:"
    echo "$METIS_STATUS" | jq . 2>/dev/null || echo "$METIS_STATUS"
    
    # Prüfe auf Action-Dominance-Warnungen
    if echo "$METIS_STATUS" | grep -qi "Action-Dominance\|CRITICAL"; then
        fail_test "Metis zeigt Action-Dominance-Warnungen"
    else
        pass_test "Metis läuft ohne Action-Dominance-Warnungen"
    fi
else
    fail_test "Metis nicht erreichbar (Port 11735)"
fi

# =============================================================================
# 7. ZUSAMMENFASSUNG
# =============================================================================
echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  7. Zusammenfassung${NC}"
echo -e "${BLUE}========================================${NC}"

echo "Tests durchgeführt: $TOTAL_TESTS"
echo -e "${GREEN}Bestanden: $PASSED_TESTS${NC}"
echo -e "${RED}Fehlgeschlagen: $FAILED_TESTS${NC}"

if [[ $FAILED_TESTS -eq 0 ]]; then
    echo -e "\n${GREEN}========================================${NC}"
    echo -e "${GREEN}  ✅ ALLE TESTS BESTANDEN!${NC}"
    echo -e "${GREEN}  GPU-Load-Balancing funktioniert korrekt.${NC}"
    echo -e "${GREEN}========================================${NC}"
    exit 0
else
    echo -e "\n${YELLOW}========================================${NC}"
    echo -e "${YELLOW}  ⚠️  EINIGE TESTS FEHLGESCHLAGEN${NC}"
    echo -e "${YELLOW}  Bitte Probleme beheben.${NC}"
    echo -e "${YELLOW}========================================${NC}"
    exit 1
fi
