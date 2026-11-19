'use strict';
/* 하루한판 - 화면 (외부 라이브러리: highlight.js 만, 서버에 직접 포함)
 * 구조: 상태(S) → render 함수가 #main 을 다시 그림 → 변경은 1건 단위로 API 저장(send)
 * 공간: 모든 항목에 spaceId 가 있다.
 *   UI.view  보기 범위 — 'p'(내 공간) / 't:팀id'(팀만) / 'pt:팀id'(내 공간 + 팀 하나). 홈 포함 모든 탭이 따른다
 *   UI.space 새 항목을 저장할 공간 — 같이 볼 때는 기본이 내 공간, 상단 "저장 위치"에서 바꾼다
 * 탭: #home #todo #board #calendar #routine #wiki(하루위키, 예전 #notes) #snippets
 */

// ───────── 상태 ─────────
const S = {
  me: { id: '', name: '', login: '', admin: false }, spaces: [],
  todos: [], events: [], notes: [], snippets: [], routines: [],
  routineChecks: [], holidays: {}, spaceDayOffs: {}, activity: [],
};
const UI = {
  view: 'p', space: null,
  todoFilter: 'open', todoTag: '', todoQuery: '', todoAssignee: '',
  boardTag: '', boardAssignee: '',
  calMonth: null, calSel: null,
  logScroll: null,
  noteId: null, noteQuery: '',
  snipTag: '', snipQuery: '',
};

// ───────── 공통 유틸 ─────────
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
const pad = n => String(n).padStart(2, '0');
const ymd = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const parse = s => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); };
const today = () => ymd(new Date());
const addDays = (s, n) => { const d = parse(s); d.setDate(d.getDate() + n); return ymd(d); };
const diffDays = (a, b) => Math.round((parse(b) - parse(a)) / 86400000); // b - a
const nowIso = () => new Date().toISOString();
const WD = ['일', '월', '화', '수', '목', '금', '토'];
const fmtDate = s => { const d = parse(s); return `${d.getMonth() + 1}/${d.getDate()}(${WD[d.getDay()]})`; };
const PRI = { high: '높음', mid: '보통', low: '낮음' };
const PRI_ORDER = { high: 0, mid: 1, low: 2, '': 3 };
const STATUS = { todo: '준비', doing: '진행중', done: '완료' };
const ROLE = { owner: '관리자', editor: '편집', viewer: '보기' };
const COLORS = ['#2563eb', '#16a34a', '#dc2626', '#d97706', '#7c3aed', '#0891b2', '#db2777', '#6b7280'];
const REPEAT = { '': '없음', daily: '매일', weekday: '평일', weekly: '매주', monthly: '매월', yearly: '매년' };

function timeAgo(iso) {
  const sec = Math.max(0, (Date.now() - new Date(iso)) / 1000);
  if (sec < 60) return '방금';
  if (sec < 3600) return Math.floor(sec / 60) + '분 전';
  if (sec < 86400) return Math.floor(sec / 3600) + '시간 전';
  return Math.floor(sec / 86400) + '일 전';
}

/** 이벤트 위임: root 안에서 selector 에 맞는 요소에 이벤트가 생기면 fn(el, e) */
function on(root, type, selector, fn) {
  root.addEventListener(type, e => {
    const el = e.target.closest(selector);
    if (el && root.contains(el)) fn(el, e);
  });
}
const byId = (list, id) => list.find(x => x.id === id);

// ───────── 공간·멤버 ─────────
const spaceById = id => S.spaces.find(s => s.id === id);
const personalId = () => (S.spaces.find(s => s.kind === 'personal') || S.spaces[0]).id;
/** 새 항목을 저장할 공간 */
const curSpace = () => spaceById(UI.space) || S.spaces[0];
const isTeam = (sid = UI.space) => (spaceById(sid) || {}).kind === 'team';
const roleIn = (sid = UI.space) => (spaceById(sid) || {}).role || 'viewer';
const canEdit = (sid = UI.space) => roleIn(sid) !== 'viewer';
const membersOf = (sid = UI.space) => (spaceById(sid) || {}).members || [];

/** 보기 범위에 들어가는 공간 id 들: 내 공간 / 팀 하나 / 내 공간 + 팀 하나 */
function viewSpaces() {
  const [kind, sid] = UI.view.split(':');
  if (kind === 'p' || !spaceById(sid)) return [personalId()];
  return kind === 't' ? [sid] : [personalId(), sid];
}
const isMixed = () => viewSpaces().length > 1;
/** 보기 범위에 있는 팀 공간 (없으면 null) */
const viewTeam = () => viewSpaces().find(id => isTeam(id)) || null;
const inView = sid => viewSpaces().includes(sid);
const inCur = list => list.filter(x => inView(x.spaceId));
/** 내가 편집할 수 있는 공간 (공간 옮기기 선택지) */
const editableSpaces = () => S.spaces.filter(s => s.role !== 'viewer');
const spaceLabel = s => (s.kind === 'team' ? '👥 ' : '👤 ') + s.name;
function userName(id) {
  if (!id) return '';
  if (id === S.me.id) return S.me.name;
  for (const s of S.spaces) {
    const m = s.members.find(x => x.id === id);
    if (m) return m.name;
  }
  return '(나간 멤버)';
}
function initials(name) {
  const n = (name || '?').trim();
  return /[가-힣]/.test(n) && n.length > 2 ? n.slice(-2) : n.slice(0, 2).toUpperCase();
}
function avatar(id, small = false) {
  if (!id) return '';
  const name = userName(id);
  const color = COLORS[[...id].reduce((a, c) => a + c.charCodeAt(0), 0) % COLORS.length];
  return `<span class="av ${small ? 'sm' : ''}" style="background:${color}" title="${esc(name)}">${esc(initials(name))}</span>`;
}
function spaceTag(sid) {
  const s = spaceById(sid);
  if (!s || s.kind !== 'team') return '';
  return `<span class="space-tag" style="--c:${esc(s.color || COLORS[1])}">${esc(s.name)}</span>`;
}
const readonlyNote = () => canEdit() ? '' : '<span class="badge lang">보기 전용</span>';
/**
 * 팀과 내 공간을 같이 볼 때: 새 항목은 팀에 저장하고(팀을 고르고 들어왔으니), 내 공간 항목에만 "👤 내 공간" 표시를 붙여 구분한다
 */
const MINE_TAG = '<span class="space-tag mine" title="내 공간 항목">👤 내 공간</span>';
const mixedTag = sid => isMixed() && !isTeam(sid) ? MINE_TAG : '';
/** 새 항목을 저장할 공간: 팀을 보고 있으면 팀 (팀에서 보기 권한뿐이면 내 공간), 아니면 내 공간 */
function defaultSpace(view = UI.view) {
  const team = view.includes(':') ? view.split(':')[1] : null;
  return team && spaceById(team) && canEdit(team) ? team : personalId();
}
/** (예전 "저장 위치" 안내 자리. 이제 저장 위치는 고르지 않는다) */
const targetHint = () => '';

// ───────── 서버 통신 ─────────
function csrfToken() {
  const m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : '';
}

function setStatus(text, isError = false) {
  const el = $('#save-state');
  el.textContent = text;
  el.title = text; // 칸 폭이 고정이라 긴 글은 잘린다 → 마우스를 올리면 전체
  el.classList.toggle('err', isError);
}

class ApiError extends Error {
  constructor(status, message, body) { super(message); this.status = status; this.body = body || {}; }
}

async function request(method, path, body, keepalive = false) {
  let res;
  try {
    res = await fetch(path, {
      method,
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: 'same-origin',
      keepalive,
    });
  } catch (e) {
    setStatus('서버에 연결할 수 없습니다', true);
    throw new ApiError(0, 'network');
  }
  if (res.status === 401) {
    // 쓰던 메모를 잃지 않도록 자동으로 이동하지 않는다
    setStatus('로그인이 만료되었습니다 — 새로고침 후 다시 로그인하세요', true);
    throw new ApiError(401, '401');
  }
  if (res.status === 503) {
    setStatus('DB 에 연결할 수 없습니다', true);
    throw new ApiError(503, '503');
  }
  let json = {};
  try { json = await res.json(); } catch (e) { /* 본문 없음 */ }
  if (!res.ok) throw new ApiError(res.status, json.error || String(res.status), json);
  return json;
}

// 같은 경로는 잠깐 모았다가 마지막 요청만 보낸다. 같은 경로가 아직 처리 중이면 끝난 뒤에 보낸다
const pending = new Map();   // path → {method, body, onOk, onConflict, timer}
const running = new Set();   // 처리 중인 path
let inflight = 0;

function send(method, path, body, handlers = {}) {
  const prev = pending.get(path);
  if (prev) clearTimeout(prev.timer);
  const job = { method, body, ...handlers };
  job.timer = setTimeout(() => flush(path), 300);
  pending.set(path, job);
  setStatus('저장 중…');
}

async function flush(path) {
  const job = pending.get(path);
  if (!job) return;
  if (running.has(path)) { job.timer = setTimeout(() => flush(path), 200); return; }
  pending.delete(path);
  running.add(path);
  inflight++;
  try {
    const res = await request(job.method, path, job.body);
    if (job.onOk) job.onOk(res);
    if (inflight === 1 && pending.size === 0) {
      const d = new Date();
      setStatus(`저장됨 ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`);
    }
  } catch (e) {
    if (e.status === 409 && job.onConflict) job.onConflict(e);
    else if (![401, 503, 0].includes(e.status)) setStatus('저장 실패 — ' + e.message, true);
  } finally {
    inflight--;
    running.delete(path);
  }
}

// 창을 닫을 때 남은 요청은 keepalive 로 보낸다
window.addEventListener('pagehide', () => {
  for (const [path, job] of pending) {
    clearTimeout(job.timer);
    request(job.method, path, job.body, true).catch(() => {});
  }
  pending.clear();
});
window.addEventListener('beforeunload', e => {
  if (pending.size || inflight) { e.preventDefault(); }
});

const base = sid => `/api/spaces/${encodeURIComponent(sid)}`;

/** 1건 저장. 응답의 새 버전을 기억하고, 다른 사람이 먼저 고쳤으면(409) 최신으로 다시 불러온다 */
function save(coll, item) {
  send('PUT', `${base(item.spaceId)}/${coll}/${encodeURIComponent(item.id)}`, item, {
    onOk: res => { if (res.version) item.version = res.version; },
    onConflict: () => onConflict(coll, item),
  });
}
const removeItem = (coll, item) => send('DELETE', `${base(item.spaceId)}/${coll}/${encodeURIComponent(item.id)}`);

async function onConflict(coll, item) {
  if (coll === 'notes') {
    // 내가 쓴 내용을 잃지 않도록 사본 메모로 남긴다
    const copy = { ...item, id: uid(), title: (item.title || '(제목 없음)') + ' (내 수정본)', version: 0, pinned: false, updatedAt: nowIso() };
    S.notes.push(copy);
    save('notes', copy);
    UI.noteId = copy.id;
    alert('다른 사람이 이 메모를 먼저 수정했습니다.\n내가 쓴 내용은 "(내 수정본)" 메모로 따로 저장했습니다.');
  } else {
    setStatus('다른 사람이 먼저 수정해서 최신 내용으로 바꿨습니다', true);
  }
  await reload(true);
}

// ───────── 불러오기·동기화 ─────────
let lastJson = '';
let checkIndex = new Map(); // "date|routineId" → Set(userId)

function applyData(data) {
  Object.assign(S, data);
  normalizeTodos();
  checkIndex = new Map();
  for (const c of S.routineChecks) {
    const k = c.date + '|' + c.routineId;
    if (!checkIndex.has(k)) checkIndex.set(k, new Set());
    checkIndex.get(k).add(c.userId);
  }
  // 보기 범위: 처음엔 지난번 것, 팀에서 나갔거나 팀이 지워졌으면 내 공간으로
  if (!UI.loaded) {
    UI.loaded = true;
    try { UI.view = localStorage.getItem('view') || 'p'; } catch (e) { /* 무시 */ }
  }
  const team = UI.view.split(':')[1];
  if (UI.view !== 'p' && !spaceById(team)) UI.view = 'p';
  if (!inView(UI.space)) UI.space = defaultSpace();
}

/** 보기 범위를 바꾼다. 저장 위치는 팀만 볼 땐 팀, 그 외엔 내 공간 */
function setView(view) {
  UI.view = view;
  try { localStorage.setItem('view', view); } catch (e) { /* 무시 */ }
  UI.space = defaultSpace(view);
  UI.todoTag = ''; UI.todoAssignee = ''; UI.boardTag = ''; UI.boardAssignee = '';
  UI.noteId = null; UI.snipTag = ''; UI.logScroll = null;
  render();
}

/** 이 공간이 보이도록 보기 범위를 맞춘다 (홈에서 다른 공간 메모를 눌렀을 때 등) */
function showSpace(sid) {
  if (inView(sid)) return;
  setView(isTeam(sid) ? 't:' + sid : 'p');
}

/**
 * 항목을 다른 공간으로 옮긴다. 먼저 지금 내용을 원래 공간에 저장한 뒤 옮긴다.
 * 루틴은 옮기지 않는다 (체크 기록이 공간에 묶여 있음).
 */
async function moveItem(coll, item, to) {
  const from = item.spaceId;
  if (from === to) return;
  try {
    const path = `${base(from)}/${coll}/${encodeURIComponent(item.id)}`;
    const pendingJob = pending.get(path);
    if (pendingJob) { clearTimeout(pendingJob.timer); pending.delete(path); }
    const res = await request('PUT', path, item);
    if (res.version) item.version = res.version;
    await request('POST', `${path}/move`, { to });
    item.spaceId = to;
    setStatus(`"${item.title || '(제목 없음)'}" → ${spaceById(to).name} 으로 옮겼습니다`);
    if (!inView(to)) showSpace(to);
    await reload(true);
  } catch (e) {
    alert('옮기지 못했습니다: ' + e.message);
    await reload(true);
  }
}

