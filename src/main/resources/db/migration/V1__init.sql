-- 하루한판 초기 스키마 (Oracle / H2 MODE=Oracle 공통)
-- 날짜·시각은 화면 로직을 단순하게 하려고 문자열(YYYY-MM-DD, ISO 시각)로 저장한다.
-- bool 은 NUMBER(1) 0/1. Oracle 은 '' 를 NULL 로 저장하므로 조회 시 NULL → '' 로 돌려준다.

CREATE TABLE todos (
  id          VARCHAR2(64)  PRIMARY KEY,
  title       VARCHAR2(1000 CHAR),
  due         VARCHAR2(10),
  priority    VARCHAR2(10),
  tag         VARCHAR2(100 CHAR),
  memo        VARCHAR2(4000 CHAR),
  done        NUMBER(1) DEFAULT 0 NOT NULL,
  done_at     VARCHAR2(40),
  created_at  VARCHAR2(40),
  status      VARCHAR2(10) DEFAULT 'todo' NOT NULL,
  rank_no     NUMBER DEFAULT 0,
  CONSTRAINT ck_todos_status CHECK (status IN ('todo', 'doing', 'done'))
);
CREATE INDEX ix_todos_due ON todos (due);

CREATE TABLE events (
  id           VARCHAR2(64) PRIMARY KEY,
  title        VARCHAR2(1000 CHAR),
  start_date   VARCHAR2(10),
  end_date     VARCHAR2(10),
  start_time   VARCHAR2(5),
  repeat_rule  VARCHAR2(20),
  repeat_until VARCHAR2(10),
  color        VARCHAR2(20),
  memo         VARCHAR2(4000 CHAR),
  dday         NUMBER(1) DEFAULT 0 NOT NULL
);

CREATE TABLE notes (
  id          VARCHAR2(64) PRIMARY KEY,
  title       VARCHAR2(1000 CHAR),
  body        CLOB,
  pinned      NUMBER(1) DEFAULT 0 NOT NULL,
  updated_at  VARCHAR2(40)
);

CREATE TABLE snippets (
  id          VARCHAR2(64) PRIMARY KEY,
  title       VARCHAR2(1000 CHAR),
  tag         VARCHAR2(100 CHAR),
  code        CLOB,
  memo        VARCHAR2(4000 CHAR),
  lang        VARCHAR2(20),
  updated_at  VARCHAR2(40)
);

CREATE TABLE routines (
  id          VARCHAR2(64) PRIMARY KEY,
  title       VARCHAR2(1000 CHAR),
  sort_order  NUMBER DEFAULT 0
);

CREATE TABLE routine_log (
  log_date    VARCHAR2(10),
  routine_id  VARCHAR2(64),
  CONSTRAINT pk_routine_log PRIMARY KEY (log_date, routine_id)
);

CREATE TABLE holidays (
  hdate  VARCHAR2(10) PRIMARY KEY,
  name   VARCHAR2(200 CHAR)
);
