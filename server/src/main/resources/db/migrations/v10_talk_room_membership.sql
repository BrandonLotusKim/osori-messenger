-- v10 (2026-09-16): 대화(그룹) 멤버 관리 기능 지원
--   - 방이 0명이 되어 삭제되더라도 대화 기록(talk_room_messages)은 보존해야 하므로
--     room_id FK 를 CASCADE 삭제에서 SET NULL 로 변경(그러려면 컬럼도 nullable).
--   - 동일 구성원 방 중복 생성 감지를 위한 member_set_hash 컬럼 + 인덱스 추가.
--
-- 적용: psql -h localhost -U osori -d osori -f v10_talk_room_membership.sql
--       psql -h localhost -U osori -d osori_test -f v10_talk_room_membership.sql

BEGIN;

ALTER TABLE talk_room_messages ALTER COLUMN room_id DROP NOT NULL;
ALTER TABLE talk_room_messages DROP CONSTRAINT talk_room_messages_room_id_fkey;
ALTER TABLE talk_room_messages
    ADD CONSTRAINT talk_room_messages_room_id_fkey
    FOREIGN KEY (room_id) REFERENCES talk_rooms(room_id) ON DELETE SET NULL;

ALTER TABLE talk_rooms ADD COLUMN IF NOT EXISTS member_set_hash TEXT;
CREATE INDEX IF NOT EXISTS idx_talk_rooms_member_hash ON talk_rooms(member_set_hash);

COMMIT;