/** 수정 창에 넣는 "공간" 선택 (편집할 수 있는 공간이 2개 이상일 때만) */
function spaceField() {
  const list = editableSpaces();
  return list.length > 1
    ? [{ name: 'spaceId', label: '공간', type: 'select', options: list.map(s => [s.id, spaceLabel(s)]) }]
    : [];
}

async function loadAll() {
  const data = await request('GET', '/api/all');
  lastJson = JSON.stringify(data);
  applyData(data);
}

/** 다른 사람의 변경을 받아온다. 입력 중이거나 저장 대기 중이면 건너뛴다 (force 면 그래도 불러옴) */
async function reload(force = false) {
  const typing = document.activeElement && document.activeElement.closest('#main, #dlg')
    && (/^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName) || document.activeElement.isContentEditable); // 위키 편집기는 contenteditable
  // 드래그로 골라 둔 글자가 있으면 다시 그리면서 지우지 않는다 (스니펫 부분 복사 등)
  const sel = getSelection();
  const selecting = sel && !sel.isCollapsed && sel.anchorNode && $('#main').contains(sel.anchorNode);
  if (!force && (pending.size || inflight || noteTimer || $('#dlg').open || typing || selecting)) return;
  try {
    const data = await request('GET', '/api/all');
    const json = JSON.stringify(data);
    if (json === lastJson && !force) return;
    lastJson = json;
    applyData(data);
    render();
  } catch (e) { /* 상태줄에 이미 표시됨 */ }
}

// ───────── 할 일 규칙 ─────────
/** status 와 done 을 맞춘다 (done=true ⇔ status='done') */
function normalizeTodos() {
  for (const t of S.todos) {
    if (!STATUS[t.status]) t.status = t.done ? 'done' : 'todo';
    t.done = t.status === 'done';
  }
}

/** 상태 변경은 여기 한 곳에서. 다른 열로 가면 그 열 맨 끝 순서를 준다 */
function applyTodoStatus(t, st) {
  if (t.status === st) return;
  const prev = t.status;
  const last = Math.max(0, ...S.todos.filter(x => x.spaceId === t.spaceId && x.status === st).map(x => x.rank || 0));
  t.status = st;
  t.done = st === 'done';
  t.doneAt = t.done ? nowIso() : '';
  t.rank = last + 1000;
  if (prev && prev !== 'done' && st === 'done') spawnNextRepeat(t); // 새로 만드는 중(prev '')에는 아님
}

/** 반복 규칙에 따른 다음 날짜 (매월·매년은 그 달에 없는 날이면 말일) */
function nextRepeatDate(date, rule) {
  const d = parse(date);
  if (rule === 'daily') d.setDate(d.getDate() + 1);
  else if (rule === 'weekday') { do d.setDate(d.getDate() + 1); while (d.getDay() === 0 || d.getDay() === 6); }
  else if (rule === 'weekly') d.setDate(d.getDate() + 7);
  else if (rule === 'monthly' || rule === 'yearly') {
    const day = d.getDate();
    d.setDate(1);
    if (rule === 'monthly') d.setMonth(d.getMonth() + 1); else d.setFullYear(d.getFullYear() + 1);
    d.setDate(Math.min(day, new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate()));
  } else return null;
  return ymd(d);
}

/**
 * 반복 할 일을 완료하면 다음 마감일로 새 할 일을 만든다. 늦게 완료했으면 오늘 이후의 첫 날짜로.
 * 완료를 풀었다가 다시 해도 같은 할 일이 두 번 생기지 않게 확인한다.
 */
function spawnNextRepeat(t) {
  if (!t.repeat || !t.due) return;
  let next = nextRepeatDate(t.due, t.repeat);
  while (next && next <= today()) next = nextRepeatDate(next, t.repeat);
  if (!next) return;
  if (S.todos.some(x => x.spaceId === t.spaceId && x.title === t.title && x.repeat === t.repeat && x.due === next)) return;
  newTodo({ title: t.title, due: next, priority: t.priority, tag: t.tag, memo: t.memo, assignee: t.assignee,
    repeat: t.repeat, spaceId: t.spaceId });
}
const repeatLabel = t => t.repeat ? `<span class="due" title="반복 할 일 — 완료하면 다음 할 일이 생깁니다">🔁 ${REPEAT[t.repeat]}</span>` : '';

const doneToday = t => t.done && t.doneAt && ymd(new Date(t.doneAt)) === today();
const isOver = t => !t.done && t.due && t.due < today();
const isToday = t => !t.done && t.due === today();
/** 홈에서 "내 할 일": 개인 공간 것 + 팀 공간에서 나에게 배정된 것 */
const isMine = t => !isTeam(t.spaceId) || t.assignee === S.me.id;

function dueLabel(t) {
  if (!t.due) return '';
  if (t.done) return `<span class="due">${fmtDate(t.due)}</span>`;
  const n = diffDays(today(), t.due);
  if (n < 0) return `<span class="due over">${-n}일 지남</span>`;
  if (n === 0) return `<span class="due today">오늘</span>`;
  if (n === 1) return `<span class="due">내일</span>`;
  return `<span class="due">D-${n} · ${fmtDate(t.due)}</span>`;
}

function sortTodos(list) {
  return list.slice().sort((a, b) =>
    (a.done - b.done)
    || (a.due || '9999').localeCompare(b.due || '9999')
    || (PRI_ORDER[a.priority || ''] - PRI_ORDER[b.priority || ''])
    || (a.createdAt || '').localeCompare(b.createdAt || ''));
}

function newTodo(fields) {
  const t = {
    id: uid(), spaceId: UI.space, version: 0, title: '', due: '', priority: 'mid', tag: '', memo: '', assignee: '',
    done: false, doneAt: '', createdAt: nowIso(), status: 'todo', rank: 0, repeat: '', ...fields,
  };
  if (t.repeat && !t.due) t.due = today(); // 반복은 마감일 기준이라 없으면 오늘부터
  const st = t.status;
  t.status = '';
  applyTodoStatus(t, st);
  S.todos.push(t);
  save('todos', t);
  return t;
}

function toggleTodo(id) {
  const t = byId(S.todos, id);
  if (!t || !canEdit(t.spaceId)) return;
  applyTodoStatus(t, t.done ? 'todo' : 'done');
  save('todos', t);
  render();
}

const tagsOf = list => [...new Set(list.map(x => x.tag).filter(Boolean))].sort();

function todoRow(t, { showSpace = false } = {}) {
  const editable = canEdit(t.spaceId);
  return `<div class="todo-row ${t.done ? 'done' : ''}" data-id="${esc(t.id)}">
    <input type="checkbox" data-act="toggle" ${t.done ? 'checked' : ''} ${editable ? '' : 'disabled'} aria-label="완료">
    <span class="pri ${esc(t.priority)}" title="우선순위 ${PRI[t.priority] || '-'}"></span>
    <div class="main" data-act="edit">
      <div class="title">${esc(t.title)} ${t.status === 'doing' ? '<span class="badge doing">진행중</span>' : ''}</div>
      <div class="meta">${showSpace ? mixedTag(t.spaceId) : ''}${t.tag ? `<span class="tag">${esc(t.tag)}</span>` : ''}${dueLabel(t)}${repeatLabel(t)}${t.memo ? '<span class="muted small">📝</span>' : ''}</div>
    </div>
    ${isTeam(t.spaceId) ? avatar(t.assignee) : ''}
    ${editable ? `<div class="acts">
      <button class="icon" data-act="edit" title="수정">수정</button>
      <button class="icon" data-act="del" title="삭제">삭제</button>
    </div>` : ''}
  </div>`;
}

/** 할 일 목록 공통 동작(체크·수정·삭제)을 root 에 붙인다 */
function bindTodoRows(root) {
  on(root, 'click', '.todo-row [data-act]', (el, e) => {
    const id = el.closest('.todo-row').dataset.id;
    const act = el.dataset.act;
    if (act === 'toggle') { e.preventDefault(); toggleTodo(id); }
    else if (act === 'edit') editTodo(id);
    else if (act === 'del') deleteTodo(id);
  });
}

function memberOptions(sid) {
  return [['', '(없음)'], ...membersOf(sid).map(m => [m.id, m.name + (m.id === S.me.id ? ' (나)' : '')])];
}

/** 할 일 추가·수정 창. defaults 는 새로 만들 때의 처음 값 (달력에서 누른 날짜 등) */
function editTodo(id, defaults = {}) {
  const t = id ? byId(S.todos, id) : null;
  const sid = t ? t.spaceId : UI.space;
  const fields = [
    { name: 'title', label: '제목', full: true, required: true },
    { name: 'due', label: '마감일', type: 'date' },
    { name: 'repeat', label: '반복 (완료하면 다음 할 일 생성)', type: 'select', options: Object.entries(REPEAT) },
    { name: 'priority', label: '우선순위', type: 'select', options: Object.entries(PRI) },
    { name: 'tag', label: '분류', list: tagsOf(S.todos.filter(x => x.spaceId === sid)) },
    { name: 'status', label: '상태', type: 'select', options: Object.entries(STATUS) },
  ];
  if (isTeam(sid)) fields.push({ name: 'assignee', label: '담당자', type: 'select', options: memberOptions(sid) });
  if (canEdit(sid)) fields.push(...spaceField());
  fields.push({ name: 'memo', label: '메모', type: 'textarea', full: true });
  openForm({
    title: (t ? '할 일 수정' : '할 일 추가') + (isTeam(sid) ? ' · ' + spaceById(sid).name : ''),
    fields,
    values: { ...(t || { priority: 'mid', status: 'todo', due: '', assignee: '', repeat: '', ...defaults }), spaceId: sid },
    readOnly: !canEdit(sid),
    meta: t && isTeam(sid) ? `만든 사람 ${esc(userName(t.createdBy))}${t.updatedBy ? ` · 마지막 수정 ${esc(userName(t.updatedBy))}` : ''}` : '',
    onSubmit: v => {
      const to = v.spaceId || sid;
      if (!t) {
        newTodo({ ...v, title: v.title.trim(), tag: v.tag.trim(), spaceId: to, assignee: isTeam(to) ? (v.assignee || '') : '' });
        if (!inView(to)) showSpace(to); else render();
        return;
      }
      const st = v.status;
      Object.assign(t, { title: v.title.trim(), due: v.due || (v.repeat ? today() : ''), priority: v.priority, tag: v.tag.trim(), memo: v.memo,
        assignee: v.assignee ?? t.assignee, repeat: v.repeat });
      applyTodoStatus(t, st);
      if (to !== t.spaceId) { moveItem('todos', t, to); return; } // 저장 후 옮김
      save('todos', t);
      render();
    },
    onDelete: t ? () => deleteTodo(t.id, true) : null,
  });
}

function deleteTodo(id, confirmed = false) {
  const t = byId(S.todos, id);
  if (!t || (!confirmed && !confirm(`"${t.title}" 을(를) 삭제할까요?`))) return;
  S.todos = S.todos.filter(x => x.id !== id);
  removeItem('todos', t);
  render();
}

// ───────── 일정 규칙 ─────────
/** 일정 e 가 날짜 d 에 있는가 (여러 날 일정·반복 일정 포함) */
function occursOn(e, d) {
  if (!e.date || d < e.date) return false;
  const rep = e.repeat || '';
  if (!rep) return d <= (e.endDate && e.endDate > e.date ? e.endDate : e.date);
  if (e.until && d > e.until) return false;
  const dd = parse(d), sd = parse(e.date);
  switch (rep) {
    case 'daily': return true;
    case 'weekday': return dd.getDay() > 0 && dd.getDay() < 6;
    case 'weekly': return dd.getDay() === sd.getDay();
    case 'monthly': return dd.getDate() === sd.getDate();
    case 'yearly': return dd.getDate() === sd.getDate() && dd.getMonth() === sd.getMonth();
    default: return d === e.date;
  }
}
const eventsOn = (d, list = inCur(S.events)) => list.filter(e => occursOn(e, d))
  .sort((a, b) => (a.time || '99').localeCompare(b.time || '99') || a.title.localeCompare(b.title));

/** D-Day 기준일: 반복 일정이면 오늘 이후 가장 가까운 날 */
function ddayDate(e) {
  if (!e.repeat) return e.date;
  for (let i = 0; i <= 366; i++) {
    const d = addDays(today(), i);
    if (occursOn(e, d)) return d;
  }
  return e.date;
}

function editEvent(id, date) {
  const e = id ? byId(S.events, id) : null;
  const sid = e ? e.spaceId : UI.space;
  openForm({
    title: (e ? '일정 수정' : '일정 추가') + (isTeam(sid) ? ' · ' + spaceById(sid).name : ''),
    fields: [
      { name: 'title', label: '제목', full: true, required: true },
      { name: 'date', label: '시작일', type: 'date', required: true },
      { name: 'endDate', label: '종료일 (여러 날)', type: 'date' },
      { name: 'time', label: '시각', type: 'time' },
      { name: 'repeat', label: '반복', type: 'select', options: Object.entries(REPEAT) },
      { name: 'until', label: '반복 종료일', type: 'date' },
      { name: 'color', label: '색', type: 'palette', full: true },
      { name: 'dday', label: 'D-Day 로 표시', type: 'checkbox', full: true },
      ...(canEdit(sid) ? spaceField() : []),
      { name: 'memo', label: '메모', type: 'textarea', full: true },
    ],
    values: { ...(e || { date: date || today(), color: COLORS[0], repeat: '' }), spaceId: sid },
    readOnly: !canEdit(sid),
    meta: e && isTeam(sid) ? `만든 사람 ${esc(userName(e.createdBy))}` : '',
    onSubmit: v => {
      const { spaceId: to = sid, ...fields } = v;
      const item = e || { id: uid(), spaceId: to, version: 0 };
      Object.assign(item, { ...fields, title: fields.title.trim() });
      if (item.endDate && item.endDate < item.date) item.endDate = '';
      if (!e) {
        S.events.push(item);
        save('events', item);
        if (!inView(to)) showSpace(to); else render();
        return;
      }
      if (to !== item.spaceId) { moveItem('events', item, to); return; }
      save('events', item);
      render();
    },
    onDelete: e ? () => {
      S.events = S.events.filter(x => x.id !== e.id);
      removeItem('events', e);
      render();
    } : null,
  });
}

