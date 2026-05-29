#!/bin/bash
# Metis Config Backup — sichert systemd units + aktuelle Config ins Repo
set -e
REPO=/home/prometheus/metis-agent-repo
BACKUP_DIR=$REPO/config-backup
mkdir -p $BACKUP_DIR

# Systemd units sichern
cp /etc/systemd/system/metis.service $BACKUP_DIR/metis.service
cp /home/prometheus/.config/systemd/user/metis-watchdog.service $BACKUP_DIR/metis-watchdog.service

# Aktuelle Modell-Registry per API abfragen
curl -s --max-time 5 http://localhost:11735/api/status > $BACKUP_DIR/status-$(date +%Y%m%d-%H%M).json 2>/dev/null || true

# Commit + Push
cd $REPO
git add config-backup/
git diff --cached --quiet || git commit -m "config: auto-backup $(date +%Y%m%d-%H%M)"
git push origin master 2>&1 || echo 'Push fehlgeschlagen (nicht kritisch)'
