-- 오소리 메신저 DB 스키마 (PostgreSQL 16)
--
-- 도메인 네이밍 규칙:
--   auth_*    : 인증/세션
--   talk_*    : 대화 (1:1 + 그룹). "대화" 도메인은 이름에 반드시 talk 가 들어간다.
--   message_* : 쪽지. "쪽지" 도메인은 이름에 message 가 들어가고 talk 는 절대 안 붙인다.
--   file_*    : 파일 첨부
--   org_*     : 조직도
--   log_*     : 감사 로그
--   push_*    : 푸시 토큰
--
-- 적용: psql -h localhost -U osori -d osori -f schema.sql
--
-- 변경 이력:
--   - v1~v6: users/sessions/messages, 도메인 접두사, 조직도, 감사, 푸시, 쪽지
--   - v7 (2026-09-10): 대화 = talk, 쪽지 = message 로 네이밍 통일
--     msg_messages→talk_messages(컬럼 message_id→talk_id), msg_outbox→talk_outbox,
--     talk_room_messages.room_msg_id→server_talk_id, memos→messages, memo_recipients→message_recipients
--   - v9 (2026-09-11): auth_sessions 제거 — 실제 세션은 SessionManager(in-memory)로만
--     관리되고 이 테이블은 쓰기/읽기 코드가 전혀 없던 죽은 테이블이었음
--   - v10 (2026-09-16): 대화(그룹) 멤버 관리(초대/나가기) 지원 — talk_room_messages.room_id
--     nullable + ON DELETE SET NULL(방이 삭제돼도 대화 기록은 보존), talk_rooms.member_set_hash
--     추가(동일 구성원 방 중복 생성 감지용)
--   - v11 (2026-09-17): talk_rooms.name_is_custom 추가 — 수동으로 이름을 바꾼 적 없는 방은
--     인원 추가/삭제 때마다 자동 명명 규칙으로 이름을 재계산


-- ============================================================================
-- auth_users — 사용자 계정
-- ============================================================================
CREATE TABLE IF NOT EXISTS auth_users (
    user_id       TEXT        PRIMARY KEY,
    password_hash TEXT        NOT NULL DEFAULT '',
    display_name  TEXT        NOT NULL DEFAULT '',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);