// ───────── 루틴 규칙 (체크는 사람별) ─────────
const checkersOf = (d, rid) => checkIndex.get(d + '|' + rid) || new Set();
const iChecked = (d, rid) => checkersOf(d, rid).has(S.me.id);

function setRoutineCheck(r, d, checked) {
  const k = d + '|' + r.id;
  if (!checkIndex.has(k)) checkIndex.set(k, new Set());
  if (checked) checkIndex.get(k).add(S.me.id); else checkIndex.get(k).delete(S.me.id);
  S.routineChecks = S.routineChecks.filter(c => !(c.date === d && c.routineId === r.id && c.userId === S.me.id));
  if (checked) S.routineChecks.push({ date: d, routineId: r.id, userId: S.me.id });
  send(checked ? 'PUT' : 'DELETE', `${base(r.spaceId)}/routineChecks/${d}/${encodeURIComponent(r.id)}`);
}

// ───────── 휴일: 공휴일(모든 공간) + 공간별 휴무일 ─────────
const spaceDayOff = (d, sid = UI.space) => (S.spaceDayOffs[sid] || {})[d];
/** 보기 범위 안 공간들의 휴무일 → 없으면 공휴일 */
const holidayOn = d => viewSpaces().map(id => spaceDayOff(d, id)).find(Boolean) || S.holidays[d];

// ───────── 대화상자 ─────────
/**
 * 공통 입력 폼.
 * fields: [{name, label, type: text|date|time|select|textarea|checkbox|palette|password, options, list, required, full}]
 */
function openForm({ title, fields, values = {}, onSubmit, onDelete, submitLabel = '저장', readOnly = false, meta = '' }) {
  const dlg = $('#dlg');
  const listId = n => 'dl-' + n;
  const dis = readOnly ? 'disabled' : '';
  const input = f => {
    const v = values[f.name] ?? '';
    const req = f.required ? 'required' : '';
    switch (f.type) {
      case 'textarea': return `<textarea name="${f.name}" rows="5" ${dis}>${esc(v)}</textarea>`;
      case 'select': return `<select name="${f.name}" ${dis}>${f.options.map(([k, l]) =>
        `<option value="${esc(k)}" ${String(v) === k ? 'selected' : ''}>${esc(l)}</option>`).join('')}</select>`;
      case 'checkbox': return `<input type="checkbox" name="${f.name}" ${v ? 'checked' : ''} ${dis}>`;
      case 'palette': return `<div class="palette">${COLORS.map((c, i) =>
        `<input type="radio" name="${f.name}" id="pc${i}" value="${c}" ${(v || COLORS[0]) === c ? 'checked' : ''} ${dis}>
         <label for="pc${i}" style="background:${c}" title="${c}"></label>`).join('')}</div>`;
      default: return `<input type="${f.type || 'text'}" name="${f.name}" value="${esc(v)}" ${req} ${dis}
        ${f.list ? `list="${listId(f.name)}"` : ''} autocomplete="${f.autocomplete || 'off'}">
        ${f.list ? `<datalist id="${listId(f.name)}">${f.list.map(x => `<option value="${esc(x)}">`).join('')}</datalist>` : ''}`;
    }
  };
  dlg.innerHTML = `<form method="dialog">
    <h3>${esc(title)} ${readOnly ? '<span class="badge lang">보기 전용</span>' : ''}</h3>
    <div class="fields">${fields.map(f =>
      `<label class="field ${f.full ? 'full' : ''} ${f.type === 'checkbox' ? 'check' : ''}">
        ${f.type === 'checkbox' ? input(f) + `<span>${esc(f.label)}</span>` : `<span>${esc(f.label)}</span>` + input(f)}
      </label>`).join('')}</div>
    ${meta ? `<div class="muted small">${meta}</div>` : ''}
    <div class="dlg-acts">
      ${onDelete && !readOnly ? '<button type="button" class="danger" data-act="del">삭제</button>' : ''}
      <span class="grow"></span>
      <button type="button" class="ghost" data-act="cancel">${readOnly ? '닫기' : '취소'}</button>
      ${readOnly ? '' : `<button type="submit">${esc(submitLabel)}</button>`}
    </div>
  </form>`;
  const form = $('form', dlg);
  form.addEventListener('submit', e => {
    e.preventDefault();
    if (readOnly) return;
    const v = {};
    for (const f of fields) {
      if (f.type === 'checkbox') v[f.name] = form.elements[f.name].checked;
      else if (f.type === 'palette') v[f.name] = (form.querySelector(`input[name="${f.name}"]:checked`) || {}).value || COLORS[0];
      else v[f.name] = form.elements[f.name].value;
    }
    const missing = fields.find(f => f.required && !String(v[f.name]).trim());
    if (missing) { form.elements[missing.name].focus(); return; }
    dlg.close();
    onSubmit(v);
  });
  on(form, 'click', '[data-act]', el => {
    if (el.dataset.act === 'cancel') dlg.close();
    if (el.dataset.act === 'del' && confirm('삭제할까요?')) { dlg.close(); onDelete(); }
  });
  dlg.showModal();
  const first = form.querySelector('input:not([type=checkbox]):not([type=radio]):not([disabled]), textarea:not([disabled])');
  if (first) first.focus();
  return dlg;
}

// ───────── 라우터·상단 바 ─────────
const TABS = [
  ['home', '홈', renderHome], ['todo', '할 일', renderTodo], ['board', '보드', renderBoard],
  ['calendar', '달력', renderCalendar], ['routine', '루틴', renderRoutine],
  ['wiki', '위키', root => renderWiki(root)], ['snippets', '스니펫', renderSnippets],
];
const currentTab = () => {
  const h = location.hash.slice(1) === 'notes' ? 'wiki' : location.hash.slice(1); // 예전 메모 주소
  return TABS.some(t => t[0] === h) ? h : 'home';
};

// 메뉴 아이콘 (선 아이콘, 글자색을 따른다)
const NAV_ICON = {
  home: '<path d="M3 11.5 12 4l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"/>',
  todo: '<rect x="3.5" y="3.5" width="17" height="17" rx="3"/><path d="m8 12 3 3 5-6"/>',
  board: '<rect x="3.5" y="4" width="5" height="16" rx="1.5"/><rect x="9.5" y="4" width="5" height="11" rx="1.5"/><rect x="15.5" y="4" width="5" height="7" rx="1.5"/>',
  calendar: '<rect x="3.5" y="5" width="17" height="15.5" rx="2.5"/><path d="M3.5 10h17M8 3v4M16 3v4"/>',
  routine: '<path d="M4 12a8 8 0 0 1 13.7-5.6M20 12a8 8 0 0 1-13.7 5.6"/><path d="M18 3v4h-4M6 21v-4h4"/>',
  wiki: '<path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v15H6.5A2.5 2.5 0 0 0 4 20.5z"/><path d="M4 20.5A2.5 2.5 0 0 1 6.5 18H20v3H6.5"/>',
  snippets: '<path d="m9 8-5 4 5 4M15 8l5 4-5 4"/>',
};
const navIcon = k => `<svg class="sn-svg" viewBox="0 0 24 24" aria-hidden="true">${NAV_ICON[k] || ''}</svg>`;

/** 메뉴 옆 숫자: 할 일(지남+오늘 마감, 지난 게 있으면 빨강) · 달력(오늘 일정) · 루틴(오늘 한 수/전체) */
function navBadges() {
  const t0 = today();
  const mine = inCur(S.todos).filter(t => !t.done && isMine(t));
  const over = mine.filter(isOver).length;
  const due = over + mine.filter(isToday).length;
  const evToday = inCur(S.events).filter(e => occursOn(e, t0)).length;
  const routines = inCur(S.routines);
  const done = routines.filter(r => iChecked(t0, r.id)).length;
  return {
    todo: due ? `<span class="sn-badge ${over ? 'alert' : ''}" title="지남 ${over} · 오늘 마감 ${due - over}">${due}</span>` : '',
    calendar: evToday ? `<span class="sn-badge" title="오늘 일정">${evToday}</span>` : '',
    routine: routines.length ? `<span class="sn-badge ${done === routines.length ? 'ok' : ''}" title="오늘 루틴">${done}/${routines.length}</span>` : '',
  };
}

const MOBILE_TABS = ['home', 'todo', 'calendar', 'wiki'];

function renderTopbar() {
  const tab = currentTab();
  const badges = navBadges();
  $('#tabs').innerHTML = TABS.map(([k, label]) =>
    `<a href="#${k}" class="sn-item ${k === tab ? 'on' : ''}" title="${label}"><span class="sn-ic">${navIcon(k)}</span><span class="sn-label">${label}</span>${badges[k] || ''}</a>`).join('');
  $('#bottombar').innerHTML = MOBILE_TABS.map(k => { const t = TABS.find(x => x[0] === k);
    return `<a href="#${k}" class="${k === tab ? 'on' : ''}">${navIcon(k)}<span>${t[1]}</span>${badges[k] ? '<i class="dot"></i>' : ''}</a>`; }).join('')
    + `<button type="button" id="bb-more" class="${MOBILE_TABS.includes(tab) ? '' : 'on'}"><svg class="sn-svg" viewBox="0 0 24 24" aria-hidden="true"><circle cx="5" cy="12" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="19" cy="12" r="1.6"/></svg><span>더보기</span></button>`;
  // 위쪽 바 제목에도 메뉴와 같은 아이콘을 붙인다
  $('#page-title').innerHTML = `<span class="pt-ic">${navIcon(tab)}</span>${esc((TABS.find(t => t[0] === tab) || [])[1] || '')}`;
  // 보기 공간: 내 공간 / 팀 하나. 팀을 고르면 옆의 "내 공간도 같이" 체크로 내 공간 + 팀을 같이 본다
  const sel = $('#space-sel');
  const personal = spaceById(personalId());
  const teams = S.spaces.filter(s => s.kind === 'team');
  const vt = viewTeam();
  const pick = vt ? 't:' + vt : 'p';
  const opt = (v, label) => `<option value="${esc(v)}" ${pick === v ? 'selected' : ''}>${esc(label)}</option>`;
  sel.innerHTML = opt('p', '👤 ' + personal.name) + teams.map(t => opt('t:' + t.id, '👥 ' + t.name)).join('')
    + '<option value="__new">＋ 새 팀 공간…</option>';
  sel.style.setProperty('--c', (vt ? spaceById(vt) : personal).color || COLORS[0]);
  sel.title = '보기 공간 — 홈과 모든 메뉴가 이 공간을 보여줍니다';
  // 휴대폰 상단: 지금 공간 (누르면 서랍)
  const curSp = vt ? spaceById(vt) : personal;
  $('#cur-space').textContent = (vt ? '👥 ' : '👤 ') + curSp.name + (isMixed() ? ' + 내 공간' : '');
  $('#cur-dot').style.background = curSp.color || COLORS[0];
  $('#with-mine').classList.toggle('hidden', !vt);
  $('#with-mine-cb').checked = isMixed();

  $('#btn-account').textContent = S.me.name || S.me.login;
  const pendingCount = (S.pendingUsers || []).length;
  const pb = $('#btn-pending');
  pb.classList.toggle('hidden', !pendingCount);
  pb.textContent = `승인 대기 ${pendingCount}`;
}

/** Google 로 직접 가입해 승인을 기다리는 사람에게 보이는 화면 */
function renderPendingScreen() {
  $('#sidenav').classList.add('hidden');
  $('#bottombar').classList.add('hidden');
  $('#btn-nav').classList.add('hidden');
  $$('.tools > :not(#btn-theme):not(#btn-logout):not(.menu-btn)').forEach(el => el.classList.add('hidden'));
  $('#main').innerHTML = `<div class="login-wrap" style="min-height:60vh">
    <section class="panel login" style="text-align:center">
      <h1>⏳ 승인 대기 중</h1>
      <p style="margin:0">가입 신청이 접수되었습니다.<br>사이트 관리자가 승인하면 바로 사용할 수 있습니다.</p>
      <p class="muted small" style="margin:0">팀 초대 링크를 받았다면 그 링크를 열면 승인 없이 바로 참여됩니다.</p>
      <button id="btn-recheck">승인됐는지 다시 확인</button>
    </section></div>`;
  $('#btn-recheck').addEventListener('click', () => location.reload());
}

