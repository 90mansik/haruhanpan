'use strict';
/* 홈: 하루한판 · 하루가계부 고르기. 카드마다 간단한 요약을 보여 준다.
 * 하루한판 요약: 내 할 일(개인 공간 전부 + 팀 공간은 나에게 맡겨진 것)과 오늘 루틴 (GET /api/all)
 * 하루가계부 요약: 마지막으로 본 가계부 공간의 이번 달 (GET /api/ledger/spaces/{sid}?month=)
 */

const $ = s => document.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const pad = n => String(n).padStart(2, '0');
const ymd = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const today = () => ymd(new Date());
const won = n => Number(n || 0).toLocaleString('ko-KR') + '원';
const WD = ['일', '월', '화', '수', '목', '금', '토'];
const addMonth = (ym, n) => { const [y, m] = ym.split('-').map(Number); const d = new Date(y, m - 1 + n, 1); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}`; };

function csrfToken() {
  const m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : '';
}
class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
async function request(method, path) {
  const res = await fetch(path, { method, headers: { 'X-XSRF-TOKEN': csrfToken() }, credentials: 'same-origin' });
  let json = {};
  try { json = await res.json(); } catch (e) { /* 본문 없음 */ }
  if (!res.ok) throw new ApiError(res.status, json.error || String(res.status));
  return json;
}

const stat = (label, value, cls = '') => `<div class="app-stat ${cls}"><span>${esc(label)}</span><b>${esc(value)}</b></div>`;

function boardStats(data) {
  const t = today();
  const team = new Set(data.spaces.filter(s => s.kind === 'team').map(s => s.id));
  const mine = data.todos.filter(x => !x.done && (!team.has(x.spaceId) || x.assignee === data.me.id));
  const dueToday = mine.filter(x => x.due === t).length;
  const over = mine.filter(x => x.due && x.due < t).length;
  const routines = data.routines.length;
  const checked = data.routineChecks.filter(c => c.date === t && c.userId === data.me.id).length;
  $('#board-stats').innerHTML = stat('남은 할 일', `${mine.length}개`)
    + stat('오늘 마감', `${dueToday}개`, dueToday ? 'warn' : '')
    + stat('지난 마감', `${over}개`, over ? 'danger' : '')
    + (routines ? stat('오늘 루틴', `${Math.min(checked, routines)} / ${routines}`) : '');
}

/** 이번 달 (월 시작일이 1일이 아니면 오늘이 들어 있는 달로 맞춘다) */
async function ledgerMonth(sid) {
  let month = today().slice(0, 7);
  let data = await request('GET', `/api/ledger/spaces/${encodeURIComponent(sid)}?month=${month}`);
  const shift = today() > data.to ? 1 : today() < data.from ? -1 : 0;
  if (shift) {
    month = addMonth(month, shift);
    data = await request('GET', `/api/ledger/spaces/${encodeURIComponent(sid)}?month=${month}`);
  }
  return data;
}

async function ledgerStats(spaces) {
  let sid = null;
  try { sid = localStorage.getItem('ledgerSpace'); } catch (e) { /* 무시 */ }
  const space = spaces.find(s => s.id === sid) || spaces.find(s => s.kind === 'personal') || spaces[0];
  const d = await ledgerMonth(space.id);
  const s = d.summary;
  // 아직 기록되지 않은 이번 달 정기결제 (하루가계부와 같은 계산)
  const due = r => {
    for (let ym = d.from.slice(0, 7); ym <= d.to.slice(0, 7); ym = addMonth(ym, 1)) {
      const [y, m] = ym.split('-').map(Number);
      const day = `${ym}-${pad(Math.min(r.dayOfMonth, new Date(y, m, 0).getDate()))}`;
      if (day >= d.from && day <= d.to && day >= r.startDate && (!r.endDate || day <= r.endDate)) return day;
    }
    return null;
  };
  const left = d.recurring.filter(r => r.active).map(r => ({ r, day: due(r) }))
    .filter(p => p.day && !d.recurringRuns.some(x => x.recurringId === p.r.id && x.month === p.day.slice(0, 7)));
  const [, m] = d.month.split('-');
  $('#ledger-sub').textContent = `${space.kind === 'team' ? '👥 ' : '👤 '}${space.name} · ${Number(m)}월`;
  $('#card-ledger').href = '/ledger?space=' + encodeURIComponent(space.id);
  $('#ledger-stats').innerHTML = stat('지출', won(s.expense))
    + stat('수입', won(s.income), 'income')
    + stat('저축', won(s.saving), 'saving')
    + (left.length ? stat('남은 정기결제', `${left.length}건 · ${won(left.reduce((a, p) => a + p.r.amount, 0))}`) : '');
}

function bindTools() {
  $('#btn-theme').addEventListener('click', () => {
    const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = next;
    try { localStorage.setItem('theme', next); } catch (e) { /* 저장소 차단 */ }
  });
  $('#btn-logout').addEventListener('click', async () => {
    try { await request('POST', '/api/logout'); } catch (e) { /* 이미 만료 */ }
    location.replace('/login.html');
  });
}

async function init() {
  bindTools();
  const d = new Date();
  $('#today').textContent = `${d.getMonth() + 1}월 ${d.getDate()}일 (${WD[d.getDay()]})`;
  let data;
  try {
    data = await request('GET', '/api/all');
  } catch (e) {
    if (e.status === 401) { location.replace('/login.html'); return; }
    if (e.status === 403) { location.replace('/board'); return; } // 승인 대기: 하루한판이 안내한다
    $('#board-stats').innerHTML = $('#ledger-stats').innerHTML = '<span class="muted small">불러오지 못했습니다</span>';
    return;
  }
  $('#me-name').textContent = data.me.name || data.me.login;
  $('#hello').textContent = `안녕하세요, ${data.me.name || data.me.login}님`;
  const pendingCount = (data.pendingUsers || []).length;
  if (pendingCount) {
    const p = $('#pending');
    p.textContent = `승인 대기 ${pendingCount}`;
    p.classList.remove('hidden');
  }
  boardStats(data);
  if (new URLSearchParams(location.search).get('ledger') === 'denied') {
    $('#notice').textContent = '하루가계부는 사이트 관리자가 허락한 사람만 쓸 수 있어요. 관리자에게 요청해 주세요.';
    $('#notice').classList.remove('hidden');
    history.replaceState(null, '', '/');
  }
  if (!data.me.ledger) { $('#card-ledger').remove(); return; } // 하루가계부 권한이 없으면 카드를 보이지 않는다
  try {
    await ledgerStats(data.spaces);
  } catch (e) {
    $('#ledger-stats').innerHTML = '<span class="muted small">불러오지 못했습니다</span>';
  }
}

init();
