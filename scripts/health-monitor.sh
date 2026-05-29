#!/bin/bash
# Metis Health Monitor — alle 5 Min via Cron
# Prüft Metis-API + Ollama-Status und sendet Telegram-Alert bei Anomalien.
#
# Installation:
#   crontab -e
#   */5 * * * * /home/prometheus/metis/health-monitor.sh >> /home/prometheus/metis/health-monitor.log 2>&1

set -e

TELEGRAM_BOT_TOKEN="833414…nVLU"
TELEGRAM_CHAT_ID="265324594"
METIS_API="http://localhost:11735/api/status"
OLLAMA_PS="http://localhost:11434/api/ps"
TMPDIR="/tmp/metis-health"
STATE_FILE="$TMPDIR/state.json"

mkdir -p "$TMPDIR"

# ── Metis API Check ──
METIS_RESPONSE=$(curl -s --max-time 10 "$METIS_API" 2>/dev/null || echo "")
if [ -z "$METIS_RESPONSE" ]; then
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) [CRITICAL] Metis API DOWN — no response" >> "$STATE_FILE"
    curl -s -X POST "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage" \
        -d "chat_id=$TELEGRAM_CHAT_ID" \
        -d "text=🔴 Metis API antwortet nicht!" > /dev/null
    exit 1
fi

# Metis Metriken parsen
SUCCESS=$(echo "$METIS_RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('successRate',0))" 2>/dev/null || echo "0")
FALLBACKS=$(echo "$METIS_RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('modelFallbackUses',999))" 2>/dev/null || echo "999")
TICKS=$(echo "$METIS_RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('totalTicks',-1))" 2>/dev/null || echo "-1")

ALERT=""

# Success-Rate < 80%
if [ "$(echo "$SUCCESS < 0.8" | bc -l 2>/dev/null || echo 0)" = "1" ]; then
    ALERT="${ALERT}⚠️ Success-Rate: $(echo "$SUCCESS * 100" | bc -l 2>/dev/null | cut -d. -f1)%\n"
fi

# Fallback-Storm (>10)
if [ "$FALLBACKS" -gt 10 ] 2>/dev/null; then
    ALERT="${ALERT}⚠️ Fallback-Storm: $FALLBACKS Fallbacks\n"
fi

# Stale (Ticks < 10 seit letztem Check)
LAST_TICKS=$(cat "$TMPDIR/last_ticks" 2>/dev/null || echo "$TICKS")
echo "$TICKS" > "$TMPDIR/last_ticks"

if [ "$TICKS" = "$LAST_TICKS" ] && [ -n "$TICKS" ]; then
    ALERT="${ALERT}⚠️ Ticks stagnant: $TICKS (kein Fortschritt)\n"
fi

# ── Ollama Check ──
OLLAMA_STATUS=$(curl -s --max-time 5 "$OLLAMA_PS" 2>/dev/null || echo "")
if [ -z "$OLLAMA_STATUS" ]; then
    ALERT="${ALERT}🔴 Ollama API DOWN\n"
fi

# ── Watchdog Check ──
if ! systemctl --user is-active metis-watchdog.service > /dev/null 2>&1; then
    ALERT="${ALERT}🔴 Watchdog DOWN\n"
fi

# ── Metis systemd Check ──
if ! systemctl is-active metis.service > /dev/null 2>&1; then
    ALERT="${ALERT}🔴 Metis systemd DOWN\n"
fi

# ── Alert senden ──
if [ -n "$ALERT" ]; then
    TIMESTAMP=$(date '+%H:%M')
    MESSAGE="🩺 Metis Health Alert ($TIMESTAMP):\n$ALERT\n\n💚 OK: Ticks=$TICKS, Success=$(echo "$SUCCESS * 100" | bc -l 2>/dev/null | cut -d. -f1)%, Fallbacks=$FALLBACKS"
    curl -s -X POST "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage" \
        -d "chat_id=$TELEGRAM_CHAT_ID" \
        -d "text=$MESSAGE" > /dev/null
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) [WARN] $ALERT" >> "$TMPDIR/alerts.log"
fi

# ── Heartbeat im Log (nur alle 30 Min) ──
MINUTE=$(date +%M)
if [ "$((10#$MINUTE % 30))" -eq 0 ]; then
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) [OK] Ticks=$TICKS Success=$(echo "$SUCCESS*100"|bc -l|cut -d. -f1)% Fallbacks=$FALLBACKS"
fi