function pendingDialog() {
  const list = S.pendingUsers || [];
  const teams = S.spaces.filter(s => s.kind === 'team' && s.role === 'owner');
  const dlg = $('#dlg');
  dlg.innerHTML = `<form method="dialog">
    <h3>가입 승인 대기 ${list.length}명</h3>
    <p class="muted small" style="margin:0">Google 로 직접 가입한 사람입니다. 아는 사람만 승인하세요. 30일 동안 처리하지 않으면 자동으로 지워집니다.</p>
    <div class="member-list">${list.map(u => `<div class="pending-user" data-uid="${esc(u.id)}">
      <div class="who"><b>${esc(u.name)}</b> <span class="muted small">${esc(u.email || u.login)} · ${u.createdAt ? timeAgo(u.createdAt) : ''}</span></div>
      <div class="row">
        <select data-f="space"><option value="">팀 없이 (개인 공간만)</option>${teams.map(s => `<option value="${esc(s.id)}">${esc(s.name)} 에 추가</option>`).join('')}</select>
        <select data-f="role">${Object.entries(ROLE).filter(([k]) => k !== 'owner').map(([k, l]) => `<option value="${k}">${l}</option>`).join('')}</select>
        <span class="grow" style="flex:1"></span>
        <button type="button" class="small" data-act="approve">승인</button>
        <button type="button" class="danger small" data-act="reject">거절</button>
      </div>
    </div>`).join('') || '<div class="empty">대기 중인 신청이 없습니다</div>'}</div>
    <div class="dlg-acts"><span class="grow"></span><button type="button" class="ghost" data-act="close">닫기</button></div>
  </form>`;
  const form = $('form', dlg);
  form.addEventListener('submit', e => e.preventDefault());
  on(form, 'click', 'button[data-act]', async el => {
    const act = el.dataset.act;
    if (act === 'close') { dlg.close(); return; }
    const row = el.closest('[data-uid]');
    const uidv = row.dataset.uid;
    const name = row.querySelector('b').textContent;
    try {
      if (act === 'approve') {
        await request('POST', `/api/admin/users/${uidv}/approve`, {
          spaceId: row.querySelector('[data-f=space]').value, role: row.querySelector('[data-f=role]').value,
        });
      } else if (act === 'reject') {
        if (!confirm(`${name} 님의 가입 신청을 거절할까요? 계정이 삭제됩니다.`)) return;
        await request('DELETE', `/api/admin/users/${uidv}`);
      }
      await loadAll();
      render();
      pendingDialog();
    } catch (e) { alert(e.message); }
  });
  if (!dlg.open) dlg.showModal();
}

function render() {
  const tab = currentTab();
  renderTopbar();
  const main = $('#main');
  const fresh = main.cloneNode(false); // 이전 화면의 이벤트 리스너를 버리기 위해 새 요소로 교체
  main.replaceWith(fresh);
  TABS.find(t => t[0] === tab)[2](fresh);
  document.body.classList.remove('nav-open'); // 메뉴를 고르면 휴대폰 서랍은 닫는다
}

/** 이 공간(팀)을 보도록 범위를 바꾼다. 이미 보이면 그대로 */
function switchSpace(id) {
  if (inView(id)) { render(); return; }
  setView(isTeam(id) ? 't:' + id : 'p');
}

// ───────── 홈 (보기 범위 안의 공간을 모아서) ─────────
function renderHome(root) {
  const t0 = today();
  const mine = inCur(S.todos).filter(isMine);
  const open = mine.filter(t => !t.done);
  const over = open.filter(isOver);
  const dueToday = open.filter(isToday);
  const doing = open.filter(t => t.status === 'doing');
  const todayRoutines = inCur(S.routines);
  const routinesDone = todayRoutines.filter(r => iChecked(t0, r.id)).length;
  const pct = todayRoutines.length ? Math.round(routinesDone / todayRoutines.length * 100) : 0;
  const focus = sortTodos(open.filter(t => isOver(t) || isToday(t) || t.status === 'doing'));
  const d = new Date();
  const hol = S.holidays[t0];
  const teams = viewTeam() ? [spaceById(viewTeam())] : [];

  const days = [];
  for (let i = 0; i < 14; i++) {
    const day = addDays(t0, i);
    const evs = eventsOn(day);
    if (evs.length || S.holidays[day]) days.push([day, evs]);
  }
  const ddays = inCur(S.events).filter(e => e.dday)
    .map(e => [e, diffDays(t0, ddayDate(e))])
    .sort((a, b) => (a[1] < 0) - (b[1] < 0) || Math.abs(a[1]) - Math.abs(b[1]));
  const pinned = inCur(S.notes).filter(n => n.pinned).sort((a, b) => (b.updatedAt || '').localeCompare(a.updatedAt || ''));
  const routineGroups = viewSpaces().map(spaceById).map(s => [s, S.routines.filter(r => r.spaceId === s.id)]).filter(([, rs]) => rs.length);
  const scope = viewSpaces().map(id => spaceById(id).name).join(' + ');

  root.innerHTML = `
  <div class="hello">
    <h1>${d.getMonth() + 1}월 ${d.getDate()}일 ${WD[d.getDay()]}요일</h1>
    <span class="muted">${hol ? `🎉 ${esc(hol)} · ` : ''}${esc(S.me.name)}님, 오늘도 한 판 해볼까요?</span>
    <span class="muted small">· ${esc(scope)}${teams.length ? ' (팀 할 일은 나에게 배정된 것만)' : ''}</span>
  </div>
  <div class="stats">
    <div class="panel stat danger link" data-go="todo" data-filter="today"><div class="label">지연</div><div class="value">${over.length}</div></div>
    <div class="panel stat warn link" data-go="todo" data-filter="today"><div class="label">오늘 마감</div><div class="value">${dueToday.length}</div></div>
    <div class="panel stat link" data-go="board"><div class="label">미완료 · 진행중 → 보드</div><div class="value">${open.length} <small>· ${doing.length}</small></div></div>
    <div class="panel stat link" data-go="routine"><div class="label">오늘 루틴</div><div class="value">${routinesDone}<small> / ${todayRoutines.length}</small></div><div class="bar"><i style="width:${pct}%"></i></div></div>
  </div>
  ${weekStrip()}
  <div class="home-grid3">
    <div class="stack">
      <section class="panel"><div class="panel-head"><h2>집중할 일</h2><span class="grow"></span>${canEdit() ? '<button class="ghost small" data-act="add-todo">+ 추가</button>' : ''}</div>
        <div id="focus">${focus.length ? focus.map(t => todoRow(t, { showSpace: isMixed() })).join('') : '<div class="empty">급한 일이 없어요 👍</div>'}</div></section>
      ${teams.map(teamPanel).join('')}
      ${recentWikiPanel()}
    </div>
    <div class="stack">
      <section class="panel"><h2>앞으로 14일 일정</h2>
        ${days.length ? days.map(([day, evs]) => `<div class="day-group">
          <div class="d ${day === t0 ? 'today' : ''}">${fmtDate(day)}${day === t0 ? ' · 오늘' : ''}${S.holidays[day] ? ` <span style="color:var(--sun)">${esc(S.holidays[day])}</span>` : ''}</div>
          ${evs.map(e => `<div class="ev-line" data-ev="${esc(e.id)}" style="cursor:pointer"><span class="ev-dot" style="background:${esc(e.color || COLORS[0])}"></span>${e.time ? `<span class="muted small">${esc(e.time)}</span>` : ''}${esc(e.title)} ${mixedTag(e.spaceId)}</div>`).join('')}
        </div>`).join('') : '<div class="empty">예정된 일정이 없습니다</div>'}</section>
      <section class="panel"><h2>D-Day</h2>
        ${ddays.length ? ddays.map(([e, n]) => `<div class="dday-row" data-ev="${esc(e.id)}" style="cursor:pointer"><span>${esc(e.title)} ${mixedTag(e.spaceId)}</span><b>${n === 0 ? 'D-Day' : n > 0 ? 'D-' + n : 'D+' + -n}</b></div>`).join('')
          : '<div class="empty">일정에서 "D-Day 로 표시"를 켜세요</div>'}</section>
    </div>
    <div class="stack hg-side">
      <section class="panel"><h2>오늘 루틴</h2>
        ${routineGroups.length ? routineGroups.map(([s, rs]) => `
          ${routineGroups.length > 1 ? `<div class="group-label">${esc(s.name)}</div>` : ''}
          ${rs.map(r => {
            const c = iChecked(t0, r.id);
            return `<label class="routine-item ${c ? 'checked' : ''}"><input type="checkbox" data-rid="${esc(r.id)}" ${c ? 'checked' : ''}><span class="grow">${esc(r.title)}</span></label>`;
          }).join('')}`).join('') : '<div class="empty">루틴 탭에서 추가하세요</div>'}</section>
      <section class="panel" id="home-ledger"><div class="panel-head"><h2>이번 달 가계부</h2><span class="grow"></span><a class="small" href="/ledger" data-ledger>하루가계부 →</a></div><div class="empty small">불러오는 중…</div></section>
      <section class="panel"><h2>고정 페이지</h2>
        ${pinned.length ? pinned.map(n => `<div class="note-card" data-note="${esc(n.id)}"><b>${esc(n.icon || '📄')} ${esc(n.title || '(제목 없음)')}</b> ${mixedTag(n.spaceId)}<div class="body">${esc((n.body || '').slice(0, 200))}</div></div>`).join('')
          : '<div class="empty">하루위키에서 📌 홈에 고정하면 여기에 보여요</div>'}</section>
    </div>
  </div>`;

  bindTodoRows(root);
  on(root, 'click', '[data-go]', el => {
    if (el.dataset.filter) UI.todoFilter = el.dataset.filter;
    location.hash = el.dataset.go;
  });
  on(root, 'click', '[data-team]', el => { switchSpace(el.dataset.team); location.hash = el.dataset.goto || 'board'; });
  on(root, 'click', '[data-act="add-todo"]', () => editTodo(null));
  on(root, 'click', '[data-ev]', el => editEvent(el.dataset.ev));
  on(root, 'click', '[data-note]', el => {
    const n = byId(S.notes, el.dataset.note);
    UI.noteId = n.id;
    location.hash = 'wiki';
  });
  on(root, 'change', '[data-rid]', el => { setRoutineCheck(byId(S.routines, el.dataset.rid), t0, el.checked); render(); });
  on(root, 'click', '[data-wday]', el => { UI.calSel = el.dataset.wday; UI.calMonth = el.dataset.wday.slice(0, 7); location.hash = 'calendar'; });
  on(root, 'click', '[data-page]', el => { UI.noteId = el.dataset.page; location.hash = 'wiki'; });
  on(root, 'click', '[data-ledger]', (el, e) => { e.preventDefault(); location.href = '/ledger?space=' + encodeURIComponent(viewTeam() || personalId()); });
  fillHomeLedger(root);
}

/** 이번 주(월~일) 7칸: 일정과 내 마감 할 일. 누르면 그날 달력으로 */
function weekStrip() {
  const t0 = today();
  const dow = (parse(t0).getDay() + 6) % 7; // 월=0
  const start = addDays(t0, -dow);
  const mine = inCur(S.todos).filter(t => !t.done && isMine(t));
  const cells = Array.from({ length: 7 }, (_, i) => {
    const day = addDays(start, i);
    const evs = eventsOn(day);
    const tds = mine.filter(t => t.due === day);
    const hol = S.holidays[day];
    const items = [...evs.map(e => `<div class="wk-ev" style="--c:${esc(e.color || COLORS[0])}">${e.time ? `<span>${esc(e.time)}</span> ` : ''}${esc(e.title)}</div>`),
      ...tds.map(t => `<div class="wk-td ${day < t0 ? 'over' : ''}">☐ ${esc(t.title)}</div>`)];
    return `<button type="button" class="wk-day ${day === t0 ? 'today' : ''} ${day < t0 ? 'past' : ''}" data-wday="${day}">
      <div class="wk-d"><b>${WD[parse(day).getDay()]}</b> ${parse(day).getDate()}${hol ? ` <span class="hol">${esc(hol)}</span>` : ''}</div>
      ${items.slice(0, 3).join('')}${items.length > 3 ? `<div class="muted small">+${items.length - 3}</div>` : ''}
    </button>`;
  }).join('');
  return `<section class="panel week-strip"><div class="panel-head"><h2>이번 주</h2><span class="grow"></span>
    <span class="muted small">${fmtDate(start)} ~ ${fmtDate(addDays(start, 6))}</span></div><div class="wk-days">${cells}</div></section>`;
}

function recentWikiPanel() {
  const pages = inCur(S.notes).filter(n => n.updatedAt).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)).slice(0, 5);
  return `<section class="panel"><div class="panel-head"><h2>최근 위키</h2><span class="grow"></span><a class="small" href="#wiki">위키 →</a></div>
    ${pages.length ? pages.map(p => `<div class="recent-page" data-page="${esc(p.id)}"><span>${esc(p.icon || '📄')}</span>
      <span class="t">${esc(p.title || '(제목 없음)')}</span> ${mixedTag(p.spaceId)}<span class="muted small">${timeAgo(p.updatedAt)}</span></div>`).join('')
      : '<div class="empty">아직 페이지가 없습니다</div>'}</section>`;
}

