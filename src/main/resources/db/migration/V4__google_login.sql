-- Google 로그인: 외부 계정(Google 의 sub) ↔ 하루한판 사용자 연결
-- 이메일은 바뀔 수 있으므로 식별은 subject(sub)로 하고, email 은 화면 표시용으로만 저장한다.
CREATE TABLE user_identities (
  provider    VARCHAR2(20) NOT NULL,
  subject     VARCHAR2(255) NOT NULL,
  user_id     VARCHAR2(64) NOT NULL,
  email       VARCHAR2(320 CHAR),
  created_at  VARCHAR2(40),
  CONSTRAINT pk_user_identities PRIMARY KEY (provider, subject)
);
CREATE INDEX ix_user_identities_user ON user_identities (user_id);

-- Google 로 가입한 사람은 비밀번호를 모른다(임의 값). 1 이면 본인이 정한 비밀번호가 있음
ALTER TABLE users ADD (password_set NUMBER(1) DEFAULT 1 NOT NULL);
