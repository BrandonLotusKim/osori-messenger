-- v8: v7 리네이밍(테이블/컬럼)에서 빠졌던 제약(PK/FK) 이름 정리.
-- ALTER TABLE ... RENAME TO 는 제약/인덱스 이름까지 바꾸지 않으므로 뒤늦게 정리.

-- 대화(talk) 쪽 — 옛 msg_* 제약명 잔재
ALTER TABLE talk_messages RENAME CONSTRAINT msg_messages_pkey TO talk_messages_pkey;
ALTER TABLE talk_outbox RENAME CONSTRAINT msg_outbox_pkey TO talk_outbox_pkey;
ALTER TABLE talk_outbox RENAME CONSTRAINT msg_outbox_message_id_fkey TO talk_outbox_talk_id_fkey;

-- 쪽지(message) 쪽 — 옛 memo(s)_* 제약명 잔재
ALTER TABLE messages RENAME CONSTRAINT memos_pkey TO messages_pkey;
ALTER TABLE message_recipients RENAME CONSTRAINT memo_recipients_pkey TO message_recipients_pkey;
ALTER TABLE message_recipients RENAME CONSTRAINT memo_recipients_server_memo_id_fkey TO message_recipients_server_message_id_fkey;
