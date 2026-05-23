#!/bin/bash
# deploy-metis.sh — Deploys Metis to miniedi as a systemd service
set -e

MINIEDI="miniedi"
MINIEDI_HOST="192.168.22.204"
DEPLOY_DIR="/home/prometheus/metis"
API_PORT="11735"
PLANNING_MODEL="mistral-small3.1:24b"
MUTATION_MODEL="qwen3.6:27b-q4_K_M"

echo "═══ Metis Deployment ═══"
echo "Target:  $MINIEDI_HOST:$DEPLOY_DIR"
echo "API:     port $API_PORT"
echo "Planner: $PLANNING_MODEL"
echo "Mutator: $MUTATION_MODEL"
echo ""

# 1. Build fat JAR
echo "[1/5] Building fat JAR..."
cd /home/admini/.openclaw/workspace/agicore
export PATH="$HOME/.local/share/maven/bin:$PATH"
mvn package -DskipTests -q
JAR="agicore-modules/target/metis-agent.jar"
echo "  ✓ $JAR ($(du -h $JAR | cut -f1))"

# 2. Create deploy dir on miniedi
echo "[2/5] Creating deploy directory on miniedi..."
ssh $MINIEDI "mkdir -p $DEPLOY_DIR"

# 3. Copy JAR
echo "[3/5] Copying JAR to miniedi..."
scp $JAR $MINIEDI:$DEPLOY_DIR/metis-agent.jar
echo "  ✓ Copied"

# 4. Create systemd service
echo "[4/5] Installing systemd service..."
ssh $MINIEDI "cat > /tmp/metis-agent.service << 'SERVICE_EOF'
[Unit]
Description=Metis AGI — Self-Evolving Agent System
After=network-online.target ollama.service
Wants=network-online.target

[Service]
Type=simple
User=prometheus
WorkingDirectory=$DEPLOY_DIR
ExecStart=/usr/bin/java \\
    -Xmx2g \\
    -XX:+UseZGC \\
    -jar $DEPLOY_DIR/metis-agent.jar \\
    --api-port $API_PORT \\
    --planning-model $PLANNING_MODEL \\
    --mutation-model $MUTATION_MODEL \\
    --interval 5000 \\
    --persist $DEPLOY_DIR/agent-state.json
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=metis-agent

# Safety: no evolution at startup (enable manually with --evolution)
# Add --evolution after verifying stability

[Install]
WantedBy=multi-user.target
SERVICE_EOF"

# 5. Enable and start
echo "[5/5] Enabling and starting service..."
ssh $MINIEDI "sudo mv /tmp/metis-agent.service /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable metis-agent && sudo systemctl restart metis-agent"

echo ""
echo "═══ Deployed! ═══"
echo "Status:  ssh $MINIEDI systemctl status metis-agent"
echo "Logs:    ssh $MINIEDI journalctl -u metis-agent -f"
echo "API:     http://$MINIEDI_HOST:$API_PORT"
echo ""
echo "OpenWebUI: Add Ollama connection → http://$MINIEDI_HOST:$API_PORT"
echo ""
echo "Enable evolution:"
echo "  ssh $MINIEDI sudo systemctl edit metis-agent"
echo "  Add: --evolution"
echo "  Then: sudo systemctl restart metis-agent"
