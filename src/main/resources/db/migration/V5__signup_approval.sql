-- Google 로 직접 가입한 사람은 관리자 승인 전까지 쓸 수 없다 (approved = 0)
-- 기존 사용자와 초대 링크로 가입한 사람은 바로 승인 상태 (1)
ALTER TABLE users ADD (approved NUMBER(1) DEFAULT 1 NOT NULL);
