-- E2E 검증용 조직도 샘플 데이터
-- 사용법: psql -U postgres -h localhost -d osori -f seed-org-sample.sql

TRUNCATE org_members, org_units CASCADE;

INSERT INTO org_units(path, parent_path, name, depth, sort_order) VALUES
  ('오소리랩', NULL, '오소리랩', 0, 0),
  ('오소리랩/연구개발본부', '오소리랩', '연구개발본부', 1, 0),
  ('오소리랩/사업본부', '오소리랩', '사업본부', 1, 1),
  ('오소리랩/연구개발본부/플랫폼개발팀', '오소리랩/연구개발본부', '플랫폼개발팀', 2, 0);

INSERT INTO auth_users(user_id, display_name) VALUES ('alice', 'alice') ON CONFLICT DO NOTHING;
INSERT INTO auth_users(user_id, display_name) VALUES ('bob', 'bob') ON CONFLICT DO NOTHING;
INSERT INTO auth_users(user_id, display_name) VALUES ('charlie', 'charlie') ON CONFLICT DO NOTHING;

INSERT INTO org_members(user_id, org_path, email, phone, mobile, title, sort_order) VALUES
  ('alice', '오소리랩/연구개발본부/플랫폼개발팀', 'alice@example.com', '02-1234', '010-1111', '선임연구원', 1),
  ('bob', '오소리랩/사업본부', 'bob@example.com', '02-5678', '010-2222', '과장', 1),
  ('charlie', '오소리랩/연구개발본부', 'charlie@example.com', '', '010-3333', '책임연구원', 2)
ON CONFLICT (user_id) DO UPDATE SET
  org_path = EXCLUDED.org_path,
  email    = EXCLUDED.email,
  phone    = EXCLUDED.phone,
  mobile   = EXCLUDED.mobile,
  title    = EXCLUDED.title,
  sort_order = EXCLUDED.sort_order;

SELECT COUNT(*) AS units FROM org_units;
SELECT COUNT(*) AS members FROM org_members;
