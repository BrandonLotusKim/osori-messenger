-- v12 (2026-11-22): 방 파일 전송 + 읽음 처리
--   - file_uploads.room_id        : 방(1:1·그룹)에 올린 파일이면 방 ID. 다운로드 권한은 방 멤버십으로 판단
--   - talk_room_messages.attachment_upload_id : 방 파일을 대화 기록으로 남긴 메시지의 업로드 ID
--   - talk_room_reads             : 방 멤버별 마지막으로 읽은 시각(보낸 메시지의 안 읽은 인원 표시)
-- 여러 번 실행해도 안전하다(IF NOT EXISTS).

ALTER TABLE file_uploads ADD COLUMN IF NOT EXISTS room_id UUID;
CREATE INDEX IF NOT EXISTS idx_file_uploads_room ON file_uploads(room_id) WHERE room_id IS NOT NULL;

ALTER TABLE talk_room_messages ADD COLUMN IF NOT EXISTS attachment_upload_id TEXT;

CREATE TABLE IF NOT EXISTS talk_room_reads (
    room_id      UUID        NOT NULL REFERENCES talk_rooms(room_id) ON DELETE CASCADE,
    user_id      TEXT        NOT NULL,
    last_read_ts TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (room_id, user_id)
);
