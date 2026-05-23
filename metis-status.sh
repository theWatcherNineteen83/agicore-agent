#!/bin/bash
# metis-status.sh — Zeigt den Lernfortschritt von Metis
# Aufruf: ./metis-status.sh [--watch]

METIS_URL="${METIS_URL:-http://192.168.22.204:11735}"

status() {
    echo "═══ Metis Lernfortschritt — $(date +%H:%M:%S) ═══"
    curl -s "$METIS_URL/api/learned" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(f'Ticks:    {d[\"ticks\"]}')
print(f'Erfolg:   {d[\"successRate\"]:.0%}')
print(f'Sicher:   {d[\"confidence\"]:.0%}')
print(f'Beliefs:  {d[\"beliefCount\"]} ({d[\"avgBeliefConfidence\"]:.0%} sicher)')
if 'llmCalls' in d:
    print(f'LLM:      {d[\"llmCalls\"]} Calls ({d[\"llmSuccessRate\"]:.0%} ok, {d[\"fallbackUses\"]} fb)')
print(f'Evolution: {d[\"evolutionCycles\"]} Zyklen, {d[\"acceptedMutations\"]} acc / {d[\"rejectedMutations\"]} rej')
print()
print('Gelernte Mappings:')
for k,v in d.get('learnedMappings',{}).items():
    bar = '█' * int(v*10) + '░' * (10 - int(v*10))
    print(f'  {k:30s} {bar} {v:.0%}')
print()
print('Top Beliefs:')
for b in d.get('topBeliefs',[])[:5]:
    print(f'  {b[\"confidence\"]:.0%}  {b[\"statement\"][:75]}')
print()
print('Letzte Aktionen:')
for e in d.get('recentExperiences',[])[:5]:
    print(f'  {e[\"action\"]:10s} {\"✓\" if e[\"success\"] else \"✗\"}  {e[\"goal\"][:50]}')
" 2>/dev/null || echo "Metis nicht erreichbar auf $METIS_URL"
    echo ""
}

if [ "$1" = "--watch" ]; then
    while true; do
        clear
        status
        sleep "${2:-30}"
    done
else
    status
fi