/** 홈의 "이번 달 가계부": 지금 공간(팀을 보고 있으면 팀) 가계부의 이번 달. 1분 동안은 받아 둔 값을 쓴다 */
let homeLedgerCache = { key: '', at: 0, data: null };
async function fillHomeLedger(root) {
  const box = root.querySelector('#home-ledger');
  if (!box) return;
  const sid = viewTeam() || personalId();
  const won = n => Number(n || 0).toLocaleString('ko-KR') + '원';
  try {
    let d = homeLedgerCache.key === sid && Date.now() - homeLedgerCache.at < 60000 ? homeLedgerCache.data : null;
    if (!d) {
      const get = m => request('GET', `/api/ledger/spaces/${encodeURIComponent(sid)}?month=${m}`);
      d = await get(today().slice(0, 7));
      if (today() > d.to || today() < d.from) { // 월 시작일이 1일이 아니면 오늘이 든 달로
        const [y, m] = today().slice(0, 7).split('-').map(Number);
        d = await get(ymd(new Date(y, m - 1 + (today() > d.to ? 1 : -1), 1)).slice(0, 7));
      }
      homeLedgerCache = { key: sid, at: Date.now(), data: d };
    }
    if (!box.isConnected) return;
    const s = d.summary;
    const top = (s.byMajor || []).slice(0, 3);
    const max = top.length ? top[0].amount : 0;
    box.querySelector('.empty, .hl-body') && box.querySelectorAll('.empty, .hl-body').forEach(x => x.remove());
    box.insertAdjacentHTML('beforeend', `<div class="hl-body">
      <div class="hl-sum"><div><span class="muted small">지출</span><b>${won(s.expense)}</b></div>
        <div><span class="muted small">수입</span><b class="in">${won(s.income)}</b></div>
        <div><span class="muted small">저축</span><b class="sv">${won(s.saving)}</b></div></div>
      ${top.map(r => `<div class="rank-row"><div class="rank-label"><span>${esc(r.major)}</span><span>${won(r.amount)}</span></div>
        <div class="bar"><i style="width:${max ? r.amount / max * 100 : 0}%"></i></div></div>`).join('') || '<div class="muted small">이번 달 지출 기록이 없습니다</div>'}
    </div>`);
  } catch (e) {
    if (box.isConnected) box.querySelectorAll('.empty').forEach(x => { x.textContent = '가계부를 불러오지 못했습니다'; });
  }
}

const ACTION = { create: '추가', update: '수정', delete: '삭제', movein: '가져옴', moveout: '다른 공간으로 옮김' };
const COLL = { todos: '할 일', events: '일정', notes: '위키', snippets: '스니펫', routines: '루틴' };

/** 팀 대시보드: 멤버별 진행 상황 + 이번 주 마감 + 최근 활동 */
function teamPanel(s) {
  const todos = S.todos.filter(t => t.spaceId === s.id);
  const open = todos.filter(t => !t.done);
  const weekEnd = addDays(today(), 7);
  const dueWeek = open.filter(t => t.due && t.due <= weekEnd);
  const acts = S.activity.filter(a => a.spaceId === s.id).slice(0, 6);
  const unassigned = open.filter(t => !t.assignee).length;
  return `<section class="panel team-panel" style="--c:${esc(s.color || COLORS[1])}">
    <div class="panel-head"><h2>👥 ${esc(s.name)}</h2><span class="muted small">${s.members.length}명 · 미완료 ${open.length} · 이번 주 마감 ${dueWeek.length}</span>
      <span class="grow"></span><button class="ghost small" data-team="${esc(s.id)}" data-goto="board">보드 →</button></div>
    <div class="team-members">${s.members.map(m => {
      const mt = open.filter(t => t.assignee === m.id);
      const doing = mt.filter(t => t.status === 'doing').length;
      const over = mt.filter(isOver).length;
      return `<div class="tm">${avatar(m.id)}<div><div>${esc(m.name)}${m.id === S.me.id ? ' <span class="muted small">(나)</span>' : ''}</div>
        <div class="muted small">진행 ${doing} · 할 일 ${mt.length}${over ? ` · <span style="color:var(--danger)">지연 ${over}</span>` : ''}</div></div></div>`;
    }).join('')}${unassigned ? `<div class="tm"><span class="av" style="background:var(--line);color:var(--muted)">?</span><div><div class="muted">담당 없음</div><div class="muted small">할 일 ${unassigned}</div></div></div>` : ''}</div>
    ${acts.length ? `<div class="acts-list">${acts.map(a => `<div class="act-line">${avatar(a.userId, true)}
      <span><b>${esc(userName(a.userId))}</b> · ${COLL[a.coll] || ''} <span class="t">"${esc(a.title || '(제목 없음)')}"</span> ${ACTION[a.action] || ''}</span>
      <span class="muted small">${timeAgo(a.at)}</span></div>`).join('')}</div>` : '<div class="empty">아직 활동이 없습니다</div>'}
  </section>`;
}

// ───────── 할 일 ─────────
const TODO_FILTERS = [
  ['open', '미완료', t => !t.done],
  ['mine', '내 담당', t => !t.done && t.assignee === S.me.id, true],
  ['doing', '진행중', t => t.status === 'doing'],
  ['today', '오늘·지연', t => !t.done && t.due && t.due <= today()],
  ['week', '7일 이내', t => !t.done && t.due && t.due <= addDays(today(), 7)],
  ['nodue', '마감 없음', t => !t.done && !t.due],
  ['done', '완료', t => t.done],
  ['all', '전체', () => true],
];

function renderTodo(root) {
  const list0 = inCur(S.todos);
  const tags = tagsOf(list0);
  const team = !!viewTeam();          // 담당자 필터 등 팀 기능 표시
  const targetTeam = isTeam();        // 새 할 일이 팀 공간에 저장되는가 → 담당자 고르기
  const filters = TODO_FILTERS.filter(f => !f[3] || team);
  if (!filters.some(f => f[0] === UI.todoFilter)) UI.todoFilter = 'open';
  root.innerHTML = `
  <section class="panel">
    ${canEdit() ? `<form class="quick-add ${targetTeam ? 'team' : ''} ${isMixed() ? 'mixed' : ''}" id="qa">
      <input type="text" name="title" placeholder="할 일 입력 후 Enter" autocomplete="off" required>
      <input type="date" name="due" title="마감일">
      <select name="priority" title="우선순위">${Object.entries(PRI).map(([k, l]) => `<option value="${k}" ${k === 'mid' ? 'selected' : ''}>${l}</option>`).join('')}</select>
      <select name="repeat" title="반복 — 완료하면 다음 할 일이 생깁니다">${Object.entries(REPEAT).map(([k, l]) => `<option value="${k}">${k ? '🔁 ' + l : '반복 없음'}</option>`).join('')}</select>
      <input type="text" name="tag" placeholder="분류" list="qa-tags" autocomplete="off">
      ${targetTeam ? `<select name="assignee" title="담당자">${memberOptions().map(([k, l]) => `<option value="${esc(k)}" ${k === S.me.id ? 'selected' : ''}>${esc(k ? l : '담당 없음')}</option>`).join('')}</select>` : ''}
      <datalist id="qa-tags">${tags.map(x => `<option value="${esc(x)}">`).join('')}</datalist>
      ${targetHint()}
      <button type="submit">추가</button>
    </form>` : `<div class="row" style="margin-bottom:10px">${readonlyNote()}</div>`}
    <div class="filters">
      ${filters.map(([k, label, fn]) => `<span class="chip ${UI.todoFilter === k ? 'on' : ''}" data-filter="${k}">${label} <span class="n">${list0.filter(fn).length}</span></span>`).join('')}
      <span class="grow"></span>
      ${team ? `<select id="assignee-filter"><option value="">모든 담당자</option>${membersOf(viewTeam()).map(m => `<option value="${esc(m.id)}" ${UI.todoAssignee === m.id ? 'selected' : ''}>${esc(m.name)}</option>`).join('')}</select>` : ''}
      <select id="tag-filter"><option value="">전체 분류</option>${tags.map(x => `<option ${UI.todoTag === x ? 'selected' : ''}>${esc(x)}</option>`).join('')}</select>
      <input type="search" id="todo-q" placeholder="검색" value="${esc(UI.todoQuery)}">
    </div>
    <div id="todo-list"></div>
  </section>`;

  const drawList = () => {
    const fn = TODO_FILTERS.find(f => f[0] === UI.todoFilter)[2];
    // 미완료 목록에도 오늘 완료한 항목은 체크된 채로 맨 아래에 남긴다 (보드·홈에서 완료해도 보이도록)
    const show = UI.todoFilter === 'open' ? (t => fn(t) || doneToday(t)) : fn;
    const q = UI.todoQuery.trim().toLowerCase();
    const list = sortTodos(list0.filter(t => show(t)
      && (!UI.todoTag || t.tag === UI.todoTag)
      && (!UI.todoAssignee || t.assignee === UI.todoAssignee)
      && (!q || (t.title + ' ' + t.memo + ' ' + t.tag).toLowerCase().includes(q))));
    $('#todo-list', root).innerHTML = list.length ? list.map(t => todoRow(t, { showSpace: isMixed() })).join('') : '<div class="empty">해당하는 할 일이 없습니다</div>';
  };
  drawList();

  const qa = $('#qa', root);
  if (qa) qa.addEventListener('submit', e => {
    e.preventDefault();
    const f = e.target;
    if (!f.title.value.trim()) return;
    newTodo({ title: f.title.value.trim(), due: f.due.value, priority: f.priority.value, tag: f.tag.value.trim(),
      assignee: f.assignee ? f.assignee.value : '', repeat: f.repeat.value });
    render();
    $('#qa input[name=title]').focus();
  });
  on(root, 'click', '[data-filter]', el => { UI.todoFilter = el.dataset.filter; render(); });
  $('#tag-filter', root).addEventListener('change', e => { UI.todoTag = e.target.value; drawList(); });
  const af = $('#assignee-filter', root);
  if (af) af.addEventListener('change', e => { UI.todoAssignee = e.target.value; drawList(); });
  $('#todo-q', root).addEventListener('input', e => { UI.todoQuery = e.target.value; drawList(); });
  bindTodoRows(root);
}

// ───────── 보드 ─────────
const BOARD_COLS = [['todo', '준비', 'var(--muted)'], ['doing', '진행중', 'var(--warn)'], ['done', '완료', 'var(--ok)']];

function renderBoard(root) {
  const list0 = inCur(S.todos);
  const tags = tagsOf(list0);
  const team = !!viewTeam();
  const editable = canEdit();          // 빠른 추가 (저장 위치 기준)
  const doneSince = addDays(today(), -14);
  const visible = t => (!UI.boardTag || t.tag === UI.boardTag)
    && (!UI.boardAssignee || t.assignee === UI.boardAssignee)
    && (t.status !== 'done' || (t.doneAt || '').slice(0, 10) >= doneSince);
  const cards = st => list0.filter(t => t.status === st && visible(t)).sort((a, b) => (a.rank || 0) - (b.rank || 0));

  root.innerHTML = `
  <div class="filters">
    <span class="muted small">${editable ? '카드를 끌어 옮기세요 (휴대폰에서는 체크박스나 카드를 눌러 상태 변경)' : ''}</span>${readonlyNote()}
    <span class="grow"></span>
    ${team ? `<select id="board-assignee"><option value="">모든 담당자</option>${membersOf(viewTeam()).map(m => `<option value="${esc(m.id)}" ${UI.boardAssignee === m.id ? 'selected' : ''}>${esc(m.name)}</option>`).join('')}</select>` : ''}
    <select id="board-tag"><option value="">전체 분류</option>${tags.map(x => `<option ${UI.boardTag === x ? 'selected' : ''}>${esc(x)}</option>`).join('')}</select>
  </div>
  <div class="board">${BOARD_COLS.map(([st, label, color]) => {
    const list = cards(st);
    const addLabel = '+ 빠른 추가 (Enter)';
    return `<div class="col" data-col="${st}">
      <div class="col-head"><span class="dot" style="background:${color}"></span>${label} <span class="n">${list.length}</span></div>
      ${editable ? `<input type="text" placeholder="${esc(addLabel)}" data-add="${st}" autocomplete="off">` : ''}
      <div class="col-list">${list.map(t => {
        const can = canEdit(t.spaceId);
        const sp = spaceById(t.spaceId);
        return `<div class="card ${t.done ? 'done' : ''} ${isMixed() ? 'mixed' : ''}" draggable="${can}" data-id="${esc(t.id)}"
          style="--c:${esc((sp && sp.color) || COLORS[0])}">
        <div class="title"><input type="checkbox" data-act="toggle" ${t.done ? 'checked' : ''} ${can ? '' : 'disabled'} aria-label="완료"><span class="pri ${esc(t.priority)}"></span><span class="t">${esc(t.title)}</span></div>
        <div class="meta">${mixedTag(t.spaceId)}${t.tag ? `<span class="tag">${esc(t.tag)}</span>` : ''}${dueLabel(t)}${repeatLabel(t)}<span class="grow"></span>${isTeam(t.spaceId) ? avatar(t.assignee, true) : ''}</div>
      </div>`;
      }).join('')}</div>
      ${st === 'done' ? '<div class="col-note">최근 14일 완료만 표시 · 이전 항목은 할 일 탭 "완료" 필터</div>' : ''}
    </div>`;
  }).join('')}</div>`;

  $('#board-tag', root).addEventListener('change', e => { UI.boardTag = e.target.value; render(); });
  const ba = $('#board-assignee', root);
  if (ba) ba.addEventListener('change', e => { UI.boardAssignee = e.target.value; render(); });
  on(root, 'keydown', '[data-add]', (el, e) => {
    if (e.key !== 'Enter' || e.isComposing || !el.value.trim()) return;
    newTodo({ title: el.value.trim(), status: el.dataset.add, tag: UI.boardTag,
      assignee: isTeam() ? (UI.boardAssignee || S.me.id) : '' });
    render();
    $(`[data-add="${el.dataset.add}"]`).focus();
  });
  on(root, 'click', '.card', (el, e) => {
    if (e.target.matches('[data-act="toggle"]')) {
      e.preventDefault();
      toggleTodo(el.dataset.id); // 완료 ↔ 준비. 할 일 탭과 같은 데이터라 그쪽에도 바로 반영
      return;
    }
    editTodo(el.dataset.id);
  });
  if (!editable) return;

  // HTML5 드래그 앤 드롭
  let dragId = null;
  on(root, 'dragstart', '.card', (el, e) => {
    dragId = el.dataset.id;
    el.classList.add('dragging');
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', dragId);
  });
  on(root, 'dragend', '.card', el => {
    el.classList.remove('dragging');
    $$('.col.over', root).forEach(c => c.classList.remove('over'));
    if (dragId) { dragId = null; render(); } // 열 밖에 놓았으면 원래 자리로
  });
  on(root, 'dragover', '.col', (col, e) => {
    if (!dragId) return;
    e.preventDefault();
    $$('.col.over', root).forEach(c => c !== col && c.classList.remove('over'));
    col.classList.add('over');
    const list = $('.col-list', col);
    const dragging = $('.card.dragging', root);
    const after = [...list.querySelectorAll('.card:not(.dragging)')]
      .find(c => e.clientY < c.getBoundingClientRect().top + c.offsetHeight / 2);
    if (dragging) list.insertBefore(dragging, after || null);
  });
  on(root, 'drop', '.col', (col, e) => {
    e.preventDefault();
    if (!dragId) return;
    const st = col.dataset.col;
    const t = byId(S.todos, dragId);
    dragId = null;
    if (!t) return;
    const changed = new Set();
    if (t.status !== st) { applyTodoStatus(t, st); changed.add(t); }
    // 열 안의 카드들에 화면 순서대로 rank 를 다시 매기고, 바뀐 카드만 저장
    // (같이 볼 때 보기 권한만 있는 팀 카드는 순서를 바꾸지 않는다)
    $$('.card', col).forEach((c, i) => {
      const x = byId(S.todos, c.dataset.id);
      const rank = (i + 1) * 1000;
      if (x && canEdit(x.spaceId) && x.rank !== rank) { x.rank = rank; changed.add(x); }
    });
    changed.forEach(x => save('todos', x));
    render();
  });
}

