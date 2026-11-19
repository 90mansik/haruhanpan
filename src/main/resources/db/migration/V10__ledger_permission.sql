-- 하루가계부 사용 권한. 기본 기능은 하루한판이고, 가계부는 사이트 관리자가 허락한 사람만 쓴다.
-- 기존 사용자 중 관리자와 이미 가계부를 쓰던 사람(내역을 남긴 사람)은 계속 쓸 수 있게 켜 둔다.
ALTER TABLE users ADD (ledger NUMBER(1) DEFAULT 0 NOT NULL);
UPDATE users SET ledger = 1
 WHERE admin = 1 OR id IN (SELECT created_by FROM ledger_entries WHERE created_by IS NOT NULL);
