-- 오소리 메신저 DB 마이그레이션 v3 — 조직도 도메인 추가
-- 적용 시점: 2026-04-15
--
-- 적용 방법:
--   psql -h localhost -U postgres -d osori -f v3_add_org.sql
--   psql -h localhost -U postgres -d osori_test -f v3_add_org.sql
--
-- 안전성:
--   - 모든 DDL 이 IF NOT EXISTS 가드 → 멱등.
--   - 기존 auth_users / msg_* / talk_* / file_* 테이블에 영향 없음.
--   - DROP/ALTER 없음. 새 테이블 2 + 인덱스 3 만 추가.
--
-- 롤백:
--   DROP TABLE IF EXISTS org_members;
--   DROP TABLE IF EXISTS org_units;

CREATE TABLE IF NOT EXISTS org_units (
    path        TEXT        PRIMARY KEY,
    parent_path TEXT        REFERENCES org_units(path) ON DELETE CASCADE,
    name        TEXT        NOT NULL,
    depth       INT         NOT NULL DEFAULT 0,
    sort_order  INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_org_units_parent ON org_units(parent_path);
CREATE INDEX IF NOT EXISTS idx_org_units_depth ON org_units(depth);

CREATE TABLE IF NOT EXISTS org_members (
    user_id    TEXT        PRIMARY KEY REFERENCES auth_users(user_id) ON DELETE CASCADE,
    org_path   TEXT        REFERENCES org_units(path) ON DELETE SET NULL,
    email      TEXT        NOT NULL DEFAULT '',
    phone      TEXT        NOT NULL DEFAULT '',
    mobile     TEXT        NOT NULL DEFAULT '',
    title      TEXT        NOT NULL DEFAULT '',
    sort_order INT         NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_org_members_org_path ON org_members(org_path);
