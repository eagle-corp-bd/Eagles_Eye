#!/bin/bash
# Full project backup — saves inside EaglesEye/backup/
PROJECT_DIR="/storage/internal_new/project"
BACKUP_DIR="$PROJECT_DIR/EaglesEye/backup"
mkdir -p "$BACKUP_DIR"
cd "$PROJECT_DIR" || exit 1
DATE=$(date +"%Y%m%d_%H%M%S")
OUT="EaglesEye/backup/EaglesEye_${DATE}.tar.gz"
tar -czf "$OUT" --exclude='app/build' --exclude='.gradle' --exclude='backup/*' EaglesEye
echo "Backup saved: $PROJECT_DIR/$OUT"
ls -lh "$PROJECT_DIR/$OUT"
