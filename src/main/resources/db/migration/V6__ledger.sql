-- 가계부: 장부 = 공간 (개인 공간 = 내 가계부, 팀 공간 = 함께 쓰는 가계부)
-- 분류·결제수단은 공간마다 따로. 설정 행이 없으면 화면에는 기본값(LedgerDefaults)을 보여 준다.
-- 내역은 분류를 id 가 아니라 이름으로 저장한다 (분류를 고쳐도 지난 내역은 그대로 남도록).

-- 공간별 설정. 결제수단은 줄바꿈으로 이어 붙여 저장한다
CREATE TABLE ledger_settings (
  space_id         VARCHAR2(64) PRIMARY KEY,
  month_start_day  NUMBER(2) DEFAULT 1 NOT NULL,
  methods          VARCHAR2(2000 CHAR),
  updated_by       VARCHAR2(64),
  updated_at       VARCHAR2(40),
  CONSTRAINT ck_ledger_settings_day CHECK (month_start_day BETWEEN 1 AND 28)
);

-- 분류: 구분(income·saving·expense)별 대분류 한 줄, 소분류는 줄바꿈으로 이어 붙인다
CREATE TABLE ledger_categories (
  space_id  VARCHAR2(64) NOT NULL,
  kind      VARCHAR2(10) NOT NULL,
  major     VARCHAR2(50 CHAR) NOT NULL,
  minors    VARCHAR2(1000 CHAR),
  sort_no   NUMBER DEFAULT 0,
  CONSTRAINT pk_ledger_categories PRIMARY KEY (space_id, kind, major),
  CONSTRAINT ck_ledger_categories_kind CHECK (kind IN ('income', 'saving', 'expense'))
);

-- 내역. 지출은 고정(fixed)·변동(variable)으로 나누고 둘 다 expense 분류를 쓴다. 금액은 원 단위 정수
CREATE TABLE ledger_entries (
  id          VARCHAR2(64) PRIMARY KEY,
  space_id    VARCHAR2(64) NOT NULL,
  entry_date  VARCHAR2(10) NOT NULL,
  kind        VARCHAR2(10) NOT NULL,
  major       VARCHAR2(50 CHAR),
  minor       VARCHAR2(50 CHAR),
  memo        VARCHAR2(500 CHAR),
  amount      NUMBER(15) NOT NULL,
  method      VARCHAR2(50 CHAR),
  person_id   VARCHAR2(64),
  created_by  VARCHAR2(64),
  updated_by  VARCHAR2(64),
  created_at  VARCHAR2(40),
  updated_at  VARCHAR2(40),
  version     NUMBER DEFAULT 1 NOT NULL,
  CONSTRAINT ck_ledger_entries_kind CHECK (kind IN ('income', 'saving', 'fixed', 'variable')),
  CONSTRAINT ck_ledger_entries_amount CHECK (amount > 0)
);
CREATE INDEX ix_ledger_entries_space_date ON ledger_entries (space_id, entry_date);
