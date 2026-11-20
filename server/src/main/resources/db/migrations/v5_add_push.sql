-- 오소리 메신저 DB 마이그레이션 v5 — 푸시 알림 토큰 도메인
-- 적용 시점: 2026-04-16
--
-- 적용 방법:
--   psql -h localhost -U postgres -d osori      -f v5_add_push.sql
--   psql -h localhost -U postgres -d osori_test -f v5_add_push.sql
--
-- 안전성:
--   - IF NOT EXISTS 가드 → 멱등.
--   - 기존 테이블 DROP/ALTER 없음. 새 테이블 1 + 인덱스 2 만.
--
-- 스키마 의도:
--   - (user_id, device_id) 가 자연키. 한 사용자가 여러 디바이스에 푸시 받을 수 있음.
--   - platform: 'fcm' (Android/Web) | 'apns' (iOS) | 'test' 확장 여지.
--   - token 은 벤더 발급 문자열. 만료/교체 시 같은 디바이스 키로 upsert.
--   - last_seen_at 으로 stale 토큰 청소 가능 (별도 배치).
--   - FK 없음 — 푸시 브리지를 별도 DB 로 뽑을 여지 (관리 평면 분리 설계 원칙).
--
-- 롤백:
--   DROP TABLE IF EXISTS push_tokens;

CREATE TABLE IF NOT EXISTS push_tokens (
    user_id        TEXT        NOT NULL,
    device_id      TEXT        NOT NULL,
    platform       TEXT        NOT NULL,
    token          TEXT        NOT NULL,
    last_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_push_tokens_user ON push_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_push_tokens_token ON push_tokens(token);
