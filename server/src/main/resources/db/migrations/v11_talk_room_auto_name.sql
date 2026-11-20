BEGIN;
-- 2026-09-17 사용자 요구사항: 방 이름을 한 번도 수동으로 바꾼 적이 없으면 인원 추가/삭제 시마다
-- 자동 명명 규칙으로 이름을 다시 계산한다. 한 번이라도 수동으로 바꿨으면 그 뒤로는 인원 변동과
-- 무관하게 이름을 그대로 유지한다 — 이 플래그로 두 경우를 구분한다.
ALTER TABLE talk_rooms ADD COLUMN IF NOT EXISTS name_is_custom BOOLEAN NOT NULL DEFAULT FALSE;
COMMIT;
