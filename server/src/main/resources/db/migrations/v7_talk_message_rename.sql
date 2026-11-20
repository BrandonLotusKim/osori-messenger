-- v7 (2026-09-10): 대화 = talk, 쪽지 = message 로 네이밍 통일
-- 기존 DB(데이터 보존)에 적용. 새 DB 는 schema.sql 이 최신 상태를 이미 반영함.
--
-- 적용: psql -h localhost -U osori -d osori -f v7_talk_message_rename.sql
--       psql -h localhost -U osori -d osori_test -f v7_talk_message_rename.sql

BEGIN;

-- ── 대화 1:1 ──
ALTER TABLE msg_messages RENAME TO talk_messages;
ALTER TABLE talk_messages RENAME COLUMN message_id TO talk_id;
ALTER INDEX idx_msg_messages_to_user_ts RENAME TO idx_talk_messages_to_user_ts;
ALTER INDEX idx_msg_messages_from_user  RENAME TO idx_talk_messages_from_user;

ALTER TABLE msg_outbox RENAME TO talk_outbox;
ALTER TABLE talk_outbox RENAME COLUMN message_id TO talk_id;
ALTER INDEX idx_msg_outbox_to_user_delivered RENAME TO idx_talk_outbox_to_user_delivered;
ALTER INDEX idx_msg_outbox_message_id        RENAME TO idx_talk_outbox_talk_id;

-- ── 대화 그룹 ──
ALTER TABLE talk_room_messages RENAME COLUMN room_msg_id TO server_talk_id;

-- ── 쪽지 ──
ALTER TABLE memos RENAME TO messages;
ALTER TABLE messages RENAME COLUMN server_memo_id TO server_message_id;
ALTER INDEX idx_memos_from_user    RENAME TO idx_messages_from_user;
ALTER INDEX idx_memos_sent_at_desc RENAME TO idx_messages_sent_at_desc;

ALTER TABLE memo_recipients RENAME TO message_recipients;
ALTER TABLE message_recipients RENAME COLUMN server_memo_id TO server_message_id;
ALTER INDEX idx_memo_recipients_unread      RENAME TO idx_message_recipients_unread;
ALTER INDEX idx_memo_recipients_undelivered RENAME TO idx_message_recipients_undelivered;

COMMIT;
