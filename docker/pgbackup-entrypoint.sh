#!/usr/bin/env bash
# pg_dump 일일 백업 sidecar
#
# 매일 03:15 KST 에 /backups/osori-YYYYMMDD.dump 로 백업 + 보존 기간(BACKUP_RETAIN_DAYS) 초과분 삭제.
# 의도적으로 cron 없이 sleep loop — Docker 이미지를 가볍게 유지.

set -euo pipefail

BACKUP_DIR=/backups
RETAIN=${BACKUP_RETAIN_DAYS:-14}

mkdir -p "$BACKUP_DIR"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S %Z')] $*"; }

run_backup() {
  local stamp
  stamp=$(date '+%Y%m%d-%H%M%S')
  local file="$BACKUP_DIR/osori-$stamp.dump"
  log "백업 시작 → $file"
  if pg_dump -F c -f "$file"; then
    log "백업 완료 ($(du -h "$file" | cut -f1))"
  else
    log "ERROR: pg_dump 실패"
    rm -f "$file"
    return 1
  fi

  # 보존 기간 초과 분 삭제
  log "$RETAIN 일 초과 파일 정리 중..."
  find "$BACKUP_DIR" -maxdepth 1 -type f -name 'osori-*.dump' -mtime +"$RETAIN" -print -delete || true
}

# 컨테이너 기동 직후 한 번 실행
log "pgbackup sidecar 기동 (retain=$RETAIN days)"
run_backup || log "초기 백업 실패 — 다음 스케줄에 재시도"

# 매일 03:15 까지 대기 → 백업 → 24시간 대기 반복
while true; do
  now_sec=$(date +%s)
  # 오늘 03:15 KST timestamp
  today_target=$(date -d 'today 03:15' +%s)
  if [ "$now_sec" -ge "$today_target" ]; then
    # 오늘 시각 지났으면 내일 03:15
    target=$(date -d 'tomorrow 03:15' +%s)
  else
    target=$today_target
  fi
  sleep_sec=$((target - now_sec))
  log "다음 백업까지 ${sleep_sec}초 대기 (target=$(date -d "@$target" '+%Y-%m-%d %H:%M:%S %Z'))"
  sleep "$sleep_sec"
  run_backup || log "백업 실패 — 다음 스케줄에 재시도"
done
