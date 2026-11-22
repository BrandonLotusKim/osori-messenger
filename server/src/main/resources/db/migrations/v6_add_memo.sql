-- 오소리 메신저 DB 마이그레이션 v6 — 쪽지 도메인 (1:N 비대화형 메시지)
-- 적용 시점: 2026-05-07
--
-- 적용 방법:
--   psql -h localhost -U postgres -d osori      -f v6_add_memo.sql
--   psql -h localhost -U postgres -d osori_test -f v6_add_memo.sql
--
-- 안전성:
--   - IF NOT EXISTS 가드 → 멱등.
--   - 기존 테이블 DROP/ALTER 없음. 신규 2개(memos, message_recipients) + 인덱스 4.
--
-- 스키마 의도:
--   - memos: 쪽지 본문은 1행. 발송자/제목/본문/첨부 옵션.
--   - message_recipients: 1:N 발송 — 한 쪽지에 여러 수신자. 각 수신자별로 read_at/delivered_at/deleted_at 독립.
--   - 받은쪽지함 = message_recipients(recipient_id=me) JOIN messages.
--   - 보낸쪽지함 = memos(from_user=me) + 모든 recipient 정보 집계.
--   - 오프라인 누적: delivered_at IS NULL 인 행이 다음 로그인 시 푸시 대상.
--   - 발송자 측 삭제 / 수신자 측 삭제 분리 (deleted_at_by_sender, message_recipients.deleted_at).
--
-- 롤백:
--   DROP TABLE IF EXISTS message_recipients;
--   DROP TABLE IF EXISTS memos;

CREATE TABLE IF NOT EXISTS memos (
    server_message_id        TEXT PRIMARY KEY,
    from_user_id          TEXT        NOT NULL,
    title                 TEXT        NOT NULL,
    body                  TEXT        NOT NULL,
    sent_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    attachment_upload_id  TEXT,
    deleted_at_by_sender  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_messages_from_user ON memos(from_user_id) WHERE deleted_at_by_sender IS NULL;
CREATE INDEX IF NOT EXISTS idx_messages_sent_at_desc ON memos(sent_at DESC);

CREATE TABLE IF NOT EXISTS message_recipients (
    server_message_id  TEXT        NOT NULL,
    recipient_id    TEXT        NOT NULL,
    delivered_at    TIMESTAMPTZ,
    read_at         TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,
    PRIMARY KEY (server_message_id, recipient_id),
    FOREIGN KEY (server_message_id) REFERENCES memos(server_message_id) ON DELETE CASCADE
);

-- 받은쪽지함 — 미읽음 빠른 조회
CREATE INDEX IF NOT EXISTS idx_message_recipients_unread ON message_recipients(recipient_id, read_at) WHERE read_at IS NULL AND deleted_at IS NULL;
-- 다음 로그인 시 미배달 푸시 대상
CREATE INDEX IF NOT EXISTS idx_message_recipients_undelivered ON message_recipients(recipient_id) WHERE delivered_at IS NULL AND deleted_at IS NULL;
