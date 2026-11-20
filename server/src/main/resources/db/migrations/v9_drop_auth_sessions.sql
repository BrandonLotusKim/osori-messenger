-- v9: auth_sessions 죽은 테이블 제거.
-- 실제 로그인 세션은 SessionManager(in-memory ConcurrentHashMap)로만 관리되며,
-- 이 테이블은 스키마/필수테이블 체크에만 존재하고 실제 insert/select/update
-- 코드가 서버 어디에도 없었음(2026-09-11 grep으로 확인).

DROP TABLE IF EXISTS auth_sessions;