// ───────── 달력 ─────────
function renderCalendar(root) {
  const t0 = today();
  if (!UI.calMonth) UI.calMonth = t0.slice(0, 7);
  if (!UI.calSel) UI.calSel = t0;
  const [y, m] = UI.calMonth.split('-').map(Number);
  const first = new Date(y, m - 1, 1);
  const start = ymd(new Date(y, m - 1, 1 - first.getDay()));
  const sel = UI.calSel;
  const editable = canEdit();
  const evList = inCur(S.events);
  const todoList = inCur(S.todos);

  const cells = [];
  for (let i = 0; i < 42; i++) {
    const d = addDays(start, i);
    const dt = parse(d);
    if (i === 35 && dt.getMonth() !== m - 1) break; // 5주로 끝나는 달
    const evs = eventsOn(d, evList);
    const tds = todoList.filter(t => t.due === d && !t.done);
    const hol = holidayOn(d);
    const items = [
      ...evs.map(e => `<div class="ev ${isMixed() && !isTeam(e.spaceId) ? 'mine-ev' : ''}" style="background:${esc(e.color || COLORS[0])}" title="${esc(spaceById(e.spaceId).name)}">${isMixed() && !isTeam(e.spaceId) ? '👤' : ''}${e.time ? esc(e.time) + ' ' : ''}${esc(e.title)}</div>`),
      ...tds.map(t => `<div class="td ${d < t0 ? 'over' : ''}">☐ ${esc(t.title)}</div>`),
    ];
    const max = 3;
    cells.push(`<div class="day ${dt.getMonth() !== m - 1 ? 'other' : ''} ${dt.getDay() === 0 ? 'sun' : ''} ${dt.getDay() === 6 ? 'sat' : ''}
      ${hol ? 'hol' : ''} ${d === t0 ? 'today' : ''} ${d === sel ? 'sel' : ''}" data-date="${d}">
      <span class="num">${dt.getDate()}</span>${hol ? `<span class="hname">${esc(hol)}</span>` : ''}
      ${items.slice(0, max).join('')}${items.length > max ? `<div class="more">+${items.length - max}</div>` : ''}
    </div>`);
  }

  const selEvs = eventsOn(sel, evList);
  const selTodos = sortTodos(todoList.filter(t => t.due === sel));
  const selOff = spaceDayOff(sel);                 // 저장 위치 공간의 휴무일 (지정·해제 대상)
  const otherOff = viewSpaces().filter(id => id !== UI.space).map(id => spaceDayOff(sel, id)).find(Boolean);
  const selPublic = S.holidays[sel];

  root.innerHTML = `
  <div class="cal-wrap">
    <section class="panel">
      <div class="cal-head">
        <button class="ghost small" data-nav="-1" aria-label="이전 달">◀</button>
        <h2>${y}년 ${m}월</h2>
        <button class="ghost small" data-nav="1" aria-label="다음 달">▶</button>
        <button class="ghost small" data-nav="0">오늘</button>
        ${isMixed() ? '<span class="grow" style="flex:1"></span><span class="muted small">👤 = 내 공간 일정</span>' : ''}
      </div>
      <div class="cal">${WD.map((w, i) => `<div class="wd ${i === 0 ? 'sun' : i === 6 ? 'sat' : ''}">${w}</div>`).join('')}${cells.join('')}</div>
    </section>
    <section class="panel">
      <div class="panel-head"><h2>${fmtDate(sel)}</h2><span class="grow"></span>${targetHint()}${editable ? '<button class="ghost small" data-act="add-todo" title="이 날이 마감인 할 일">+ 할 일</button><button class="small" data-act="add-ev">+ 일정</button>' : ''}</div>
      <div class="row" style="margin-bottom:8px">
        ${selOff ? `<span style="color:var(--sun)">🌴 ${esc(selOff)}</span>` : selPublic ? `<span style="color:var(--sun)">🎌 ${esc(selPublic)}</span>` : '<span class="muted small">평일</span>'}
        ${otherOff ? `<span class="muted small">· 다른 공간 휴무: ${esc(otherOff)}</span>` : ''}
        <span class="grow" style="flex:1"></span>
        ${editable ? `<button class="ghost small" data-act="holiday">${selOff ? '휴무일 해제' : '휴무일 지정'}</button>` : ''}
      </div>
      <h2 class="small muted" style="margin:8px 0 2px">일정</h2>
      ${selEvs.length ? selEvs.map(e => `<div class="side-item" data-ev="${esc(e.id)}">
        <span class="ev-dot" style="background:${esc(e.color || COLORS[0])}"></span>
        <div><div>${esc(e.title)} ${mixedTag(e.spaceId)}</div><div class="muted small">${[e.time, e.repeat ? REPEAT[e.repeat] + ' 반복' : '', e.endDate && !e.repeat ? `~ ${fmtDate(e.endDate)}` : '', e.dday ? 'D-Day' : ''].filter(Boolean).join(' · ')}</div>
        ${e.memo ? `<div class="muted small" style="white-space:pre-wrap">${esc(e.memo)}</div>` : ''}</div>
      </div>`).join('') : '<div class="empty">일정 없음</div>'}
      <h2 class="small muted" style="margin:12px 0 2px">마감 할 일</h2>
      <div id="sel-todos">${selTodos.length ? selTodos.map(t => todoRow(t, { showSpace: isMixed() })).join('') : '<div class="empty">없음</div>'}</div>
    </section>
  </div>`;

  on(root, 'click', '[data-nav]', el => {
    const n = Number(el.dataset.nav);
    if (n === 0) { UI.calMonth = t0.slice(0, 7); UI.calSel = t0; }
    else UI.calMonth = ymd(new Date(y, m - 1 + n, 1)).slice(0, 7);
    render();
  });
  on(root, 'click', '.day', el => {
    UI.calSel = el.dataset.date;
    if (el.classList.contains('other')) UI.calMonth = el.dataset.date.slice(0, 7);
    render();
  });
  if (editable) on(root, 'dblclick', '.day', el => editEvent(null, el.dataset.date));
  on(root, 'click', '[data-act="add-ev"]', () => editEvent(null, sel));
  on(root, 'click', '[data-act="add-todo"]', () => editTodo(null, { due: sel }));
  on(root, 'click', '[data-ev]', el => editEvent(el.dataset.ev));
  on(root, 'click', '[data-act="holiday"]', () => {
    const offs = S.spaceDayOffs[UI.space] || (S.spaceDayOffs[UI.space] = {});
    if (selOff) {
      if (!confirm(`${fmtDate(sel)} "${selOff}" 휴무일을 해제할까요?`)) return;
      delete offs[sel];
      send('DELETE', `${base(UI.space)}/dayOffs/${sel}`);
    } else {
      const name = prompt(`"${curSpace().name}" 휴무일 이름 (예: 휴가, 창립기념일)`, '휴무');
      if (!name || !name.trim()) return;
      offs[sel] = name.trim();
      send('PUT', `${base(UI.space)}/dayOffs/${sel}`, { name: name.trim() });
    }
    render();
  });
  bindTodoRows(root);
}

// ───────── 루틴 ─────────
function renderRoutine(root) {
  const t0 = today();
  // 같이 볼 때는 공간별로 묶어서 (내 공간 루틴 → 팀 루틴)
  const list = viewSpaces().flatMap(sid => S.routines.filter(r => r.spaceId === sid));
  const sameSpace = r => list.filter(x => x.spaceId === r.spaceId);
  const team = !!viewTeam();
  const editable = canEdit();
  // 최근 기록 표: 왼쪽→오른쪽으로 날짜가 흐르고 맨 오른쪽이 오늘
  const days = [];
  for (let i = 13; i >= 0; i--) days.push(addDays(t0, -i));

  const cell = (r, d) => {
    const who = checkersOf(d, r.id);
    const meOn = who.has(S.me.id);
    const othersOn = [...who].filter(u => u !== S.me.id);
    return `<td class="c ${meOn ? 'on' : ''} ${d === t0 ? 'today' : ''}" data-d="${d}" data-r="${esc(r.id)}"
      title="${esc([...who].map(userName).join(', '))}">${meOn ? '✓' : ''}${team && othersOn.length ? `<span class="who">${othersOn.map(u => esc(initials(userName(u)))).join(' ')}</span>` : ''}</td>`;
  };

  root.innerHTML = `
  <div class="grid cols-2">
    <section class="panel">
      <h2>루틴 목록 ${readonlyNote()}</h2>
      ${editable ? `<form class="row" id="r-add" style="margin-bottom:8px">
        <input type="text" name="title" placeholder="매일 할 루틴 입력 후 Enter" style="flex:1" autocomplete="off">
        ${targetHint()}
        <button type="submit">추가</button>
      </form>` : ''}
      ${list.length ? list.map(r => {
        const group = sameSpace(r);
        const i = group.indexOf(r);
        return `<div class="routine-item" data-rid="${esc(r.id)}">
        <span class="grow">${esc(r.title)} ${mixedTag(r.spaceId)}</span>
        ${canEdit(r.spaceId) ? `<button class="icon" data-act="up" ${i === 0 ? 'disabled' : ''} title="위로">▲</button>
        <button class="icon" data-act="down" ${i === group.length - 1 ? 'disabled' : ''} title="아래로">▼</button>
        <button class="icon" data-act="edit">수정</button>
        <button class="icon" data-act="del">삭제</button>` : ''}
      </div>`;
      }).join('') : '<div class="empty">루틴이 없습니다</div>'}
    </section>
    <section class="panel">
      <h2>오늘 내 체크 · ${fmtDate(t0)}</h2>
      ${list.map(r => {
        const c = iChecked(t0, r.id);
        const others = [...checkersOf(t0, r.id)].filter(u => u !== S.me.id);
        return `<label class="routine-item ${c ? 'checked' : ''}"><input type="checkbox" data-check="${esc(r.id)}" ${c ? 'checked' : ''}><span class="grow">${esc(r.title)} ${mixedTag(r.spaceId)}</span>
          ${isTeam(r.spaceId) ? others.map(u => avatar(u, true)).join('') : ''}</label>`;
      }).join('') || '<div class="empty">왼쪽에서 루틴을 추가하세요</div>'}
    </section>
  </div>
  <section class="panel" style="margin-top:16px">
    <h2>최근 기록 (14일)${team ? ' <span class="muted small">· ✓ 내 체크, 이니셜은 다른 멤버</span>' : ''}</h2>
    ${list.length ? `<div class="log-wrap"><table class="log">
      <thead><tr><th class="r">루틴</th>${days.map(d => {
        const dt = parse(d);
        const cls = [d === t0 ? 'today' : '', dt.getDay() === 0 || holidayOn(d) ? 'sun' : '', dt.getDay() === 6 ? 'sat' : ''].join(' ');
        return `<th class="d ${cls}" title="${esc(holidayOn(d) || '')}">${dt.getMonth() + 1}/${dt.getDate()}<small>${WD[dt.getDay()]}</small></th>`;
      }).join('')}</tr></thead>
      <tbody>${list.map(r => `<tr><th class="r" title="${esc(r.title)}">${isMixed() && !isTeam(r.spaceId) ? '👤 ' : ''}${esc(r.title)}</th>${days.map(d => cell(r, d)).join('')}</tr>`).join('')}</tbody>
      <tfoot><tr><th class="r">내 달성</th>${days.map(d => {
        const cnt = list.filter(r => iChecked(d, r.id)).length;
        return `<td class="cnt ${d === t0 ? 'today' : ''} ${cnt === list.length ? 'full' : ''}">${cnt}/${list.length}</td>`;
      }).join('')}</tr></tfoot>
    </table></div>` : '<div class="empty">루틴을 추가하면 기록이 쌓입니다</div>'}
  </section>`;

  // 기록 표는 처음엔 오늘(오른쪽 끝)이 보이게, 체크해서 다시 그릴 때는 보던 위치 유지
  const wrap = $('.log-wrap', root);
  if (wrap) {
    requestAnimationFrame(() => { wrap.scrollLeft = UI.logScroll ?? wrap.scrollWidth; });
    wrap.addEventListener('scroll', () => { UI.logScroll = wrap.scrollLeft; });
  }

  const saveOrder = sid => send('PUT', `${base(sid)}/routines/order`, S.routines.filter(r => r.spaceId === sid).map(r => r.id));
  const addForm = $('#r-add', root);
  if (addForm) addForm.addEventListener('submit', e => {
    e.preventDefault();
    const title = e.target.title.value.trim();
    if (!title) return;
    const r = { id: uid(), spaceId: UI.space, version: 0, title };
    S.routines.push(r);
    save('routines', r);
    render();
    $('#r-add input').focus();
  });
  on(root, 'click', '.routine-item [data-act]', el => {
    const rid = el.closest('[data-rid]').dataset.rid;
    const r = byId(S.routines, rid);
    const group = S.routines.filter(x => x.spaceId === r.spaceId); // 순서는 같은 공간 안에서만
    const i = group.indexOf(r);
    const act = el.dataset.act;
    if (act === 'up' || act === 'down') {
      const other = group[act === 'up' ? i - 1 : i + 1];
      if (!other) return;
      const a = S.routines.indexOf(r), b = S.routines.indexOf(other);
      [S.routines[a], S.routines[b]] = [S.routines[b], S.routines[a]];
      saveOrder(r.spaceId);
    } else if (act === 'edit') {
      const title = prompt('루틴 이름', r.title);
      if (!title || !title.trim()) return;
      r.title = title.trim();
      save('routines', r);
    } else if (act === 'del') {
      if (!confirm(`"${r.title}" 루틴과 기록을 삭제할까요?${isTeam(r.spaceId) ? '\n(팀원 모두의 기록이 삭제됩니다)' : ''}`)) return;
      S.routines = S.routines.filter(x => x.id !== rid);
      S.routineChecks = S.routineChecks.filter(c => c.routineId !== rid);
      removeItem('routines', r);
    }
    render();
  });
  on(root, 'change', '[data-check]', el => { setRoutineCheck(byId(S.routines, el.dataset.check), t0, el.checked); render(); });
  on(root, 'click', 'td.c', el => {
    setRoutineCheck(byId(S.routines, el.dataset.r), el.dataset.d, !el.classList.contains('on'));
    render();
  });
}

