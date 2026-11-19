-- 하루위키: 메모(notes)를 페이지 트리 + 블록 편집기(Editor.js) 페이지로 확장한다.
--   parent_id  상위 페이지 (없으면 최상위). 같은 공간 안에서만
--   sort_no    같은 상위 아래 순서
--   icon       페이지 이모지
--   content    Editor.js JSON {time, blocks, version}. 비어 있으면 화면이 body(예전 메모 글)를 문단으로 바꿔 보여 준다
--   body       는 그대로 둔다: content 에서 뽑은 글자만 (검색·홈 미리보기·예전 화면 호환)
ALTER TABLE notes ADD (parent_id VARCHAR2(64), sort_no NUMBER DEFAULT 0, icon VARCHAR2(16 CHAR), content CLOB);
CREATE INDEX ix_notes_parent ON notes (space_id, parent_id);

-- 위키 이미지. 파일은 데이터 폴더(files/)에 id 이름으로 저장하고, 여기에는 누구 공간의 어느 페이지 것인지만
CREATE TABLE wiki_files (
  id          VARCHAR2(64) PRIMARY KEY,
  space_id    VARCHAR2(64) NOT NULL,
  page_id     VARCHAR2(64),
  name        VARCHAR2(200 CHAR),
  mime        VARCHAR2(40) NOT NULL,
  bytes       NUMBER NOT NULL,
  created_by  VARCHAR2(64),
  created_at  VARCHAR2(40)
);
CREATE INDEX ix_wiki_files_space ON wiki_files (space_id);
