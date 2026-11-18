-- 팀 공유: 사용자 · 공간(개인/팀) · 멤버 · 초대
-- 기존 1인용 데이터는 모두 최초 사용자(u-owner)의 개인 공간(sp-owner)으로 옮긴다.
-- u-owner 의 비밀번호는 앱 시작 시 password.hash 파일(또는 dev-password)에서 채운다 (OwnerBootstrap).

CREATE TABLE users (
  id             VARCHAR2(64) PRIMARY KEY,
  login          VARCHAR2(30) NOT NULL,
  name           VARCHAR2(100 CHAR),
  password_hash  VARCHAR2(200),
  admin          NUMBER(1) DEFAULT 0 NOT NULL,
  disabled       NUMBER(1) DEFAULT 0 NOT NULL,
  created_at     VARCHAR2(40),
  CONSTRAINT uq_users_login UNIQUE (login)
);

CREATE TABLE spaces (
  id          VARCHAR2(64) PRIMARY KEY,
  name        VARCHAR2(100 CHAR),
  kind        VARCHAR2(10) NOT NULL,
  color       VARCHAR2(20),
  created_at  VARCHAR2(40),
  CONSTRAINT ck_spaces_kind CHECK (kind IN ('personal', 'team'))
);

CREATE TABLE space_members (
  space_id   VARCHAR2(64) NOT NULL,
  user_id    VARCHAR2(64) NOT NULL,
  role       VARCHAR2(10) NOT NULL,
  joined_at  VARCHAR2(40),
  CONSTRAINT pk_space_members PRIMARY KEY (space_id, user_id),
  CONSTRAINT ck_space_members_role CHECK (role IN ('owner', 'editor', 'viewer'))
);
CREATE INDEX ix_space_members_user ON space_members (user_id);

-- 초대 링크(join) · 비밀번호 재설정 링크(reset). 토큰 원문은 저장하지 않고 SHA-256 만 저장
CREATE TABLE invites (
  token_hash  VARCHAR2(64) PRIMARY KEY,
  kind        VARCHAR2(10) NOT NULL,
  space_id    VARCHAR2(64),
  role        VARCHAR2(10),
  user_id     VARCHAR2(64),
  created_by  VARCHAR2(64),
  created_at  VARCHAR2(40),
  expires_at  VARCHAR2(40),
  used_at     VARCHAR2(40),
  CONSTRAINT ck_invites_kind CHECK (kind IN ('join', 'reset'))
);

-- 팀 대시보드 "최근 활동"
CREATE TABLE activity (
  id        VARCHAR2(64) PRIMARY KEY,
  space_id  VARCHAR2(64) NOT NULL,
  user_id   VARCHAR2(64),
  at        VARCHAR2(40),
  action    VARCHAR2(10),
  coll      VARCHAR2(20),
  item_id   VARCHAR2(64),
  title     VARCHAR2(200 CHAR)
);
CREATE INDEX ix_activity_space ON activity (space_id, at);

INSERT INTO users (id, login, name, admin, created_at) VALUES ('u-owner', 'haru', '관리자', 1, '2026-11-05T00:00:00Z');
INSERT INTO spaces (id, name, kind, color, created_at) VALUES ('sp-owner', '내 공간', 'personal', '#2563eb', '2026-11-05T00:00:00Z');
INSERT INTO space_members (space_id, user_id, role, joined_at) VALUES ('sp-owner', 'u-owner', 'owner', '2026-11-05T00:00:00Z');

-- ───────── 기존 데이터에 공간·작성자·버전 추가 ─────────
ALTER TABLE todos ADD (space_id VARCHAR2(64), assignee_id VARCHAR2(64), created_by VARCHAR2(64), updated_by VARCHAR2(64), version NUMBER DEFAULT 1 NOT NULL);
ALTER TABLE events ADD (space_id VARCHAR2(64), created_by VARCHAR2(64), updated_by VARCHAR2(64), version NUMBER DEFAULT 1 NOT NULL);
ALTER TABLE notes ADD (space_id VARCHAR2(64), created_by VARCHAR2(64), updated_by VARCHAR2(64), version NUMBER DEFAULT 1 NOT NULL);
ALTER TABLE snippets ADD (space_id VARCHAR2(64), created_by VARCHAR2(64), updated_by VARCHAR2(64), version NUMBER DEFAULT 1 NOT NULL);
ALTER TABLE routines ADD (space_id VARCHAR2(64), created_by VARCHAR2(64), updated_by VARCHAR2(64), version NUMBER DEFAULT 1 NOT NULL);

UPDATE todos SET space_id = 'sp-owner', created_by = 'u-owner';
UPDATE events SET space_id = 'sp-owner', created_by = 'u-owner';
UPDATE notes SET space_id = 'sp-owner', created_by = 'u-owner';
UPDATE snippets SET space_id = 'sp-owner', created_by = 'u-owner';
UPDATE routines SET space_id = 'sp-owner', created_by = 'u-owner';

CREATE INDEX ix_todos_space ON todos (space_id);
CREATE INDEX ix_events_space ON events (space_id);
CREATE INDEX ix_notes_space ON notes (space_id);
CREATE INDEX ix_snippets_space ON snippets (space_id);
CREATE INDEX ix_routines_space ON routines (space_id);

-- ───────── 루틴 기록: 사람별로 ─────────
CREATE TABLE routine_checks (
  log_date    VARCHAR2(10) NOT NULL,
  routine_id  VARCHAR2(64) NOT NULL,
  user_id     VARCHAR2(64) NOT NULL,
  CONSTRAINT pk_routine_checks PRIMARY KEY (log_date, routine_id, user_id)
);
INSERT INTO routine_checks (log_date, routine_id, user_id) SELECT log_date, routine_id, 'u-owner' FROM routine_log;
DROP TABLE routine_log;

-- ───────── 휴일: 공휴일(scope='*') + 공간별 휴무일(scope=space_id) ─────────
CREATE TABLE day_offs (
  scope  VARCHAR2(64) NOT NULL,
  hdate  VARCHAR2(10) NOT NULL,
  name   VARCHAR2(200 CHAR),
  CONSTRAINT pk_day_offs PRIMARY KEY (scope, hdate)
);
INSERT INTO day_offs (scope, hdate, name) SELECT '*', hdate, name FROM holidays;
DROP TABLE holidays;