// ───────── 하루위키 (화면은 wiki.js) ─────────
let noteTimer = null; // 위키 자동 저장 대기 (wiki.js 가 쓴다)

// 하루위키 화면은 wiki.js (renderWiki, flushNote)

// ───────── 스니펫 ─────────
/** 스니펫 언어. SQL 은 자체 강조(Oracle 힌트·바인드 변수), 나머지는 highlight.js */
const LANGS = [
  ['sql', 'SQL'], ['java', 'Java'], ['kotlin', 'Kotlin'], ['python', 'Python'], ['bash', 'Shell'],
  ['powershell', 'PowerShell'], ['javascript', 'JavaScript'], ['typescript', 'TypeScript'], ['go', 'Go'],
  ['json', 'JSON'], ['yaml', 'YAML'], ['xml', 'XML/HTML'], ['ini', 'Properties/INI'],
  ['dockerfile', 'Dockerfile'], ['markdown', 'Markdown'], ['text', '일반 텍스트'],
];
const LANG_NAME = Object.fromEntries(LANGS);
const hasHljs = () => typeof window.hljs !== 'undefined';
const AUTO_LANGS = LANGS.map(l => l[0]).filter(l => l !== 'sql' && l !== 'text');

const SQL_KEYWORDS = new Set(('select from where and or not in is null like between exists group by order having union all '
  + 'intersect minus insert into values update set delete merge using matched when then else end case create alter drop '
  + 'table view index sequence synonym trigger procedure function package body begin declare exception return returns '
  + 'join inner left right full outer cross on as distinct with grant revoke commit rollback savepoint truncate '
  + 'primary key foreign references constraint unique check default asc desc nulls first last fetch next rows only offset '
  + 'for loop while if elsif cursor open close execute immediate replace force connect prior start level partition over '
  + 'pivot unpivot type record of varchar2 number date timestamp clob blob char integer boolean').split(' '));
const SQL_FUNCS = new Set(('count sum avg min max nvl nvl2 coalesce decode to_char to_date to_number trunc round substr '
  + 'instr length upper lower trim ltrim rtrim lpad rpad sysdate systimestamp row_number rank dense_rank lag lead listagg '
  + 'regexp_like regexp_substr regexp_replace cast extract add_months months_between last_day greatest least abs mod '
  + 'floor ceil concat dbms_output sys_context').split(' '));

function isSql(code) {
  return /^\s*(--|\/\*|(select|with|insert|update|delete|merge|create|alter|drop|begin|declare|grant|exec|truncate)\b)/i.test(code);
}

/** 정규식 기반 SQL 구문 강조. 결과는 이스케이프된 HTML */
function highlightSql(code) {
  const re = /(\/\*\+[\s\S]*?\*\/)|(--[^\n]*|\/\*[\s\S]*?\*\/)|('(?:[^']|'')*'?)|("(?:[^"]|"")*"?)|(:[A-Za-z_]\w*)|(\b\d+(?:\.\d+)?\b)|([A-Za-z_][\w$#]*)/g;
  let out = '', last = 0, m;
  while ((m = re.exec(code))) {
    out += esc(code.slice(last, m.index));
    const [tok, hint, comment, str, quoted, bind, num, word] = m;
    let cls = '';
    if (hint) cls = 'sh';
    else if (comment) cls = 'sc';
    else if (str) cls = 'ss';
    else if (quoted) cls = 'si';
    else if (bind) cls = 'sb';
    else if (num) cls = 'sn';
    else if (word) {
      const w = word.toLowerCase();
      if (SQL_KEYWORDS.has(w)) cls = 'sk';
      else if (SQL_FUNCS.has(w) || /^\s*\(/.test(code.slice(re.lastIndex))) cls = 'sf';
    }
    out += cls ? `<span class="${cls}">${esc(tok)}</span>` : esc(tok);
    last = re.lastIndex;
  }
  return out + esc(code.slice(last));
}

/** 언어 판단: 직접 고른 값 → SQL 모양 → highlight.js 자동 인식 → 일반 텍스트 */
const langCache = new Map();
function snippetLang(s) {
  if (s.lang && LANG_NAME[s.lang]) return s.lang;
  const code = s.code || '';
  if (isSql(code)) return 'sql';
  if (!hasHljs() || !code.trim()) return 'text';
  if (!langCache.has(code)) {
    const r = hljs.highlightAuto(code, AUTO_LANGS.filter(l => hljs.getLanguage(l)));
    langCache.set(code, r.relevance >= 4 && r.language ? r.language : 'text');
  }
  return langCache.get(code);
}

function highlight(code, lang) {
  if (lang === 'sql') return highlightSql(code);
  if (lang !== 'text' && hasHljs() && hljs.getLanguage(lang)) {
    try { return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value; } catch (e) { /* 아래로 */ }
  }
  return esc(code);
}

function renderSnippets(root) {
  const mine = inCur(S.snippets);
  const tags = tagsOf(mine);
  const editable = canEdit();   // 새로 만들기 (저장 위치 기준)
  const q = UI.snipQuery.trim().toLowerCase();
  const list = mine
    .filter(s => (!UI.snipTag || s.tag === UI.snipTag) && (!q || (s.title + ' ' + s.code + ' ' + s.memo + ' ' + s.tag).toLowerCase().includes(q)))
    .sort((a, b) => (b.updatedAt || '').localeCompare(a.updatedAt || ''));

  root.innerHTML = `
  <div class="filters">
    ${editable ? `<button data-act="new">+ 새 스니펫</button>${targetHint()}` : readonlyNote()}
    <span class="chip ${!UI.snipTag ? 'on' : ''}" data-tag="">전체 <span class="n">${mine.length}</span></span>
    ${tags.map(t => `<span class="chip ${UI.snipTag === t ? 'on' : ''}" data-tag="${esc(t)}">${esc(t)} <span class="n">${mine.filter(s => s.tag === t).length}</span></span>`).join('')}
    <span class="grow"></span>
    <input type="search" id="snip-q" placeholder="검색" value="${esc(UI.snipQuery)}">
  </div>
  <div class="snips">${list.length ? list.map(s => {
    const lang = snippetLang(s);
    return `<section class="panel snip" data-id="${esc(s.id)}">
      <div class="snip-head">
        <span class="t" title="${esc(s.title)}">${esc(s.title || '(제목 없음)')}</span>
        ${mixedTag(s.spaceId)}
        ${s.tag ? `<span class="tag">${esc(s.tag)}</span>` : ''}
        <span class="badge lang" title="${s.lang ? '직접 선택' : '자동 판단'}">${esc(LANG_NAME[lang] || lang)}</span>
        <button class="ghost small" data-act="copy">복사</button>
        <button class="icon" data-act="edit">${canEdit(s.spaceId) ? '수정' : '보기'}</button>
      </div>
      <pre><code class="code">${highlight(s.code || '', lang)}</code></pre>
      ${s.memo ? `<div class="memo">${esc(s.memo)}</div>` : ''}
      ${isTeam(s.spaceId) && s.updatedBy ? `<div class="muted small">${esc(userName(s.updatedBy))} · ${s.updatedAt ? timeAgo(s.updatedAt) : ''}</div>` : ''}
    </section>`;
  }).join('') : '<div class="empty panel">스니펫이 없습니다</div>'}</div>`;

  on(root, 'click', '[data-tag]', el => { UI.snipTag = el.dataset.tag; render(); });
  $('#snip-q', root).addEventListener('input', e => {
    UI.snipQuery = e.target.value;
    render();
    const input = $('#snip-q');
    input.focus();
    input.setSelectionRange(input.value.length, input.value.length);
  });
  on(root, 'click', '[data-act="new"]', () => editSnippet(null));
  on(root, 'click', '.snip [data-act]', async el => {
    const s = byId(S.snippets, el.closest('.snip').dataset.id);
    if (el.dataset.act === 'edit') editSnippet(s.id);
    if (el.dataset.act === 'copy') {
      try {
        await navigator.clipboard.writeText(s.code || '');
        el.textContent = '복사됨 ✓';
      } catch (e) {
        el.textContent = '복사 실패';
      }
      setTimeout(() => { el.textContent = '복사'; }, 1200);
    }
  });
}

function editSnippet(id) {
  const s = id ? byId(S.snippets, id) : null;
  const sid = s ? s.spaceId : UI.space;
  openForm({
    title: s ? '스니펫 수정' : '스니펫 추가',
    fields: [
      { name: 'title', label: '제목', required: true },
      { name: 'tag', label: '분류', list: tagsOf(S.snippets.filter(x => x.spaceId === sid)) },
      { name: 'lang', label: '언어', type: 'select', options: [['', '자동 판단'], ...LANGS] },
      ...(canEdit(sid) ? spaceField() : []),
      { name: 'code', label: '코드', type: 'textarea', full: true },
      { name: 'memo', label: '메모', type: 'textarea', full: true },
    ],
    values: { ...(s || { lang: '' }), spaceId: sid },
    readOnly: !canEdit(sid),
    onSubmit: v => {
      const { spaceId: to = sid, ...fields } = v;
      const item = s || { id: uid(), spaceId: to, version: 0 };
      Object.assign(item, { ...fields, title: fields.title.trim(), tag: fields.tag.trim(), updatedAt: nowIso(), updatedBy: S.me.id });
      if (!s) {
        S.snippets.push(item);
        save('snippets', item);
        if (!inView(to)) showSpace(to); else render();
        return;
      }
      if (to !== item.spaceId) { moveItem('snippets', item, to); return; }
      save('snippets', item);
      render();
    },
    onDelete: s ? () => {
      S.snippets = S.snippets.filter(x => x.id !== s.id);
      removeItem('snippets', s);
      render();
    } : null,
  });
  const code = $('#dlg textarea[name=code]');
  code.classList.add('mono');
  code.rows = 12;
  code.spellcheck = false;
}

// ───────── 공간 설정·새 팀·내 계정 ─────────
function newTeamDialog() {
  openForm({
    title: '새 팀 공간',
    fields: [
      { name: 'name', label: '팀 이름', full: true, required: true },
      { name: 'color', label: '색', type: 'palette', full: true },
    ],
    values: { color: COLORS[1] },
    submitLabel: '만들기',
    onSubmit: async v => {
      try {
        const res = await request('POST', '/api/spaces', { name: v.name.trim(), color: v.color });
        await loadAll();
        setView('t:' + res.id);
        spaceSettings(res.id);
      } catch (e) { alert('만들 수 없습니다: ' + e.message); }
    },
  });
}

