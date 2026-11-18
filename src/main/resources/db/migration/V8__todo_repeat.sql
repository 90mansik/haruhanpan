-- 할 일 반복 (daily·weekday·weekly·monthly·yearly, 비어 있으면 반복 없음).
-- 반복 할 일을 완료하면 화면이 다음 마감일로 새 할 일을 만든다 (app.js spawnNextRepeat).
ALTER TABLE todos ADD repeat_rule VARCHAR2(20);
