# 오소리 메신저 Docker 구성

## 파일

| 파일 | 용도 | 비고 |
|---|---|---|
| `docker-compose.yml` | 개발용 — Postgres 단독 | 기존, 로컬 서버 개발 시 DB만 띄울 때 |
| `docker-compose.prod.yml` | 운영 — Postgres + Server + pgbackup | |
| `Dockerfile.server` | 서버 멀티스테이지 빌드 | builder(JDK) → runtime(JRE) |
| `pgbackup-entrypoint.sh` | pg_dump 일일 백업 sidecar 진입점 | |
| `.env.example` | 운영 환경변수 템플릿 | `.env` 로 복사 후 채워서 사용 |
| `TLS.md` | 정식 인증서 발급/적용 가이드 | |
| `grafana-osori-dashboard.json` | Grafana 대시보드 템플릿 (Prometheus 데이터소스 가정) | |

## 개발용 (Postgres 만)

`bash
docker compose -f docker/docker-compose.yml up -d
# 서버는 호스트에서 `./gradlew run` 으로 기동
`

## 운영용 (전체 스택)

`bash
# 1) 환경변수 채우기
cp docker/.env.example docker/.env
# .env 안 OSORI_DB_PASS, OSORI_TLS_ENABLED 등 실제 값으로 편집

# 2) TLS 사용한다면: docker/certs/keystore.jks 위치 (TLS.md 참고)

# 3) 기동
docker compose -f docker/docker-compose.prod.yml --env-file docker/.env up -d

# 4) 상태 확인
docker compose -f docker/docker-compose.prod.yml ps
docker logs osori-server -f
docker logs osori-pgbackup -f
`

기동 후:
- 클라 접속: `ws://server-host:7070/ws` 또는 `wss://server-host:7443/ws`
- 서버 로그: `docker/logs/osori-server.log` (회전 백업도 함께)
- DB 백업: `docker/backups/osori-YYYYMMDD-HHMMSS.dump` (매일 03:15 KST 자동)
- 백업 복원: `docker exec -i osori-postgres pg_restore -U osori -d osori_restore < backups/osori-YYYYMMDD-HHMMSS.dump`

## 업데이트 (새 버전 배포)

`bash
git pull
docker compose -f docker/docker-compose.prod.yml --env-file docker/.env build server
docker compose -f docker/docker-compose.prod.yml --env-file docker/.env up -d server
server` 만 재기동 — postgres / pgbackup 은 유지. DB 스키마 변경 있으면 schema.sql 또는 별도 migration.

## 트러블슈팅

| 증상 | 1차 조치 |
|---|---|
| `osori-server` 가 `unhealthy` | `docker logs osori-server --tail=200` |
| `pg_dump: connection failed` | `osori-pgbackup` 에 `OSORI_DB_PASS` 가 server 와 같은지 확인 |
| 디스크 부족 | `docker/backups/` 또는 `docker/logs/` 정리, `BACKUP_RETAIN_DAYS` 조정 |
| TLS 핸드셰이크 실패 | `TLS.md` 트러블슈팅 표 |

## 보안 권고

- `.env` 는 절대 git 에 커밋 금지 (`.gitignore` 에 등록 확인)
- `osori-pgdata` 와 `docker/backups/` 는 호스트 디스크 암호화 권장
- `OSORI_BIND_HOST` 를 `127.0.0.1` 로 두고 nginx 앞단 + 방화벽으로 외부 노출 통제 권장