function spaceSettings(sid = UI.space) {
  const s = spaceById(sid) || curSpace();
  const owner = s.role === 'owner';
  const team = s.kind === 'team';
  const dlg = $('#dlg');
  dlg.innerHTML = `<form method="dialog" class="space-settings">
    <h3>${team ? '👥' : '👤'} 공간 설정</h3>
    <div class="fields">
      <label class="field"><span>이름</span><input type="text" name="name" value="${esc(s.name)}" ${owner ? '' : 'disabled'} maxlength="50"></label>
      <div class="field"><span>색</span><div class="palette">${COLORS.map((c, i) =>
        `<input type="radio" name="color" id="sc${i}" value="${c}" ${s.color === c ? 'checked' : ''} ${owner ? '' : 'disabled'}><label for="sc${i}" style="background:${c}"></label>`).join('')}</div></div>
    </div>
    ${owner ? '<div class="dlg-acts"><span class="grow"></span><button type="button" data-act="save">이름·색 저장</button></div>' : ''}
    ${team ? `
    <h3 style="margin-top:6px">멤버 ${s.members.length}명</h3>
    <div class="member-list">${s.members.map(m => `<div class="member" data-uid="${esc(m.id)}">
      ${avatar(m.id)}<span class="grow">${esc(m.name)}${m.id === S.me.id ? ' <span class="muted small">(나)</span>' : ''}</span>
      ${owner ? `<select data-act="role">${Object.entries(ROLE).map(([k, l]) => `<option value="${k}" ${m.role === k ? 'selected' : ''}>${l}</option>`).join('')}</select>`
        : `<span class="muted small">${ROLE[m.role]}</span>`}
      ${S.me.admin && m.id !== S.me.id ? '<button type="button" class="ghost small" data-act="reset">비밀번호 재설정 링크</button>' : ''}
      ${owner && m.id !== S.me.id ? '<button type="button" class="icon" data-act="kick" title="내보내기">✕</button>' : ''}
    </div>`).join('')}</div>
    ${owner ? `<div class="invite-box">
      <div class="row"><b class="small">초대 링크</b><span class="muted small">7일 · 1명만 사용 가능</span><span class="grow" style="flex:1"></span>
        <select id="inv-role">${Object.entries(ROLE).filter(([k]) => k !== 'owner').map(([k, l]) => `<option value="${k}">${l} 권한</option>`).join('')}</select>
        <button type="button" class="small" data-act="invite">링크 만들기</button></div>
      <div class="row hidden" id="inv-out"><input type="text" readonly id="inv-url" style="flex:1"><button type="button" class="ghost small" data-act="copy">복사</button></div>
    </div>` : ''}
    <div class="dlg-acts">
      <button type="button" class="danger" data-act="leave">공간 나가기</button>
      ${owner ? '<button type="button" class="danger" data-act="delete">팀 공간 삭제</button>' : ''}
      <span class="grow"></span><button type="button" class="ghost" data-act="close">닫기</button>
    </div>` : `<p class="muted small">개인 공간은 나만 봅니다. 팀과 공유하려면 상단 공간 선택에서 "새 팀 공간"을 만드세요.</p>
    <div class="dlg-acts"><span class="grow"></span><button type="button" class="ghost" data-act="close">닫기</button></div>`}
  </form>`;
  const form = $('form', dlg);
  const refresh = async () => { await loadAll(); render(); if (spaceById(s.id)) spaceSettings(s.id); else dlg.close(); };
  const fail = e => alert(e.message);
  form.addEventListener('submit', e => e.preventDefault());
  on(form, 'change', '[data-act="role"]', async el => {
    const uid2 = el.closest('[data-uid]').dataset.uid;
    try { await request('PUT', `${base(s.id)}/members/${uid2}`, { role: el.value }); await refresh(); } catch (e) { fail(e); await refresh(); }
  });
  on(form, 'click', 'button[data-act]', async el => {
    const act = el.dataset.act;
    const memberId = el.closest('[data-uid]') && el.closest('[data-uid]').dataset.uid;
    try {
      if (act === 'close') dlg.close();
      if (act === 'save') {
        const color = (form.querySelector('input[name=color]:checked') || {}).value || s.color;
        await request('PUT', base(s.id), { name: form.elements.name.value, color });
        await refresh();
      }
      if (act === 'invite') {
        const res = await request('POST', `${base(s.id)}/invites`, { role: $('#inv-role').value });
        $('#inv-url').value = `${location.origin}/invite.html#${res.token}`;
        $('#inv-out').classList.remove('hidden');
        $('#inv-url').select();
      }
      if (act === 'copy') {
        await navigator.clipboard.writeText($('#inv-url').value);
        el.textContent = '복사됨 ✓';
      }
      if (act === 'reset') {
        const res = await request('POST', `/api/users/${memberId}/reset-link`);
        prompt('비밀번호 재설정 링크 (1일 · 1회용). 본인에게 전달하세요:', `${location.origin}/invite.html#${res.token}`);
      }
      if (act === 'kick' && confirm(`${userName(memberId)} 님을 이 공간에서 내보낼까요?`)) {
        await request('DELETE', `${base(s.id)}/members/${memberId}`);
        await refresh();
      }
      if (act === 'leave' && confirm(`"${s.name}" 공간에서 나갈까요?`)) {
        await request('DELETE', `${base(s.id)}/members/${S.me.id}`);
        dlg.close();
        UI.space = null;
        await loadAll();
        render();
      }
      if (act === 'delete' && prompt(`"${s.name}" 공간과 안의 데이터가 모두 삭제됩니다.\n확인하려면 공간 이름을 입력하세요.`) === s.name) {
        await request('DELETE', base(s.id));
        dlg.close();
        UI.space = null;
        await loadAll();
        render();
      }
    } catch (e) { fail(e); }
  });
  if (!dlg.open) dlg.showModal();
}

function accountDialog() {
  const pwSet = S.me.passwordSet !== false;
  const google = S.google || {};
  const fields = [{ name: 'name', label: '표시 이름', full: true, required: true }];
  if (pwSet) fields.push({ name: 'current', label: '현재 비밀번호 (변경할 때만)', type: 'password', full: true, autocomplete: 'current-password' });
  fields.push(
    { name: 'next', label: pwSet ? '새 비밀번호 (12자 이상)' : '비밀번호 정하기 (12자 이상, 선택)', type: 'password', autocomplete: 'new-password' },
    { name: 'next2', label: '새 비밀번호 확인', type: 'password', autocomplete: 'new-password' },
  );
  const dlg = openForm({
    title: `내 계정 · ${S.me.login}`,
    fields,
    values: { name: S.me.name },
    meta: pwSet ? '' : 'Google 로 가입한 계정이라 비밀번호가 없습니다. 정해 두면 아이디로도 로그인할 수 있습니다.',
    onSubmit: async v => {
      try {
        if (v.name.trim() !== S.me.name) await request('PUT', '/api/me', { name: v.name.trim() });
        if (v.next || v.current) {
          if (v.next !== v.next2) { alert('새 비밀번호가 서로 다릅니다'); return; }
          await request('PUT', '/api/me/password', { current: v.current || '', next: v.next });
          alert('비밀번호를 정했습니다. 다른 기기에서는 다시 로그인해야 할 수 있습니다.');
        }
        await loadAll();
        render();
      } catch (e) { alert(e.message); }
    },
  });
  // Google 연결 상태 (서버에 Google 로그인이 설정돼 있을 때만)
  if (!google.enabled) return;
  const box = document.createElement('div');
  box.className = 'account-google';
  box.innerHTML = google.email
    ? `<span class="grow">🔗 Google 연결됨 · <b>${esc(google.email)}</b></span><button type="button" class="ghost small" data-g="unlink">연결 해제</button>`
    : `<span class="grow muted">Google 계정을 연결하면 Google 버튼으로 로그인할 수 있습니다</span><button type="button" class="small" data-g="link">Google 연결</button>`;
  $('.fields', dlg).after(box);
  box.addEventListener('click', async e => {
    const act = e.target.dataset.g;
    if (!act) return;
    try {
      if (act === 'link') {
        const res = await request('POST', '/api/me/google');
        location.href = res.url; // Google 로그인 → 돌아오면 연결 완료
      } else if (confirm('Google 연결을 해제할까요? 이후에는 아이디·비밀번호로 로그인합니다.')) {
        await request('DELETE', '/api/me/google');
        await loadAll();
        dlg.close();
        accountDialog();
      }
    } catch (ex) { alert(ex.message); }
  });
}

// ───────── 상단 도구: 공간·내보내기·가져오기·테마·로그아웃 ─────────
function exportSpace() {
  const s = curSpace();
  const pick = list => list.filter(x => x.spaceId === s.id);
  const routines = pick(S.routines);
  const rids = new Set(routines.map(r => r.id));
  const data = {
    format: 'haruhanpan-space', exportedAt: nowIso(), spaceName: s.name,
    todos: pick(S.todos), events: pick(S.events), notes: pick(S.notes), snippets: pick(S.snippets), routines,
    routineChecks: S.routineChecks.filter(c => rids.has(c.routineId)),
    dayOffs: S.spaceDayOffs[s.id] || {},
  };
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `haruhanpan_${s.name.replace(/[\\/:*?"<>|\s]+/g, '_')}_${today()}.json`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

async function importSpace(file) {
  const s = curSpace();
  let data;
  try { data = JSON.parse(await file.text()); } catch (e) { alert('JSON 파일이 아닙니다'); return; }
  // 서버 일일 백업 파일이면 그 안의 공간 하나를 고른다
  if (data && Array.isArray(data.spaces)) {
    const names = data.spaces.map((x, i) => `${i + 1}. ${x.name}`).join('\n');
    const n = Number(prompt(`백업 파일에 공간이 여러 개 있습니다. "${s.name}" 에 넣을 번호를 고르세요:\n${names}`, '1'));
    if (!n || !data.spaces[n - 1]) return;
    data = data.spaces[n - 1].data;
  }
  if (!data || typeof data !== 'object' || !Array.isArray(data.todos)) { alert('하루한판 내보내기 파일이 아닙니다'); return; }
  if (!confirm(`"${s.name}" 공간의 데이터를 이 파일 내용으로 모두 바꿉니다.\n(서버에 교체 전 백업이 자동 저장됩니다)\n계속할까요?`)) return;
  try {
    setStatus('가져오는 중…');
    await request('POST', `${base(s.id)}/import`, data);
    await loadAll();
    render();
    setStatus('가져오기 완료');
  } catch (e) {
    setStatus('가져오기 실패 — ' + e.message, true);
  }
}

function bindTools() {
  $('#space-sel').addEventListener('change', e => {
    if (e.target.value === '__new') { e.target.value = viewTeam() ? 't:' + viewTeam() : 'p'; newTeamDialog(); return; }
    flushNote();
    // 팀을 고르면 지난번 "내 공간도 같이" 선택을 이어 간다
    let both = false;
    try { both = localStorage.getItem('withMine') === '1'; } catch (x) { /* 무시 */ }
    setView(e.target.value.startsWith('t:') && both ? 'p' + e.target.value : e.target.value);
  });
  $('#with-mine-cb').addEventListener('change', e => {
    const team = viewTeam();
    if (!team) return;
    try { localStorage.setItem('withMine', e.target.checked ? '1' : '0'); } catch (x) { /* 무시 */ }
    flushNote();
    setView((e.target.checked ? 'pt:' : 't:') + team);
  });
  // 사이드바 접기 (넓게 보기). 접힌 상태는 기억한다
  const applyMini = mini => {
    document.body.classList.toggle('nav-mini', mini);
    $('#btn-collapse').textContent = mini ? '»' : '«';
    $('#btn-collapse').title = mini ? '사이드바 펼치기' : '사이드바 접기';
  };
  try { applyMini(localStorage.getItem('navMini') === '1'); } catch (e) { /* 무시 */ }
  $('#btn-collapse').addEventListener('click', () => {
    const mini = !document.body.classList.contains('nav-mini');
    applyMini(mini);
    try { localStorage.setItem('navMini', mini ? '1' : '0'); } catch (e) { /* 무시 */ }
  });
  // 휴대폰: 서랍 열고 닫기
  const openNav = () => document.body.classList.add('nav-open');
  $('#btn-nav').addEventListener('click', openNav);
  $('#sn-scrim').addEventListener('click', () => document.body.classList.remove('nav-open'));
  $('#bottombar').addEventListener('click', e => { if (e.target.closest('#bb-more')) openNav(); });
  $('#tabs').addEventListener('click', () => document.body.classList.remove('nav-open'));
  // ⚙ 은 보기 범위에 팀이 있으면 그 팀, 아니면 내 공간 설정
  $('#btn-space').addEventListener('click', () => spaceSettings(viewTeam() || personalId()));
  $('#btn-account').addEventListener('click', accountDialog);
  $('#btn-pending').addEventListener('click', pendingDialog);
  // 하루가계부는 공간 하나씩 본다: 지금 저장 위치(팀만 볼 땐 팀, 그 외엔 내 공간)의 가계부를 연다
  $('#btn-ledger').addEventListener('click', () => { location.href = '/ledger?space=' + encodeURIComponent(curSpace().id); });
  $('#btn-export').addEventListener('click', exportSpace);

  const file = $('#import-file');
  $('#btn-import').addEventListener('click', () => {
    if (roleIn() !== 'owner') { alert('가져오기는 공간 관리자만 할 수 있습니다'); return; }
    file.click();
  });
  file.addEventListener('change', () => {
    const f = file.files[0];
    file.value = '';
    if (f) importSpace(f);
  });

  $('#btn-theme').addEventListener('click', () => {
    const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = next;
    try { localStorage.setItem('theme', next); } catch (e) { /* 저장소 차단 */ }
  });

  $('#btn-logout').addEventListener('click', async () => {
    flushNote();
    for (const path of [...pending.keys()]) await flush(path);
    try { await request('POST', '/api/logout'); } catch (e) { /* 이미 만료 */ }
    location.replace('/login.html');
  });
}

// ───────── 시작 ─────────
async function init() {
  bindTools();
  try {
    await loadAll();
  } catch (e) {
    if (e.status === 401) { location.replace('/login.html'); return; }
    if (e.status === 403 && e.message === 'approval_pending') { renderPendingScreen(); return; }
    $('#main').innerHTML = '<div class="empty">서버에 연결할 수 없습니다. 잠시 후 새로고침하세요.</div>';
    return;
  }

  window.addEventListener('hashchange', () => { flushNote(); UI.logScroll = null; render(); });
  render();
  if (new URLSearchParams(location.search).get('google') === 'linked') {
    setStatus(`Google 계정을 연결했습니다 (${(S.google || {}).email || ''})`);
    history.replaceState(null, '', '/board' + location.hash);
  }

  // 다른 사람의 변경: 30초마다, 그리고 탭으로 돌아올 때 받아온다
  setInterval(() => { if (document.visibilityState === 'visible') reload(); }, 30000);
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') reload(); });

  // 자정이 지나면 다시 그린다
  let day = today();
  setInterval(() => {
    if (today() !== day) { day = today(); UI.calMonth = null; UI.calSel = null; render(); }
  }, 60000);
}

init();