-- ============================================================================
-- talk_messages — 대화(1:1) 본문
-- ============================================================================
CREATE TABLE IF NOT EXISTS talk_messages (
    talk_id    UUID        PRIMARY KEY,
    from_user  TEXT        NOT NULL,
    to_user    TEXT        NOT NULL,
    text       TEXT        NOT NULL,
    server_ts  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_talk_messages_to_user_ts ON talk_messages(to_user, server_ts DESC);
CREATE INDEX IF NOT EXISTS idx_talk_messages_from_user  ON talk_messages(from_user);


-- ============================================================================
-- talk_outbox — 대화(1:1) 오프라인 수신자용 아웃박스
-- ============================================================================
CREATE TABLE IF NOT EXISTS talk_outbox (
    id         BIGSERIAL   PRIMARY KEY,
    talk_id    UUID        NOT NULL REFERENCES talk_messages(talk_id) ON DELETE CASCADE,
    to_user    TEXT        NOT NULL,
    delivered  BOOLEAN     NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_talk_outbox_to_user_delivered ON talk_outbox(to_user, delivered) WHERE delivered = false;
CREATE INDEX IF NOT EXISTS idx_talk_outbox_talk_id           ON talk_outbox(talk_id);


-- ============================================================================
-- talk_rooms — 대화(그룹) 방
-- ============================================================================
CREATE TABLE IF NOT EXISTS talk_rooms (
    room_id          UUID        PRIMARY KEY,
    name             TEXT        NOT NULL,
    owner_user_id    TEXT        NOT NULL REFERENCES auth_users(user_id) ON DELETE CASCADE,
    -- 현재 멤버 UserId 집합(정렬 후 해시)을 캐싱 — "동일 구성원 방 이미 있음" 감지를 인덱스로
    -- O(1) 조회하기 위함. 멤버 추가/삭제 때마다 TalkRoomRepository 가 재계산해서 갱신한다.
    member_set_hash  TEXT,
    -- 방 이름을 한 번이라도 수동으로 바꾼 적이 있으면 true — 그 뒤로는 인원 추가/삭제와 무관하게
    -- 이름을 그대로 유지한다. false 인 동안은 인원이 바뀔 때마다 자동 명명 규칙으로 재계산된다.
    name_is_custom   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_talk_rooms_owner ON talk_rooms(owner_user_id);
CREATE INDEX IF NOT EXISTS idx_talk_rooms_member_hash ON talk_rooms(member_set_hash);


-- ============================================================================
-- talk_room_members — 방 참여자
-- ============================================================================
CREATE TABLE IF NOT EXISTS talk_room_members (
    room_id   UUID        NOT NULL REFERENCES talk_rooms(room_id) ON DELETE CASCADE,
    user_id   TEXT        NOT NULL REFERENCES auth_users(user_id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_talk_room_members_user ON talk_room_members(user_id);


-- ============================================================================
-- talk_room_messages — 방 안 대화
-- ============================================================================
CREATE TABLE IF NOT EXISTS talk_room_messages (
    server_talk_id UUID        PRIMARY KEY,
    -- nullable + ON DELETE SET NULL: 멤버가 모두 나가서 방(talk_rooms row)이 삭제돼도
    -- 대화 기록 자체는 지우지 않고 보존한다(요구사항: "방/멤버십 데이터는 삭제, 대화 기록은 보존").
    room_id        UUID        REFERENCES talk_rooms(room_id) ON DELETE SET NULL,
    from_user_id   TEXT        NOT NULL,
    text           TEXT        NOT NULL,
    server_ts      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_talk_room_messages_room_ts ON talk_room_messages(room_id, server_ts DESC);


-- ============================================================================
-- file_uploads — 파일 전송
-- ============================================================================
CREATE TABLE IF NOT EXISTS file_uploads (
    upload_id     UUID        PRIMARY KEY,
    from_user_id  TEXT        NOT NULL REFERENCES auth_users(user_id) ON DELETE CASCADE,
    to_user_id    TEXT        NOT NULL,
    filename      TEXT        NOT NULL,
    mime_type     TEXT        NOT NULL DEFAULT '',
    total_size    BIGINT      NOT NULL,
    bytes_written BIGINT      NOT NULL DEFAULT 0,
    status        TEXT        NOT NULL DEFAULT 'open',
    storage_path  TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_file_uploads_from_user ON file_uploads(from_user_id);
CREATE INDEX IF NOT EXISTS idx_file_uploads_to_user ON file_uploads(to_user_id);


-- ============================================================================
-- org_units — 조직 부서 트리
-- ============================================================================
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


-- ============================================================================
-- org_members — 사용자 조직도 메타
-- ============================================================================
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


-- ============================================================================
-- log_events — 감사 로그
-- ============================================================================
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


-- ============================================================================
-- push_tokens — 푸시 알림 디바이스 토큰
-- ============================================================================
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


-- ============================================================================
-- 쪽지 (Message) —. 1행=쪽지 본문, 1:N message_recipients.
-- ============================================================================
CREATE TABLE IF NOT EXISTS messages (
    server_message_id     TEXT PRIMARY KEY,
    from_user_id          TEXT        NOT NULL,
    title                 TEXT        NOT NULL,
    body                  TEXT        NOT NULL,
    sent_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    attachment_upload_id  TEXT,
    deleted_at_by_sender  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_messages_from_user ON messages(from_user_id) WHERE deleted_at_by_sender IS NULL;
CREATE INDEX IF NOT EXISTS idx_messages_sent_at_desc ON messages(sent_at DESC);

CREATE TABLE IF NOT EXISTS message_recipients (
    server_message_id  TEXT        NOT NULL,
    recipient_id       TEXT        NOT NULL,
    delivered_at       TIMESTAMPTZ,
    read_at            TIMESTAMPTZ,
    deleted_at         TIMESTAMPTZ,
    PRIMARY KEY (server_message_id, recipient_id),
    FOREIGN KEY (server_message_id) REFERENCES messages(server_message_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_message_recipients_unread ON message_recipients(recipient_id, read_at) WHERE read_at IS NULL AND deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_message_recipients_undelivered ON message_recipients(recipient_id) WHERE delivered_at IS NULL AND deleted_at IS NULL;
