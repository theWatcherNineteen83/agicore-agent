#!/bin/bash
# Debug-Skript - testet Ollama direkt

echo "=== Test 1: Ollama Binary ==="
which ollama
ollama --version

echo ""
echo "=== Test 2: Ollama manuell starten (Port 11434) ==="
/usr/local/bin/ollama serve --port 11434 --model qwen3.6:35b-a3b-q4_K_M &
OLLAMA_PID=$!
sleep 5

echo "Ollama PID: $OLLAMA_PID"
echo ""

# Test Port
echo "=== Test 3: Port 11434 testen ==="
curl -s http://localhost:11434/api/tags | jq -r '.models[0].name' 2>/dev/null || echo "FEHLER auf Port 11434"

# GPU prüfen
echo ""
echo "=== Test 4: GPU-Status ==="
rocm-smi --showuse --showmeminfo vram 2>/dev/null | grep -E "GPU\[|VRAM Total Used"

# Ollama beenden
kill $OLLAMA_PID 2>/dev/null || true
wait $OLLAMA_PID 2>/dev/null || true

echo ""
echo "=== Test 5: Mit HIP_VISIBLE_DEVICES ==="
HIP_VISIBLE_DEVICES=1 /usr/local/bin/ollama serve --port 11434 --model qwen3.6:35b-a3b-q4_K_M &
OLLAMA_PID=$!
sleep 5

echo "Ollama PID: $OLLAMA_PID"
echo ""

# Test Port
echo "=== Test 6: Port 11434 mit GPU1 testen ==="
curl -s http://localhost:11434/api/tags | jq -r '.models[0].name' 2>/dev/null || echo "FEHLER auf Port 11434"

# GPU prüfen
echo ""
echo "=== Test 7: GPU-Status ==="
rocm-smi --showuse --showmeminfo vram 2>/dev/null | grep -E "GPU\[|VRAM Total Used"

# Ollama beenden
kill $OLLAMA_PID 2>/dev/null || true
wait $OLLAMA_PID 2>/dev/null || true

echo ""
echo "=== FERTIG ==="
