-- 오소리 메신저 DB 마이그레이션 v4 — 감사 로그 도메인
-- 적용 시점: 2026-04-16
--
-- 적용 방법:
--   psql -h localhost -U postgres -d osori      -f v4_add_audit.sql
--   psql -h localhost -U postgres -d osori_test -f v4_add_audit.sql
--
-- 안전성:
--   - IF NOT EXISTS 가드 → 멱등.
--   - 기존 테이블 DROP/ALTER 없음. 새 테이블 1 + 인덱스 2 만.
--
-- 롤백:
--   DROP TABLE IF EXISTS log_events;

CREATE TABLE IF NOT EXISTS log_events (
    event_id       UUID        PRIMARY KEY,
    actor_user_id  TEXT,
    event_type     TEXT        NOT NULL,
    target         TEXT,
    payload        JSONB       NOT NULL DEFAULT '{}'::JSONB,
    server_ts      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_log_events_actor_ts ON log_events(actor_user_id, server_ts DESC);
CREATE INDEX IF NOT EXISTS idx_log_events_type_ts  ON log_events(event_type, server_ts DESC);
