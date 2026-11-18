-- 가계부 정기결제 (구독·고정비). 결제일이 되면 서버가 내역을 자동으로 만든다 (LedgerRecurringJob).
-- 결제일이 그 달에 없으면(31일 등) 그 달 마지막 날. 시작일 이전 달은 소급하지 않는다.

CREATE TABLE ledger_recurring (
  id            VARCHAR2(64) PRIMARY KEY,
  space_id      VARCHAR2(64) NOT NULL,
  name          VARCHAR2(100 CHAR) NOT NULL,
  kind          VARCHAR2(10) DEFAULT 'fixed' NOT NULL,
  major         VARCHAR2(50 CHAR),
  minor         VARCHAR2(50 CHAR),
  amount        NUMBER(15) NOT NULL,
  method        VARCHAR2(50 CHAR),
  person_id     VARCHAR2(64),
  day_of_month  NUMBER(2) NOT NULL,
  start_date    VARCHAR2(10) NOT NULL,
  end_date      VARCHAR2(10),
  active        NUMBER(1) DEFAULT 1 NOT NULL,
  created_by    VARCHAR2(64),
  updated_by    VARCHAR2(64),
  created_at    VARCHAR2(40),
  updated_at    VARCHAR2(40),
  version       NUMBER DEFAULT 1 NOT NULL,
  CONSTRAINT ck_ledger_recurring_kind CHECK (kind IN ('income', 'saving', 'fixed', 'variable')),
  CONSTRAINT ck_ledger_recurring_day CHECK (day_of_month BETWEEN 1 AND 31),
  CONSTRAINT ck_ledger_recurring_amount CHECK (amount > 0)
);
CREATE INDEX ix_ledger_recurring_space ON ledger_recurring (space_id);

-- 달마다 한 번만 기록하기 위한 표시. 자동으로 만든 내역을 지워도 이 표시는 남아서 다시 만들지 않는다
CREATE TABLE ledger_recurring_runs (
  recurring_id  VARCHAR2(64) NOT NULL,
  run_month     VARCHAR2(7) NOT NULL,
  entry_id      VARCHAR2(64),
  created_at    VARCHAR2(40),
  CONSTRAINT pk_ledger_recurring_runs PRIMARY KEY (recurring_id, run_month)
);

-- 정기결제로 만든 내역 표시 (🔁)
ALTER TABLE ledger_entries ADD recurring_id VARCHAR2(64);
