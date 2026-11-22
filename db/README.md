# 오소리 메신저 — 데이터베이스 가이드

## 1. 스택

- **DBMS**: PostgreSQL 16
- **드라이버**: `org.postgresql:postgresql:42.7.3`
- **커넥션 풀**: `com.zaxxer:HikariCP:5.1.0`
- **접근 방식**: 순수 JDBC + 경량 Repository 레이어 (MVP). 추후 필요 시 Exposed/jOOQ 이관 검토.

## 2. 스키마 파일

권위 있는 스키마 정의 위치:
```
<repo>\OsoriMessenger\server\src\main\resources\db\schema.sql
```

이 파일은 **"현재 최신 스냅샷"** 만 표현한다. 새 DB 에 한 번에 적용 가능한 상태만 유지한다.
마이그레이션 도구(Flyway 등)를 도입하기 전까지는 이 파일을 단일 소스로 취급한다.

## 3. 테이블 구조

| 테이블 | 설명 |
|---|---|
| `users` | 사용자 계정 (user_id PK, password_hash, display_name, created_at) |
| `sessions` | 로그인 세션 및 재접속 토큰 (session_id UUID PK, user_id FK, device_id, token, last_seen) |
| `messages` | 메시지 본문 영속 저장 (message_id UUID PK, from_user, to_user, text, server_ts) |
| `message_outbox` | 오프라인 수신자용 아웃박스 (id BIGSERIAL PK, message_id FK, to_user, delivered, created_at) |

### 주요 인덱스
- `idx_messages_to_user_ts` — `(to_user, server_ts DESC)` : 수신자 최근 메시지 조회 최적화
- `idx_outbox_to_user_delivered` — `(to_user, delivered) WHERE delivered = false` : 오프라인 수신자 아웃박스 조회 최적화 (부분 인덱스)
- `idx_sessions_user_id`, `idx_sessions_token` — 세션 조회용

### 분리 정책
- 논리적으로 **"메타데이터 DB"** (`users`, `sessions`) 와 **"메시지 본문 DB"** (`messages`, `message_outbox`) 를 구분한다.
- MVP 단계에서는 동일 Postgres 인스턴스 안의 동일 스키마에 테이블로만 분리.
- 운영 단계에서 메시지 본문 테이블을 별도 인스턴스로 이관 가능하도록 **FK 제약을 교차 테이블 사이에는 걸지 않음** (예: `messages.from_user` 는 `users.user_id` 를 참조하지 않음).
- `message_outbox → messages` 의 FK 는 동일 DB 내 관계이므로 유지.

## 4. 로컬 기동 (Docker)

```bash
cd <repo>\OsoriMessenger
docker compose -f docker/docker-compose.yml up -d
```

컨테이너 최초 기동 시 `docker-entrypoint-initdb.d` 훅으로 `schema.sql` 이 자동 적용된다.

### 접속 정보 (기본값)
| 항목 | 값 |
|---|---|
| 호스트 | `localhost` |
| 포트 | `5432` |
| DB 이름 | `osori` |
| 사용자 | `osori` |
| 비밀번호 | `osori_dev_pw` |
| JDBC URL | `jdbc:postgresql://localhost:5432/osori` |

### 재초기화 (볼륨 삭제 후 재기동)
```bash
docker compose -f docker/docker-compose.yml down -v
docker compose -f docker/docker-compose.yml up -d
```

## 5. 수동 스키마 적용

Docker 를 쓰지 않고 기존 Postgres 인스턴스에 직접 적용하려면:
```bash
psql -h localhost -U osori -d osori -f server/src/main/resources/db/schema.sql
```

## 6. 서버 DB 연결 환경변수

`server/src/main/kotlin/com/osori/server/db/Database.kt` 에서 사용하는 환경변수:

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `OSORI_DB_URL` | `jdbc:postgresql://localhost:5432/osori` | JDBC URL |
| `OSORI_DB_USER` | `osori` | 사용자 |
| `OSORI_DB_PASS` | `osori_dev_pw` | 비밀번호 |
| `OSORI_DB_POOL_SIZE` | `10` | HikariCP 최대 풀 사이즈 |
| `OSORI_DB_ENABLED` | `true` | `false` 로 설정 시 DB 없이 메모리 전용 모드로 기동 |

**운영 배포 시 비밀번호는 반드시 환경변수/시크릿 스토어로 주입**. 기본값은 로컬 개발 전용이다.

## 7. 메모리 전용 모드

`OSORI_DB_ENABLED=false` 로 기동하면 DB 없이 Phase 2 MVP 와 동일한 동작을 한다.
- `LoginHandler` 는 사용자 존재 확인 없이 통과
- `MessageRouter` 는 메시지를 영속화하지 않음
- 디버깅/단위 테스트/Phase 2 성공 기준 재현 용도

## 8. 향후 마이그레이션

- **도입 후보**: Flyway 또는 Liquibase
- **이관 시**: 현재 `schema.sql` 전체 내용을 `db/migration/V1__init.sql` 으로 복사하고, 이후 변경은 `V2__*.sql`, `V3__*.sql` 로 누적
- **현재 `schema.sql` 의 위치**: 마이그레이션 도구 도입 시 이 파일은 읽기 전용 "누적 스키마 스냅샷" 으로 역할 변경 (자동 빌드로 생성하는 방식도 검토)

## 9. 단위 테스트용 주의

- `Database.fromEnv` 는 실제 Postgres 에 접속하므로 단위 테스트에서는 사용하지 않는다.
- Repository 단위 테스트는 H2 in-memory (`MODE=PostgreSQL`) 또는 Testcontainers Postgres 를 도입 검토 (현재 MVP 에는 미포함).
- `MessageRouter` / `LoginHandler` 단위 테스트는 Repository 파라미터에 `null` 을 넘겨 메모리 모드로 검증한다.
