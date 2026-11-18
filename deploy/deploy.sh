#!/bin/bash
# 서버의 /opt/haruhanpan/deploy.sh — 이미지 태그 하나를 받아 배포하고, 실패하면 직전 태그로 되돌린다.
#   GitHub Actions: deploy 계정의 SSH 키는 이 스크립트만 실행할 수 있다 (authorized_keys 의 command=).
#                   ssh deploy@서버 sha-1a2b3c4   → 태그는 SSH_ORIGINAL_COMMAND 로 들어온다
#   직접 실행:      sudo -u deploy /opt/haruhanpan/deploy.sh sha-1a2b3c4
set -euo pipefail
cd /opt/haruhanpan

TAG="${1:-${SSH_ORIGINAL_COMMAND:-}}"
if [[ ! "$TAG" =~ ^(sha-[0-9a-f]{7,40}|latest)$ ]]; then
  echo "잘못된 태그: '$TAG' (sha-<커밋> 또는 latest)" >&2
  exit 2
fi

CURRENT=$(cat current-tag 2>/dev/null || echo "")
log() { echo "[deploy $(date -u +%FT%TZ)] $*" | tee -a deploy.log; }

healthy() {
  for _ in $(seq 1 45); do
    if curl -fsS -m 3 http://127.0.0.1:8765/api/health | grep -q '"db":"ok"'; then return 0; fi
    sleep 2
  done
  return 1
}

up() { TAG="$1" docker compose -f compose.yml up -d --remove-orphans; }

log "배포 시작: $TAG (지금: ${CURRENT:-없음})"
TAG="$TAG" docker compose -f compose.yml pull
up "$TAG"
if healthy; then
  echo "$TAG" > current-tag
  log "배포 완료: $TAG"
  docker image prune -f --filter "until=168h" >/dev/null || true
  exit 0
fi

log "헬스 체크 실패: $TAG"
docker logs --tail 50 haruhanpan 2>&1 | tail -50 || true
if [ -n "$CURRENT" ] && [ "$CURRENT" != "$TAG" ]; then
  log "되돌리기: $CURRENT"
  up "$CURRENT"
  healthy && log "되돌리기 완료: $CURRENT" || log "되돌리기도 실패 — 서버 확인 필요"
fi
exit 1
