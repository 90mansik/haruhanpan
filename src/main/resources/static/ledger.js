'use strict';
/* 하루가계부 화면 (하루한판 app.js 와 따로)
 * 장부 = 공간 하나. 한 달씩 서버에서 받는다 (GET /api/ledger/spaces/{sid}?month=YYYY-MM).
 * 합계·요약은 서버가 계산하고, 저장·삭제 후에는 그 달을 다시 받는다.
 * 한 달은 "월 시작일"부터 다음 달 시작일 전날까지 (시작일 25일 → 2월 = 1/25 ~ 2/24).
 * 내역은 목록 / 달력 두 가지로 본다. 달력에서 날짜를 누르면 그날 상세 창(내역·결제 예정·이 날 추가)이 열린다.
 * 정기결제는 서버가 결제일에 내역을 자동으로 만든다. 화면은 관리와 "예정" 표시만 한다.
 * 월간 / 연간 두 화면. 연간은 GET /api/ledger/spaces/{sid}/year?year=YYYY (달 구분은 월 시작일 기준).
 */

// ───────── 상태 ─────────
const L = {
  me: { id: '', name: '', login: '' }, spaces: [],
  sid: null,        // 보고 있는 공간
  month: null,      // 'YYYY-MM'
  data: null,       // 그 달 응답 {settings, categories, from, to, entries, summary}
  filter: 'all',    // 내역 목록: all · income · saving · expense
  view: 'list',     // 내역 보기: list · calendar (브라우저에 기억)
  mode: 'month',    // 화면: month · year (브라우저에 기억)
  year: null,       // 연간에서 보는 해
  ydata: null,      // 연간 응답 {year, from, to, settings, categories, months, summary, savingByMajor, savingTotals}
  last: {},         // 빠른 입력에서 마지막으로 고른 값 (구분·날짜·결제수단·사람)
};
const KIND = { income: '수입', saving: '저축', fixed: '고정지출', variable: '변동지출' };
const CAT_KIND = k => (k === 'fixed' || k === 'variable') ? 'expense' : k;

// ───────── 공통 유틸 ─────────
const $ = (s, r = document) => r.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
const pad = n => String(n).padStart(2, '0');
const ymd = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const parse = s => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); };
const today = () => ymd(new Date());
const WD = ['일', '월', '화', '수', '목', '금', '토'];
const fmtDate = s => { const d = parse(s); return `${d.getMonth() + 1}/${d.getDate()}(${WD[d.getDay()]})`; };
const won = n => Number(n || 0).toLocaleString('ko-KR') + '원';
const num = n => Number(n || 0).toLocaleString('ko-KR');
/** 좁은 화면 달력용: 1만 이상은 "20.7만" (소수 한 자리, 100만 이상은 정수) */
const short = n => n < 10000 ? num(n) : (n >= 1000000 ? num(Math.round(n / 10000)) : (Math.round(n / 1000) / 10).toString()) + '만';
/** 달력 칸 금액: 넓으면 전체, 좁으면 줄인 값 (CSS 가 하나만 보여 준다) */
const calAmt = (n, sign) => `<span class="full">${sign}${num(n)}</span><span class="short">${sign}${short(n)}</span>`;
const addDays = (s, n) => { const d = parse(s); d.setDate(d.getDate() + n); return ymd(d); };
const digits = s => String(s || '').replace(/[^0-9]/g, '');
const addMonth = (ym, n) => { const [y, m] = ym.split('-').map(Number); const d = new Date(y, m - 1 + n, 1); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}`; };

function on(root, type, selector, fn) {
  root.addEventListener(type, e => {
    const el = e.target.closest(selector);
    if (el && root.contains(el)) fn(el, e);
  });
}

const space = () => L.spaces.find(s => s.id === L.sid);
const personalId = () => (L.spaces.find(s => s.kind === 'personal') || L.spaces[0]).id;
const isTeam = () => (space() || {}).kind === 'team';
const canEdit = () => (space() || {}).role !== 'viewer';
const members = () => (space() || {}).members || [];
function userName(id) {
  if (!id) return '';
  const m = members().find(x => x.id === id);
  return m ? m.name : (id === L.me.id ? L.me.name : '(나간 멤버)');
}

// ───────── 서버 통신 ─────────
function csrfToken() {
  const m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : '';
}
function setStatus(text, isError = false) {
  const el = $('#save-state');
  el.textContent = text;
  el.classList.toggle('err', isError);
}
class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
async function request(method, path, body) {
  let res;
  try {
    res = await fetch(path, {
      method,
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: 'same-origin',
    });
  } catch (e) {
    setStatus('서버에 연결할 수 없습니다', true);
    throw new ApiError(0, '서버에 연결할 수 없습니다');
  }
  if (res.status === 401) {
    setStatus('로그인이 만료되었습니다 — 새로고침 후 다시 로그인하세요', true);
    throw new ApiError(401, '로그인이 만료되었습니다');
  }
  if (res.status === 503) {
    setStatus('DB 에 연결할 수 없습니다', true);
    throw new ApiError(503, 'DB 에 연결할 수 없습니다');
  }
  let json = {};
  try { json = await res.json(); } catch (e) { /* 본문 없음 */ }
  if (!res.ok) throw new ApiError(res.status, json.error || String(res.status));
  return json;
}
const api = sid => `/api/ledger/spaces/${encodeURIComponent(sid)}`;

function savedNow() {
  const d = new Date();
  setStatus(`저장됨 ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`);
}

/** 그 달을 받아 그린다. 처음 열 때는 오늘이 들어 있는 달로 맞춘다 (월 시작일이 1일이 아닐 수 있어서) */
async function loadMonth(fitToday = false) {
  const data = await request('GET', `${api(L.sid)}?month=${L.month}`);
  if (fitToday) {
    const t = today();
    const shift = t > data.to ? 1 : t < data.from ? -1 : 0;
    if (shift) { L.month = addMonth(L.month, shift); return loadMonth(false); }
  }
  L.data = data;
  render();
}

async function loadYear() {
  L.ydata = await request('GET', `${api(L.sid)}/year?year=${L.year}`);
  render();
}
/** 지금 화면(월간·연간)을 다시 받는다 */
const reloadView = () => (L.mode === 'year' ? loadYear() : loadMonth());

function setMode(mode) {
  L.mode = mode;
  try { localStorage.setItem('ledgerMode', mode); } catch (e) { /* 무시 */ }
  if (mode === 'year') { L.year = Number((L.month || today()).slice(0, 4)); loadYear().catch(showError); }
  else loadMonth(!L.month).catch(showError);
}
const modeSeg = () => `<div class="seg" role="group" aria-label="화면">
  <button type="button" class="${L.mode === 'month' ? 'on' : ''}" data-mode="month">월간</button>
  <button type="button" class="${L.mode === 'year' ? 'on' : ''}" data-mode="year">연간</button></div>`;

// ───────── 화면 ─────────
function renderTopbar() {
  const sel = $('#space-sel');
  sel.innerHTML = L.spaces.map(s =>
    `<option value="${esc(s.id)}" ${s.id === L.sid ? 'selected' : ''}>${s.kind === 'team' ? '👥 ' : '👤 '}${esc(s.name)}</option>`).join('')
    + '<option value="__new">＋ 새 팀 공간…</option>';
  sel.style.setProperty('--c', (space() || {}).color || '#2563eb');
  $('#me-name').textContent = L.me.name || L.me.login;
}

function render() {
  renderTopbar();
  const main = $('#main');
  const fresh = main.cloneNode(false); // 이전 화면의 이벤트 리스너를 버린다
  main.replaceWith(fresh);
  on(fresh, 'click', '[data-mode]', el => { if (el.dataset.mode !== L.mode) setMode(el.dataset.mode); });
  bindDonutHover(fresh);
  if (L.mode === 'year') { renderYear(fresh); return; }
  const d = L.data;
  const s = d.summary;
  const [y, m] = L.month.split('-');
  const rate = s.savingRate == null ? '-' : (s.savingRate * 100).toFixed(1) + '%';

  fresh.innerHTML = `
  <div class="ledger-head">
    ${modeSeg()}
    <button class="icon" data-act="prev" title="지난달" aria-label="지난달">◀</button>
    <h2>${y}년 ${Number(m)}월</h2>
    <button class="icon" data-act="next" title="다음 달" aria-label="다음 달">▶</button>
    <span class="muted small">${fmtDate(d.from)} ~ ${fmtDate(d.to)}</span>
    <button class="ghost small" data-act="thisMonth">이번 달</button>
    <span class="grow"></span>
    ${canEdit() ? '' : '<span class="badge lang">보기 전용</span>'}
    <button class="ghost small" data-act="recurring" title="정기결제">🔁<span class="label-text"> 정기결제</span></button>
    <button class="ghost small" data-act="settings" title="분류·설정">⚙<span class="label-text"> 분류·설정</span></button>
  </div>
  <div class="stats ledger-stats">
    ${stat('수입', won(s.income), '', 'income')}
    ${stat('저축', won(s.saving), '', 'saving')}
    ${stat('지출', won(s.expense), `고정 ${won(s.fixed)} · 변동 ${won(s.variable)}`)}
    ${stat('잉여현금', won(s.surplus), '수입 − 저축 − 지출', s.surplus < 0 ? 'neg' : '')}
    ${stat('저축률', rate, '저축 ÷ 수입')}
  </div>
  ${canEdit() ? addForm() : ''}
  ${canEdit() ? '<button type="button" class="lg-fab" data-act="add" title="내역 추가" aria-label="내역 추가">＋</button>' : ''}
  <div class="ledger-grid">
    <section class="panel">
      <div class="panel-head lg-panel-head">
        <div class="seg" role="group" aria-label="내역 보기">
          <button type="button" class="${L.view === 'list' ? 'on' : ''}" data-view="list">목록</button>
          <button type="button" class="${L.view === 'calendar' ? 'on' : ''}" data-view="calendar">달력</button>
        </div>
        ${L.view === 'calendar' ? `<span class="muted small">무지출 <b class="lg-nospend-n">${noSpendDays().length}일</b></span>` : ''}
        <span class="grow"></span>
        ${['all', 'income', 'saving', 'expense'].map(k =>
          `<span class="chip ${L.filter === k ? 'on' : ''}" data-filter="${k}">${{ all: '전체', income: '수입', saving: '저축', expense: '지출' }[k]}</span>`).join('')}
      </div>
      ${L.view === 'calendar' ? calendar() + '<div class="muted small lg-cal-hint">날짜를 누르면 그날 내역을 보고 추가할 수 있습니다</div>' : entryList()}
    </section>
    <div class="stack">
      <section class="panel"><h2>이번 달 지출 구성</h2>${donut(expenseSlices(), s.expense, '지출')}</section>
      <section class="panel"><h2>이번 달 저축·투자 구성</h2>${donut(savingSlices(), s.saving, '저축')}</section>
      <section class="panel">${savingTotalsPanel(d.savingTotals, d.to, s.saving, '이번 달')}</section>
      <section class="panel">${recurringPanel()}</section>
      <section class="panel"><h2>결제수단별 지출</h2>${ranking(s.byMethod, 'method', s.expense, '(결제수단 없음)')}</section>
      ${isTeam() ? `<section class="panel"><h2>사람별</h2>${personTable(s.byPerson)}</section>` : ''}
    </div>
  </div>`;

  on(fresh, 'click', '[data-act]', el => {
    const act = el.dataset.act;
    if (act === 'prev' || act === 'next') { L.month = addMonth(L.month, act === 'prev' ? -1 : 1); loadMonth().catch(showError); }
    if (act === 'thisMonth') { L.month = today().slice(0, 7); loadMonth(true).catch(showError); }
    if (act === 'settings') settingsDialog();
    if (act === 'recurring') recurringDialog();
    if (act === 'add') addDialog();
  });
  on(fresh, 'click', '[data-filter]', el => { L.filter = el.dataset.filter; render(); });
  on(fresh, 'click', '[data-view]', el => {
    L.view = el.dataset.view;
    try { localStorage.setItem('ledgerView', L.view); } catch (e) { /* 무시 */ }
    render();
  });
  on(fresh, 'click', '.lg-cal .day[data-date]', el => dayDialog(el.dataset.date));
  on(fresh, 'click', '.lg-row', el => entryDialog(d.entries.find(e => e.id === el.dataset.id)));
  on(fresh, 'click', '.lg-plan-row[data-rid]', el => recurringForm(L.data.recurring.find(r => r.id === el.dataset.rid)));

  if (canEdit()) bindAddForm($('.ledger-add', fresh));
}

function stat(label, value, sub = '', cls = '') {
  return `<section class="panel stat">
    <div class="label">${esc(label)}</div>
    <div class="value lg-value ${cls}">${esc(value)}</div>
    ${sub ? `<div class="muted small">${esc(sub)}</div>` : ''}
  </section>`;
}

function entryList() {
  const show = e => L.filter === 'all' || CAT_KIND(e.kind) === L.filter;
  const list = L.data.entries.filter(show);
  if (!list.length) return `<div class="empty">${L.data.entries.length ? '해당하는 내역이 없습니다' : '이번 달 내역이 없습니다'}</div>`;
  const days = new Map();
  for (const e of list) {
    if (!days.has(e.date)) days.set(e.date, []);
    days.get(e.date).push(e);
  }
  return [...days].map(([date, rows]) => {
    const spent = rows.filter(e => CAT_KIND(e.kind) === 'expense').reduce((a, e) => a + e.amount, 0);
    return `<div class="lg-day">
      <div class="lg-day-head"><b class="${date === today() ? 'today' : ''}">${fmtDate(date)}</b>
        ${spent ? `<span class="muted small">지출 ${won(spent)}</span>` : ''}</div>
      ${rows.map(entryRow).join('')}
    </div>`;
  }).join('');
}

function entryRow(e) {
  const cat = e.minor ? `${esc(e.major)} › ${esc(e.minor)}` : esc(e.major);
  const meta = [e.method, isTeam() ? (e.personId ? userName(e.personId) : '공동') : ''].filter(Boolean).map(esc).join(' · ');
  const sign = e.kind === 'income' ? '+' : e.kind === 'saving' ? '' : '−';
  return `<div class="lg-row" data-id="${esc(e.id)}" title="${canEdit() ? '눌러서 수정' : '눌러서 보기'}">
    <span class="lg-kind ${e.kind}">${KIND[e.kind]}</span>
    <div class="lg-main">
      <div class="lg-cat">${e.recurringId ? '<span title="정기결제로 자동 기록">🔁</span> ' : ''}${cat}${e.memo ? ` <span class="muted">${esc(e.memo)}</span>` : ''}</div>
      ${meta ? `<div class="muted small">${meta}</div>` : ''}
    </div>
    <span class="lg-amt ${CAT_KIND(e.kind)}">${sign}${won(e.amount)}</span>
  </div>`;
}

/** 날짜별 합계 {date: {income, saving, expense}} */
function dailySums() {
  const sums = {};
  for (const e of L.data.entries) {
    const d = sums[e.date] || (sums[e.date] = { income: 0, saving: 0, expense: 0 });
    d[CAT_KIND(e.kind)] += e.amount;
  }
  return sums;
}

/** 이 달에서 오늘까지 지출이 없었던 날 (앞으로 올 날은 세지 않는다) */
function noSpendDays() {
  const sums = dailySums();
  const days = [];
  const last = L.data.to < today() ? L.data.to : today();
  for (let d = L.data.from; d <= last; d = addDays(d, 1)) {
    if (!(sums[d] && sums[d].expense)) days.push(d);
  }
  return days;
}

/** 이 달 기간(월 시작일 기준)을 일요일부터 토요일까지 채운 달력. 기간 밖 날짜는 흐리게, 누를 수 없음 */
function calendar() {
  const sums = dailySums();
  const noSpend = new Set(noSpendDays());
  const plans = planned();
  const t = today();
  let start = L.data.from;
  while (parse(start).getDay() !== 0) start = addDays(start, -1);
  let end = L.data.to;
  while (parse(end).getDay() !== 6) end = addDays(end, 1);
  const cells = [];
  for (let d = start; d <= end; d = addDays(d, 1)) {
    const inMonth = d >= L.data.from && d <= L.data.to;
    const dt = parse(d);
    const s = sums[d];
    const cls = ['day', dt.getDay() === 0 ? 'sun' : dt.getDay() === 6 ? 'sat' : '', inMonth ? '' : 'other',
      d === t ? 'today' : ''].filter(Boolean).join(' ');
    // 월 시작일이 1일이 아니면 달이 바뀌는 날에 월을 같이 적는다
    const label = dt.getDate() === 1 || d === L.data.from ? `${dt.getMonth() + 1}/${dt.getDate()}` : dt.getDate();
    cells.push(`<div class="${cls}" ${inMonth ? `data-date="${d}" title="${fmtDate(d)} — 눌러서 그날 내역 보기"` : ''}>
      <div class="num">${label}</div>
      ${inMonth && s ? `
        ${s.expense ? `<div class="lg-c-amt expense" title="지출 ${won(s.expense)}">${calAmt(s.expense, '−')}</div>` : ''}
        ${s.income ? `<div class="lg-c-amt income" title="수입 ${won(s.income)}">${calAmt(s.income, '+')}</div>` : ''}
        ${s.saving ? `<div class="lg-c-amt saving" title="저축 ${won(s.saving)}">${calAmt(s.saving, '')}</div>` : ''}` : ''}
      ${inMonth && noSpend.has(d) ? '<div class="lg-nospend">무지출</div>' : ''}
      ${inMonth ? plans.filter(p => p.due === d).map(p =>
        `<div class="lg-c-plan" title="결제 예정 ${esc(p.r.name)} ${won(p.r.amount)}">🔁<span class="nm"> ${esc(p.r.name)}</span></div>`).join('') : ''}
    </div>`);
  }
  return `<div class="cal lg-cal">${WD.map((w, i) => `<div class="wd ${i === 0 ? 'sun' : i === 6 ? 'sat' : ''}">${w}</div>`).join('')}${cells.join('')}</div>`;
}

// ───────── 연간 ─────────
function renderYear(fresh) {
  const y = L.ydata;
  const s = y.summary;
  const rate = s.savingRate == null ? '-' : (s.savingRate * 100).toFixed(1) + '%';
  // 월평균 지출: 기록이 있는 달로 나눈다 (가계부를 연중에 시작했거나 아직 안 온 달이 있어도 맞게)
  const passed = y.months.filter(m => m.count > 0).length || 1;
  const avg = Math.round(s.expense / passed);
  const savingRows = Object.fromEntries(y.savingByMajor.map(r => [r.major, r.amount]));
  fresh.innerHTML = `
  <div class="ledger-head">
    ${modeSeg()}
    <button class="icon" data-act="prevY" title="지난해" aria-label="지난해">◀</button>
    <h2>${y.year}년</h2>
    <button class="icon" data-act="nextY" title="다음 해" aria-label="다음 해">▶</button>
    <span class="muted small">${y.from.replaceAll('-', '.')} ~ ${y.to.replaceAll('-', '.')}</span>
    <button class="ghost small" data-act="thisYear">올해</button>
    <span class="grow"></span>
    <button class="ghost small" data-act="settings" title="분류·설정">⚙<span class="label-text"> 분류·설정</span></button>
  </div>
  <div class="stats ledger-stats">
    ${stat('연간 수입', won(s.income), '', 'income')}
    ${stat('연간 저축', won(s.saving), '', 'saving')}
    ${stat('연간 지출', won(s.expense), `월평균 ${won(avg)} (기록 ${passed}개월)`)}
    ${stat('잉여현금', won(s.surplus), '수입 − 저축 − 지출', s.surplus < 0 ? 'neg' : '')}
    ${stat('저축률', rate, '저축 ÷ 수입')}
  </div>
  <div class="ledger-grid">
    <section class="panel">
      <div class="panel-head"><h2>월별 수입·지출</h2><span class="grow"></span>
        <span class="lg-key"><i style="background:var(--s3)"></i>수입</span>
        <span class="lg-key"><i style="background:var(--s1)"></i>지출</span></div>
      ${monthBars(y.months)}
      ${monthTable(y.months, s)}
    </section>
    <div class="stack">
      <section class="panel"><h2>${y.year}년 지출 구성</h2>${donut(slices(Object.fromEntries(s.byMajor.map(r => [r.major, r.amount])), 'expense', y.categories), s.expense, '지출', '올해')}</section>
      <section class="panel"><h2>${y.year}년 저축·투자 구성</h2>${donut(slices(savingRows, 'saving', y.categories), s.saving, '저축', '올해')}</section>
      <section class="panel">${savingTotalsPanel(y.savingTotals, y.to, s.saving, '올해')}</section>
      <section class="panel"><h2>결제수단별 지출</h2>${ranking(s.byMethod, 'method', s.expense, '(결제수단 없음)')}</section>
      ${isTeam() ? `<section class="panel"><h2>사람별</h2>${personTable(s.byPerson)}</section>` : ''}
    </div>
  </div>`;
  on(fresh, 'click', '[data-act]', el => {
    const act = el.dataset.act;
    if (act === 'prevY' || act === 'nextY') { L.year += act === 'prevY' ? -1 : 1; loadYear().catch(showError); }
    if (act === 'thisYear') { L.year = Number(today().slice(0, 4)); loadYear().catch(showError); }
    if (act === 'settings') settingsDialog(y);
  });
  // 달을 누르면 그 달 월간 화면으로
  on(fresh, 'click', '[data-month]', el => {
    L.month = el.dataset.month;
    L.mode = 'month';
    try { localStorage.setItem('ledgerMode', 'month'); } catch (e) { /* 무시 */ }
    loadMonth().catch(showError);
  });
}

/** 달마다 수입·지출 막대 (y축 하나, 눈금은 1·2·5 단위). 달 칸 전체가 누르는 영역 */
function monthBars(months) {
  const W = 640, H = 220, L0 = 46, R0 = 8, T0 = 10, B0 = 26;
  const top = Math.max(1, ...months.map(m => Math.max(m.income, m.expense)));
  const step = (() => { const raw = top / 4; const p = 10 ** Math.floor(Math.log10(raw)); return [1, 2, 5, 10].map(k => k * p).find(v => v >= raw); })();
  const max = step * Math.ceil(top / step);
  const yOf = v => T0 + (H - T0 - B0) * (1 - v / max);
  const gw = (W - L0 - R0) / 12;
  const bw = Math.min(16, gw * 0.3);
  const grid = [];
  for (let v = 0; v <= max; v += step) {
    grid.push(`<line x1="${L0}" x2="${W - R0}" y1="${yOf(v)}" y2="${yOf(v)}" class="grid ${v ? '' : 'base'}"/>
      <text x="${L0 - 6}" y="${yOf(v) + 4}" text-anchor="end" class="tick">${v ? short(v) : '0'}</text>`);
  }
  const t = today();
  const bars = months.map((m, i) => {
    const cx = L0 + gw * i + gw / 2;
    const bar = (v, x, color, label) => v
      ? `<rect x="${x}" y="${yOf(v)}" width="${bw}" height="${Math.max(yOf(0) - yOf(v), 1)}" rx="2" fill="${color}"/>` : '';
    return `<g class="mb" data-month="${m.month}">
      <title>${Number(m.month.slice(5))}월 · 수입 ${won(m.income)} · 지출 ${won(m.expense)} · 저축 ${won(m.saving)}</title>
      <rect class="hit" x="${L0 + gw * i}" y="${T0}" width="${gw}" height="${H - T0}" fill="transparent"/>
      ${bar(m.income, cx - bw - 1, 'var(--s3)')}
      ${bar(m.expense, cx + 1, 'var(--s1)')}
      <text x="${cx}" y="${H - 8}" text-anchor="middle" class="tick ${m.from > t ? 'future' : ''} ${m.from <= t && m.to >= t ? 'now' : ''}">${Number(m.month.slice(5))}월</text>
    </g>`;
  }).join('');
  return `<svg class="lg-bars" viewBox="0 0 ${W} ${H}" role="img" aria-label="월별 수입과 지출 막대 차트 (아래 표에 같은 값)">${grid.join('')}${bars}</svg>`;
}

/** 월별 표 (차트와 같은 값). 행을 누르면 그 달로 */
function monthTable(months, s) {
  const pct = r => r == null ? '-' : Math.round(r * 100) + '%';
  return `<div class="lg-mt-wrap"><table class="lg-table lg-mt">
    <thead><tr><th>월</th><th>수입</th><th>저축</th><th>지출</th><th>잉여현금</th><th>저축률</th></tr></thead>
    <tbody>${months.map(m => `<tr data-month="${m.month}" class="${m.count ? '' : 'none'}" title="${Number(m.month.slice(5))}월 월간 화면으로">
      <td>${Number(m.month.slice(5))}월</td><td class="lg-amt income">${num(m.income)}</td><td class="lg-amt saving">${num(m.saving)}</td>
      <td class="lg-amt">${num(m.expense)}</td><td class="lg-amt ${m.surplus < 0 ? 'neg' : ''}">${num(m.surplus)}</td><td class="lg-amt">${pct(m.savingRate)}</td></tr>`).join('')}</tbody>
    <tfoot><tr><td>합계</td><td class="lg-amt income">${num(s.income)}</td><td class="lg-amt saving">${num(s.saving)}</td>
      <td class="lg-amt">${num(s.expense)}</td><td class="lg-amt ${s.surplus < 0 ? 'neg' : ''}">${num(s.surplus)}</td><td class="lg-amt">${pct(s.savingRate)}</td></tr></tfoot>
  </table></div>`;
}

// ───────── 정기결제 (예정 계산) ─────────
/** 이 달 기간 안의 결제일 (결제일이 그 달에 없으면 말일). 시작일 전·끝난 뒤면 null */
function dueInPeriod(r) {
  const { from, to } = L.data;
  for (let ym = from.slice(0, 7); ym <= to.slice(0, 7); ym = addMonth(ym, 1)) {
    const [y, m] = ym.split('-').map(Number);
    const due = `${ym}-${pad(Math.min(r.dayOfMonth, new Date(y, m, 0).getDate()))}`;
    if (due >= from && due <= to && due >= r.startDate && (!r.endDate || due <= r.endDate)) return due;
  }
  return null;
}
const recorded = (r, due) => L.data.recurringRuns.some(x => x.recurringId === r.id && x.month === due.slice(0, 7));
/** 이 달 정기결제 [{r, due, done}] (결제일 순) */
function thisMonthRecurring() {
  return L.data.recurring.filter(r => r.active).map(r => ({ r, due: dueInPeriod(r) })).filter(p => p.due)
    .map(p => ({ ...p, done: recorded(p.r, p.due) })).sort((a, b) => a.due.localeCompare(b.due));
}
/** 아직 기록되지 않은 결제 예정 (달력·날짜 창에 표시) */
const planned = () => thisMonthRecurring().filter(p => !p.done);

function recurringPanel() {
  const list = thisMonthRecurring();
  const total = list.reduce((a, p) => a + p.r.amount, 0);
  const left = list.filter(p => !p.done).reduce((a, p) => a + p.r.amount, 0);
  const off = L.data.recurring.filter(r => !r.active).length;
  return `<div class="panel-head"><h2>이번 달 정기결제</h2><span class="grow"></span>
      <button class="ghost small" data-act="recurring">관리</button></div>
    ${list.length ? list.map(p => `<div class="lg-plan-row" data-rid="${esc(p.r.id)}" title="눌러서 정기결제 수정">
        <span><span class="muted small lg-plan-d">${fmtDate(p.due)}</span> ${esc(p.r.name)}</span>
        <span><b>${won(p.r.amount)}</b> ${p.done ? '<span class="lg-done">기록됨</span>' : '<span class="lg-todo">예정</span>'}</span>
      </div>`).join('')
      + `<div class="lg-plan-sum muted small">${list.length}건 · 합계 ${won(total)}${left ? ` · 남은 결제 ${won(left)}` : ''}</div>`
      : `<div class="empty">등록된 정기결제가 없습니다${canEdit() ? '<br><span class="small">구독료·관리비처럼 매달 나가는 돈을 등록하면 결제일에 자동으로 기록됩니다</span>' : ''}</div>`}
    ${off ? `<div class="muted small">쉬는 중 ${off}건</div>` : ''}`;
}

function recurringDialog() {
  const list = L.data.recurring;
  const dlg = $('#dlg');
  dlg.innerHTML = `<form method="dialog" class="lg-wide">
    <h3>🔁 정기결제 — ${esc((space() || {}).name)}</h3>
    <p class="muted small" style="margin:0">결제일(그 달에 없으면 말일)이 되면 고정지출 내역이 자동으로 기록됩니다.
      자동 기록된 내역을 지우면 그 달은 다시 만들지 않습니다.</p>
    <div class="lg-rc-list">${list.map(r => `<div class="lg-rc-row ${r.active ? '' : 'off'}" data-rid="${esc(r.id)}">
        <span class="lg-rc-day">매달 ${r.dayOfMonth}일</span>
        <span class="lg-rc-name">${esc(r.name)} <span class="muted small">${esc(r.minor ? `${r.major} › ${r.minor}` : r.major)}${r.method ? ' · ' + esc(r.method) : ''}${r.active ? '' : ' · 쉬는 중'}${r.endDate ? ` · ${esc(r.endDate)}까지` : ''}</span></span>
        <b class="lg-amt">${won(r.amount)}</b>
      </div>`).join('') || '<div class="empty">등록된 정기결제가 없습니다</div>'}</div>
    ${list.length ? `<div class="muted small">매달 합계 ${won(list.filter(r => r.active).reduce((a, r) => a + r.amount, 0))}</div>` : ''}
    <div class="dlg-acts">
      ${canEdit() ? '<button type="button" data-act="new">＋ 정기결제 추가</button>' : ''}
      <span class="grow"></span>
      <button type="button" class="ghost" data-act="close">닫기</button>
    </div>
  </form>`;
  const form = $('form', dlg);
  on(form, 'click', '[data-act=close]', () => dlg.close());
  on(form, 'click', '[data-act=new]', () => recurringForm(null));
  on(form, 'click', '.lg-rc-row', el => recurringForm(list.find(r => r.id === el.dataset.rid)));
  form.addEventListener('submit', e => e.preventDefault());
  if (!dlg.open) dlg.showModal();
}

/** 정기결제 추가·수정 창. 닫히면 목록 창으로 돌아간다 */
function recurringForm(r) {
  const isNew = !r;
  const ro = !canEdit();
  const dis = ro ? 'disabled' : '';
  const v = r || { name: '', kind: 'fixed', major: '', minor: '', amount: 0, method: L.last.method || '', personId: '',
    dayOfMonth: Number(today().slice(8)), startDate: today(), endDate: '', active: true };
  if (!v.major) v.major = (majorsOf(v.kind)[0] || {}).major || '';
  const dlg = $('#dlg');
  dlg.innerHTML = `<form method="dialog" autocomplete="off">
    <h3>🔁 정기결제 ${isNew ? '추가' : ro ? '<span class="badge lang">보기 전용</span>' : '수정'}</h3>
    <div class="fields">
      <label class="field"><span>이름</span><input type="text" name="name" value="${esc(v.name)}" maxlength="100" placeholder="예: 넷플릭스" required ${dis}></label>
      <label class="field"><span>결제일</span><select name="dayOfMonth" ${dis}>${Array.from({ length: 31 }, (_, i) => i + 1)
        .map(n => `<option value="${n}" ${n === v.dayOfMonth ? 'selected' : ''}>매달 ${n}일${n >= 29 ? ' (없으면 말일)' : ''}</option>`).join('')}</select></label>
      ${entryFields(v, { dis, withDate: false }).replace(/<label class="field[^"]*"><span>사용내역<\/span>.*?<\/label>/s, '')}
      <label class="field"><span>시작일 (이날 이후 결제일부터 기록)</span><input type="date" name="startDate" value="${esc(v.startDate)}" required ${dis}></label>
      <label class="field"><span>끝나는 날 (선택)</span><input type="date" name="endDate" value="${esc(v.endDate)}" ${dis}></label>
      <label class="field check full"><input type="checkbox" name="active" ${v.active ? 'checked' : ''} ${dis}><span>사용 중 (끄면 기록하지 않음)</span></label>
    </div>
    <div class="dlg-acts">
      ${!isNew && !ro ? '<button type="button" class="danger" data-act="del">삭제</button>' : ''}
      <span class="grow"></span>
      <button type="button" class="ghost" data-act="back">${ro ? '닫기' : '취소'}</button>
      ${ro ? '' : '<button type="submit">저장</button>'}
    </div>
  </form>`;
  const form = $('form', dlg);
  const f = form.elements;
  if (!ro) bindCategoryFields(form);
  on(form, 'click', '[data-act=back]', () => recurringDialog());
  on(form, 'click', '[data-act=del]', async () => {
    if (!confirm(`"${r.name}" 정기결제를 삭제할까요? 이미 기록된 내역은 그대로 남습니다.`)) return;
    try {
      await request('DELETE', `${api(L.sid)}/recurring/${encodeURIComponent(r.id)}`);
      savedNow();
      await loadMonth();
      recurringDialog();
    } catch (err) { alert(err.message); }
  });
  form.addEventListener('submit', async ev => {
    ev.preventDefault();
    if (ro) return;
    const body = {
      name: f.name.value.trim(), kind: f.kind.value, major: f.major.value, minor: f.minor.value,
      amount: Number(digits(f.amount.value)), method: f.method.value, personId: f.personId ? f.personId.value : '',
      dayOfMonth: Number(f.dayOfMonth.value), startDate: f.startDate.value, endDate: f.endDate.value,
      active: f.active.checked, version: r ? r.version : 0,
    };
    if (!body.name) { f.name.focus(); return; }
    if (!body.amount) { f.amount.focus(); return; }
    if (!body.major || body.major === '__new') { f.major.focus(); return; }
    try {
      setStatus('저장 중…');
      await request('PUT', `${api(L.sid)}/recurring/${r ? encodeURIComponent(r.id) : uid()}`, body);
      savedNow();
      await loadMonth();
      recurringDialog();
    } catch (err) { alert('저장하지 못했습니다: ' + err.message); }
  });
  if (!dlg.open) dlg.showModal();
}

// ───────── 누적 저축 ─────────
function savingTotalsPanel(t, to, added, addedLabel) {
  const head = `<div class="panel-head"><h2>누적 저축·투자</h2><span class="grow"></span>
    <span class="muted small" title="가계부에 저축으로 기록한 금액의 합계입니다. 이자·수익·인출은 반영되지 않습니다">ⓘ 기록 합계</span></div>`;
  if (!t.total) return head + '<div class="empty">아직 저축 기록이 없습니다</div>';
  return head + `
    <div class="lg-total">${won(t.total)} <span class="muted small">${fmtDate(to)}까지${added ? ` · ${addedLabel} <span class="lg-plus">+${won(added)}</span>` : ''}</span></div>
    ${t.byMajor.map(m => `<div class="lg-tot-row">
      <div class="rank-label"><span>${esc(m.major || '(미분류)')}</span><b>${won(m.amount)}</b></div>
      ${m.minors.filter(x => x.minor).length ? `<div class="muted small">${m.minors.filter(x => x.minor).map(x => `${esc(x.minor)} ${num(x.amount)}`).join(' · ')}</div>` : ''}
    </div>`).join('')}`;
}

// ───────── 도넛 차트 ─────────
/** 조각은 분류 설정 순서로 놓고, 큰 것 7개만 색을 주고 나머지는 "기타" (색은 순위가 아니라 분류를 따른다) */
const MAX_SLICES = 7;
function slices(sums, kind, categories = L.data.categories) {
  const order = (categories[kind] || []).map(c => c.major);
  const idx = k => { const i = order.indexOf(k); return i < 0 ? 999 : i; };
  const all = Object.entries(sums).filter(([, v]) => v > 0);
  const top = [...all].sort((a, b) => b[1] - a[1]).slice(0, MAX_SLICES).map(([k]) => k);
  const shown = all.filter(([k]) => top.includes(k)).sort((a, b) => idx(a[0]) - idx(b[0]) || a[0].localeCompare(b[0]));
  const rest = all.filter(([k]) => !top.includes(k)).reduce((a, [, v]) => a + v, 0);
  const out = shown.map(([label, amount], i) => ({ label: label || '(미분류)', amount, color: `var(--s${i + 1})` }));
  if (rest) out.push({ label: `기타 ${all.length - top.length}개`, amount: rest, color: 'var(--s-other)' });
  return out;
}
const expenseSlices = () => slices(Object.fromEntries(L.data.summary.byMajor.map(r => [r.major, r.amount])), 'expense');
function savingSlices() {
  const sums = {};
  L.data.entries.filter(e => e.kind === 'saving').forEach(e => { sums[e.major] = (sums[e.major] || 0) + e.amount; });
  return slices(sums, 'saving');
}

/** 범례·조각에 올리면 그 조각만 진하게 */
function bindDonutHover(root) {
  on(root, 'mouseover', '.lg-leg, .donut .arc', el => {
    const box = el.closest('.donut');
    box.classList.add('focus');
    box.querySelectorAll('[data-i]').forEach(x => x.classList.toggle('on', x.dataset.i === el.dataset.i));
  });
  on(root, 'mouseout', '.donut', (el, e) => { if (!el.contains(e.relatedTarget)) el.classList.remove('focus'); });
}

/** SVG 도넛 + 범례 (이름·금액·비율을 글자로 적어 색만으로 구분하지 않는다) */
function donut(rows, total, label, when = '이번 달') {
  if (!total) return `<div class="empty">${when} ${label} 기록이 없습니다</div>`;
  const R = 52;
  const C = 2 * Math.PI * R;
  const GAP = rows.length > 1 ? 2 : 0;
  let at = 0;
  const arcs = rows.map((r, i) => {
    const len = r.amount / total * C;
    const pct = Math.round(r.amount / total * 1000) / 10;
    const arc = `<circle class="arc" data-i="${i}" r="${R}" cx="70" cy="70" fill="none" stroke="${r.color}" stroke-width="22"
      stroke-dasharray="${Math.max(len - GAP, 0.5)} ${C}" stroke-dashoffset="${-at}" transform="rotate(-90 70 70)">
      <title>${esc(r.label)} ${won(r.amount)} (${pct}%)</title></circle>`;
    at += len;
    return arc;
  }).join('');
  return `<div class="donut">
    <svg viewBox="0 0 140 140" role="img" aria-label="${esc(label)} 구성: ${rows.map(r => `${r.label} ${Math.round(r.amount / total * 100)}%`).join(', ')}">
      ${arcs}
      <text x="70" y="66" text-anchor="middle" class="donut-k">${esc(label)}</text>
      <text x="70" y="84" text-anchor="middle" class="donut-v">${short(total)}</text>
    </svg>
    <div class="donut-legend">${rows.map((r, i) => `<div class="lg-leg" data-i="${i}">
      <i style="background:${r.color}"></i><span class="nm">${esc(r.label)}</span>
      <b>${won(r.amount)}</b><span class="muted small pct">${Math.round(r.amount / total * 100)}%</span></div>`).join('')}</div>
  </div>`;
}

function ranking(rows, key, total, blank) {
  if (!rows.length) return '<div class="empty">지출이 없습니다</div>';
  const max = rows[0].amount;
  return rows.map(r => `<div class="rank-row">
    <div class="rank-label"><span>${esc(r[key] || blank)}</span>
      <span><b>${won(r.amount)}</b> <span class="muted small">${total ? Math.round(r.amount / total * 100) : 0}%</span></span></div>
    <div class="bar"><i style="width:${max ? r.amount / max * 100 : 0}%"></i></div>
  </div>`).join('');
}

function personTable(rows) {
  if (!rows.length) return '<div class="empty">내역이 없습니다</div>';
  return `<table class="lg-table"><thead><tr><th></th><th>수입</th><th>지출</th></tr></thead><tbody>
    ${rows.map(r => `<tr><td>${esc(r.personId ? userName(r.personId) : '공동')}</td>
      <td class="lg-amt income">${won(r.income)}</td><td class="lg-amt">${won(r.expense)}</td></tr>`).join('')}
  </tbody></table>`;
}

// ───────── 입력 ─────────
const kindOptions = v => Object.entries(KIND).map(([k, l]) => `<option value="${k}" ${v === k ? 'selected' : ''}>${l}</option>`).join('');
const majorsOf = kind => L.data.categories[CAT_KIND(kind)] || [];
function majorOptions(kind, v) {
  const list = majorsOf(kind).map(c => c.major);
  if (v && !list.includes(v)) list.unshift(v); // 지금은 없는 분류로 쓴 지난 내역
  return (list.map(x => `<option ${x === v ? 'selected' : ''}>${esc(x)}</option>`).join('') || '<option value="">(분류 없음)</option>')
    + (canEdit() ? NEW_OPTION : '');
}
const NEW_OPTION = '<option value="__new">＋ 새 분류…</option>';
function minorOptions(kind, major, v) {
  const list = [...((majorsOf(kind).find(c => c.major === major) || {}).minors || [])];
  if (v && !list.includes(v)) list.unshift(v);
  return '<option value="">-</option>' + list.map(x => `<option ${x === v ? 'selected' : ''}>${esc(x)}</option>`).join('')
    + (canEdit() && major ? NEW_OPTION : '');
}
function methodOptions(v) {
  const list = [...L.data.settings.methods];
  if (v && !list.includes(v)) list.unshift(v);
  return '<option value="">결제수단 없음</option>' + list.map(x => `<option ${x === v ? 'selected' : ''}>${esc(x)}</option>`).join('');
}
const personOptions = v => '<option value="">공동</option>'
  + members().map(m => `<option value="${esc(m.id)}" ${m.id === v ? 'selected' : ''}>${esc(m.name)}</option>`).join('');

/** 날짜 기본값: 보고 있는 달에 오늘이 있으면 오늘, 아니면 그 달 첫날 */
const defaultDate = () => {
  const t = today();
  if (L.last.date && L.last.date >= L.data.from && L.last.date <= L.data.to) return L.last.date;
  return t >= L.data.from && t <= L.data.to ? t : L.data.from;
};

function addForm() {
  const kind = L.last.kind || 'variable';
  const major = (majorsOf(kind)[0] || {}).major || '';
  return `<form class="panel ledger-add ${isTeam() ? 'team' : ''}" autocomplete="off">
    <select name="kind" aria-label="구분">${kindOptions(kind)}</select>
    <input type="date" name="date" value="${defaultDate()}" min="${L.data.from}" max="${L.data.to}" aria-label="날짜" required>
    <select name="major" aria-label="대분류">${majorOptions(kind, major)}</select>
    <select name="minor" aria-label="소분류">${minorOptions(kind, major, '')}</select>
    <input type="text" name="memo" placeholder="사용내역 (선택)" maxlength="500">
    <input type="text" name="amount" placeholder="금액" inputmode="numeric" aria-label="금액" required>
    <select name="method" aria-label="결제수단">${methodOptions(L.last.method || '')}</select>
    ${isTeam() ? `<select name="personId" aria-label="사람" title="누구의 수입·지출인지">${personOptions(L.last.personId ?? L.me.id)}</select>` : ''}
    <button type="submit">추가</button>
  </form>`;
}

/**
 * 구분을 바꾸면 대분류가, 대분류를 바꾸면 소분류가 바뀐다. 금액은 쉼표를 넣어 보여 준다.
 * 목록 맨 아래 "＋ 새 분류…" 를 고르면 이름을 받아 분류에 저장하고 바로 고른 상태로 둔다.
 */
function bindCategoryFields(form) {
  const f = form.elements;
  let prevMajor = f.major.value;
  let prevMinor = f.minor.value;
  f.kind.addEventListener('change', () => {
    f.major.innerHTML = majorOptions(f.kind.value, '');
    f.minor.innerHTML = minorOptions(f.kind.value, f.major.value, '');
    prevMajor = f.major.value;
    prevMinor = '';
  });
  f.major.addEventListener('change', async () => {
    if (f.major.value === '__new') {
      const name = await askCategory(f.kind.value, null);
      f.major.innerHTML = majorOptions(f.kind.value, name || prevMajor);
    }
    if (f.major.value !== prevMajor) f.minor.innerHTML = minorOptions(f.kind.value, f.major.value, '');
    prevMajor = f.major.value;
    prevMinor = f.minor.value;
  });
  f.minor.addEventListener('change', async () => {
    if (f.minor.value === '__new') {
      const name = await askCategory(f.kind.value, f.major.value);
      f.minor.innerHTML = minorOptions(f.kind.value, f.major.value, name || prevMinor);
    }
    prevMinor = f.minor.value;
  });
  f.amount.addEventListener('input', () => {
    const n = digits(f.amount.value);
    f.amount.value = n ? Number(n).toLocaleString('ko-KR') : '';
  });
}

function formValues(form) {
  const f = form.elements;
  return {
    kind: f.kind.value, date: f.date.value, major: f.major.value, minor: f.minor.value,
    memo: f.memo.value.trim(), amount: Number(digits(f.amount.value)), method: f.method.value,
    personId: f.personId ? f.personId.value : '',
  };
}

function bindAddForm(form) {
  bindCategoryFields(form);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const v = formValues(form);
    if (!v.amount) { form.elements.amount.focus(); return; }
    if (!v.major) { alert('분류가 없습니다. ⚙ 분류·설정에서 대분류를 추가하세요'); return; }
    L.last = { kind: v.kind, date: v.date, method: v.method, personId: v.personId };
    try {
      setStatus('저장 중…');
      await request('PUT', `${api(L.sid)}/entries/${uid()}`, v);
      savedNow();
      await loadMonth();
      $('.ledger-add input[name=amount]').focus();
    } catch (err) { showError(err); }
  });
}

/** 분류 이름을 받아 저장한다 (major 가 없으면 대분류, 있으면 그 아래 소분류). @return 새 이름 또는 null */
async function askCategory(kind, major) {
  const label = major ? `"${major}" 아래에 추가할 소분류 이름` : `새 ${{ income: '수입', saving: '저축', expense: '지출' }[CAT_KIND(kind)]} 대분류 이름`;
  const name = (prompt(label) || '').trim();
  if (!name) return null;
  if (name.length > 50) { alert('분류 이름은 50자까지'); return null; }
  const cats = JSON.parse(JSON.stringify(L.data.categories));
  const list = cats[CAT_KIND(kind)];
  if (major) {
    const c = list.find(x => x.major === major);
    if (!c) return null;
    if (!c.minors.includes(name)) c.minors.push(name);
  } else if (!list.some(x => x.major === name)) {
    list.push({ major: name, minors: [] });
  }
  try {
    setStatus('저장 중…');
    await request('PUT', `${api(L.sid)}/settings`,
      { monthStartDay: L.data.settings.monthStartDay, methods: L.data.settings.methods, categories: cats });
    L.data.categories = cats;
    savedNow();
    return name;
  } catch (err) {
    alert('분류를 저장하지 못했습니다: ' + err.message);
    return null;
  }
}

// ───────── 수정 창 ─────────
/** 내역 입력 칸들 (수정 창·날짜 창 공용). withDate 가 false 면 날짜 칸 없이 */
function entryFields(e, { dis = '', withDate = true } = {}) {
  return `
    <label class="field"><span>구분</span><select name="kind" ${dis}>${kindOptions(e.kind)}</select></label>
    ${withDate ? `<label class="field"><span>날짜</span><input type="date" name="date" value="${esc(e.date)}" required ${dis}></label>`
      : `<label class="field"><span>금액</span><input type="text" name="amount" inputmode="numeric" value="${e.amount ? num(e.amount) : ''}" placeholder="금액" required ${dis}></label>`}
    <label class="field"><span>대분류</span><select name="major" ${dis}>${majorOptions(e.kind, e.major)}</select></label>
    <label class="field"><span>소분류</span><select name="minor" ${dis}>${minorOptions(e.kind, e.major, e.minor)}</select></label>
    ${withDate ? `<label class="field"><span>금액</span><input type="text" name="amount" inputmode="numeric" value="${e.amount ? num(e.amount) : ''}" required ${dis}></label>` : ''}
    <label class="field"><span>결제수단</span><select name="method" ${dis}>${methodOptions(e.method)}</select></label>
    ${isTeam() ? `<label class="field"><span>사람</span><select name="personId" ${dis}>${personOptions(e.personId)}</select></label>` : ''}
    <label class="field ${isTeam() ? '' : 'full'}"><span>사용내역</span><input type="text" name="memo" value="${esc(e.memo)}" maxlength="500" ${dis}></label>`;
}

/** 내역 수정 창. after 가 있으면 닫힌 뒤(저장·삭제·취소) 부른다 (날짜 창으로 돌아가기) */
function entryDialog(e, after) {
  if (!e) return;
  const ro = !canEdit();
  const dis = ro ? 'disabled' : '';
  const dlg = $('#dlg');
  const who = isTeam() ? `작성 ${esc(userName(e.createdBy))}${e.updatedBy && e.updatedBy !== e.createdBy ? ` · 수정 ${esc(userName(e.updatedBy))}` : ''}` : '';
  dlg.innerHTML = `<form method="dialog" autocomplete="off">
    <h3>${e.recurringId ? '🔁 ' : ''}내역 ${ro ? '<span class="badge lang">보기 전용</span>' : '수정'}</h3>
    <div class="fields">${entryFields(e, { dis })}</div>
    ${e.recurringId ? '<div class="muted small">정기결제로 자동 기록된 내역입니다. 고치거나 지워도 정기결제에는 영향이 없습니다.</div>' : ''}
    ${who ? `<div class="muted small">${who}</div>` : ''}
    <div class="dlg-acts">
      ${ro ? '' : '<button type="button" class="danger" data-act="del">삭제</button>'}
      <span class="grow"></span>
      <button type="button" class="ghost" data-act="cancel">${ro ? '닫기' : '취소'}</button>
      ${ro ? '' : '<button type="submit">저장</button>'}
    </div>
  </form>`;
  const form = $('form', dlg);
  if (!ro) bindCategoryFields(form);
  form.addEventListener('submit', async ev => {
    ev.preventDefault();
    if (ro) return;
    const v = formValues(form);
    if (!v.amount) { form.elements.amount.focus(); return; }
    try {
      setStatus('저장 중…');
      await request('PUT', `${api(L.sid)}/entries/${encodeURIComponent(e.id)}`, { ...v, version: e.version });
      dlg.close();
      savedNow();
      await loadMonth();
    } catch (err) {
      dlg.close();
      showError(err);
      await loadMonth().catch(() => {});
    }
    if (after) after();
  });
  on(form, 'click', '[data-act]', async el => {
    if (el.dataset.act === 'cancel') { dlg.close(); if (after) after(); }
    if (el.dataset.act === 'del' && confirm('이 내역을 삭제할까요?')) {
      dlg.close();
      try {
        await request('DELETE', `${api(L.sid)}/entries/${encodeURIComponent(e.id)}`);
        savedNow();
        await loadMonth();
      } catch (err) { showError(err); }
      if (after) after();
    }
  });
  if (!dlg.open) dlg.showModal();
}

/** 휴대폰: 입력 줄 대신 ＋ 버튼으로 여는 내역 추가 창 */
function addDialog() {
  const kind = L.last.kind || 'variable';
  const blank = { kind, date: defaultDate(), major: (majorsOf(kind)[0] || {}).major || '', minor: '', amount: 0, memo: '',
    method: L.last.method || '', personId: L.last.personId ?? L.me.id };
  const dlg = $('#dlg');
  dlg.innerHTML = `<form method="dialog" autocomplete="off">
    <h3>내역 추가</h3>
    <div class="fields">${entryFields(blank)}</div>
    <div class="dlg-acts">
      <span class="grow"></span>
      <button type="button" class="ghost" data-act="close">닫기</button>
      <button type="submit">추가</button>
    </div>
  </form>`;
  const form = $('form', dlg);
  bindCategoryFields(form);
  on(form, 'click', '[data-act=close]', () => dlg.close());
  form.addEventListener('submit', async ev => {
    ev.preventDefault();
    const v = formValues(form);
    if (!v.amount) { form.elements.amount.focus(); return; }
    if (!v.major || v.major === '__new') { form.elements.major.focus(); return; }
    L.last = { kind: v.kind, date: v.date, method: v.method, personId: v.personId };
    try {
      setStatus('저장 중…');
      await request('PUT', `${api(L.sid)}/entries/${uid()}`, v);
      dlg.close();
      savedNow();
      await loadMonth();
    } catch (err) { showError(err); }
  });
  dlg.showModal();
  form.elements.amount.focus();
}

// ───────── 날짜 상세 창 ─────────
/** 달력에서 날짜를 누르면: 그날 합계, 내역(누르면 수정), 결제 예정, 이 날 내역 추가 */
function dayDialog(date) {
  const rows = L.data.entries.filter(e => e.date === date);
  const sum = { income: 0, saving: 0, expense: 0 };
  rows.forEach(e => { sum[CAT_KIND(e.kind)] += e.amount; });
  const plans = planned().filter(p => p.due === date);
  const kind = L.last.kind || 'variable';
  const blank = { kind, major: (majorsOf(kind)[0] || {}).major || '', minor: '', amount: 0, memo: '',
    method: L.last.method || '', personId: L.last.personId ?? L.me.id };
  const dlg = $('#dlg');
  dlg.innerHTML = `<form method="dialog" autocomplete="off" class="lg-day-dlg">
    <h3>${fmtDate(date)}</h3>
    <div class="lg-day-sum">
      <span>지출 <b>${won(sum.expense)}</b></span>
      ${sum.income ? `<span class="income">수입 <b>${won(sum.income)}</b></span>` : ''}
      ${sum.saving ? `<span class="saving">저축 <b>${won(sum.saving)}</b></span>` : ''}
    </div>
    <div class="lg-day-list">${rows.map(entryRow).join('') || '<div class="empty">이날 내역이 없습니다</div>'}</div>
    ${plans.length ? `<div class="lg-day-plans">${plans.map(p =>
      `<div class="lg-plan-row"><span>🔁 ${esc(p.r.name)} <span class="muted small">결제 예정 · 결제일에 자동 기록</span></span><b>${won(p.r.amount)}</b></div>`).join('')}</div>` : ''}
    ${canEdit() ? `<h3 class="lg-sub">이 날 내역 추가</h3>
      <input type="hidden" name="date" value="${date}">
      <div class="fields">${entryFields(blank, { withDate: false })}</div>` : ''}
    <div class="dlg-acts">
      <span class="grow"></span>
      <button type="button" class="ghost" data-act="close">닫기</button>
      ${canEdit() ? '<button type="submit">추가</button>' : ''}
    </div>
  </form>`;
  const form = $('form', dlg);
  if (canEdit()) bindCategoryFields(form);
  on(form, 'click', '.lg-row', el => entryDialog(rows.find(e => e.id === el.dataset.id), () => dayDialog(date)));
  on(form, 'click', '[data-act=close]', () => dlg.close());
  form.addEventListener('submit', async ev => {
    ev.preventDefault();
    if (!canEdit()) return;
    const v = formValues(form);
    if (!v.amount) { form.elements.amount.focus(); return; }
    if (!v.major || v.major === '__new') { form.elements.major.focus(); return; }
    L.last = { kind: v.kind, date, method: v.method, personId: v.personId };
    try {
      setStatus('저장 중…');
      await request('PUT', `${api(L.sid)}/entries/${uid()}`, v);
      savedNow();
      await loadMonth();
      dayDialog(date);
      $('#dlg input[name=amount]').focus();
    } catch (err) { showError(err); }
  });
  if (!dlg.open) dlg.showModal();
}

// ───────── 분류·설정 ─────────
const CAT_LABEL = { income: '수입', saving: '저축', expense: '지출 (고정·변동 공통)' };
const catText = list => list.map(c => c.minors.length ? `${c.major}: ${c.minors.join(', ')}` : c.major).join('\n');
/** "대분류: 소분류, 소분류" 한 줄에 대분류 하나 */
const parseCats = text => text.split('\n').map(line => line.trim()).filter(Boolean).map(line => {
  const i = line.indexOf(':');
  const major = (i < 0 ? line : line.slice(0, i)).trim();
  const minors = i < 0 ? [] : line.slice(i + 1).split(',').map(x => x.trim()).filter(Boolean);
  return { major, minors };
}).filter(c => c.major);

function settingsDialog(d = L.data) {
  const ro = !canEdit();
  const dis = ro ? 'disabled' : '';
  const dlg = $('#dlg');
  dlg.innerHTML = `<form method="dialog" class="lg-settings">
    <h3>분류·설정 — ${esc((space() || {}).name)} ${ro ? '<span class="badge lang">보기 전용</span>' : ''}</h3>
    <div class="fields">
      <label class="field"><span>월 시작일 (월급날 등)</span>
        <select name="day" ${dis}>${Array.from({ length: 28 }, (_, i) => i + 1)
          .map(n => `<option value="${n}" ${n === d.settings.monthStartDay ? 'selected' : ''}>${n}일${n === 1 ? ' (달력 그대로)' : ''}</option>`).join('')}</select>
      </label>
      <div class="field"><span>&nbsp;</span><span class="muted small">예: 25일이면 2월 = 1/25 ~ 2/24</span></div>
      <label class="field full"><span>결제수단 (한 줄에 하나)</span>
        <textarea name="methods" rows="4" ${dis}>${esc(d.settings.methods.join('\n'))}</textarea></label>
      ${['income', 'saving', 'expense'].map(k => `<label class="field full"><span>${CAT_LABEL[k]} 분류 — 한 줄에 "대분류: 소분류, 소분류"</span>
        <textarea name="cat-${k}" rows="${k === 'expense' ? 10 : 4}" ${dis}>${esc(catText(d.categories[k]))}</textarea></label>`).join('')}
    </div>
    <div class="muted small">분류를 바꾸거나 지워도 지난 내역은 그대로 남습니다.</div>
    <div class="dlg-acts">
      <span class="grow"></span>
      <button type="button" class="ghost" data-act="cancel">${ro ? '닫기' : '취소'}</button>
      ${ro ? '' : '<button type="submit">저장</button>'}
    </div>
  </form>`;
  const form = $('form', dlg);
  form.addEventListener('submit', async ev => {
    ev.preventDefault();
    if (ro) return;
    const f = form.elements;
    const body = {
      monthStartDay: Number(f.day.value),
      methods: f.methods.value.split('\n').map(x => x.trim()).filter(Boolean),
      categories: Object.fromEntries(['income', 'saving', 'expense'].map(k => [k, parseCats(f['cat-' + k].value)])),
    };
    try {
      setStatus('저장 중…');
      await request('PUT', `${api(L.sid)}/settings`, body);
      dlg.close();
      savedNow();
      if (L.mode === 'year') await loadYear();
      else await loadMonth(body.monthStartDay !== d.settings.monthStartDay);
    } catch (err) { alert(err.message); }
  });
  on(form, 'click', '[data-act=cancel]', () => dlg.close());
  dlg.showModal();
}

// ───────── 새 팀 공간 ─────────
const TEAM_COLORS = ['#2563eb', '#16a34a', '#dc2626', '#d97706', '#7c3aed', '#0891b2', '#db2777', '#6b7280'];

/** 팀 공간(= 함께 쓰는 가계부)을 만들고 바로 그 가계부로 바꾼다. 초대는 하루한판의 ⚙ 공간 설정에서 */
function newTeamDialog() {
  const dlg = $('#dlg');
  dlg.innerHTML = `<form method="dialog" autocomplete="off">
    <h3>새 팀 공간</h3>
    <p class="muted small" style="margin:0">팀 공간을 만들면 함께 쓰는 가계부가 생깁니다. 하루한판의 할 일·일정도 같은 팀에서 함께 씁니다.</p>
    <div class="fields">
      <label class="field full"><span>팀 이름</span><input type="text" name="name" maxlength="50" placeholder="예: 우리집" required></label>
      <div class="field full"><span>색</span><div class="palette">${TEAM_COLORS.map((c, i) =>
        `<input type="radio" name="color" id="tc${i}" value="${c}" ${i === 1 ? 'checked' : ''}><label for="tc${i}" style="background:${c}" title="${c}"></label>`).join('')}</div></div>
    </div>
    <div class="dlg-acts"><span class="grow"></span>
      <button type="button" class="ghost" data-act="cancel">취소</button><button type="submit">만들기</button></div>
  </form>`;
  const form = $('form', dlg);
  on(form, 'click', '[data-act=cancel]', () => dlg.close());
  form.addEventListener('submit', async ev => {
    ev.preventDefault();
    const name = form.elements.name.value.trim();
    if (!name) { form.elements.name.focus(); return; }
    const color = (form.querySelector('input[name=color]:checked') || {}).value || TEAM_COLORS[1];
    try {
      const res = await request('POST', '/api/spaces', { name, color });
      const meta = await request('GET', '/api/ledger/meta');
      L.spaces = meta.spaces;
      selectSpace(res.id);
      history.replaceState(null, '', '/ledger?space=' + encodeURIComponent(L.sid));
      await reloadView();
      teamCreatedDialog(res.id, name);
    } catch (err) { alert('만들 수 없습니다: ' + err.message); }
  });
  dlg.showModal();
  form.elements.name.focus();
}

function teamCreatedDialog(sid, name) {
  const dlg = $('#dlg');
  dlg.innerHTML = `<form method="dialog">
    <h3>👥 ${esc(name)} 을(를) 만들었습니다</h3>
    <p style="margin:0">지금부터 이 팀의 가계부를 보고 있습니다.</p>
    <p class="muted small" style="margin:0">같이 쓸 사람은 하루한판의 <b>⚙ 공간 설정 → 초대 링크</b>로 초대하세요. 링크를 받은 사람이 가입하면 이 가계부를 함께 씁니다.</p>
    <div class="dlg-acts"><span class="grow"></span>
      <button type="button" class="ghost" data-act="later">나중에</button>
      <button type="button" data-act="invite">하루한판에서 초대하기</button></div>
  </form>`;
  const form = $('form', dlg);
  on(form, 'click', '[data-act=later]', () => dlg.close());
  on(form, 'click', '[data-act=invite]', () => {
    try { localStorage.setItem('view', 't:' + sid); } catch (e) { /* 무시 */ }
    location.href = '/board';
  });
  dlg.showModal();
}

function showError(err) {
  if (err.status === 409) {
    setStatus('다른 사람이 먼저 수정해서 최신 내용으로 바꿨습니다', true);
  } else if (![0, 401, 503].includes(err.status)) {
    setStatus('저장 실패 — ' + err.message, true);
  }
}

// ───────── 시작 ─────────
function selectSpace(sid) {
  L.sid = sid;
  L.last = {};
  L.filter = 'all';
  L.day = null;
  try { localStorage.setItem('ledgerSpace', sid); } catch (e) { /* 무시 */ }
}

function bindTools() {
  $('#space-sel').addEventListener('change', e => {
    if (e.target.value === '__new') { e.target.value = L.sid; newTeamDialog(); return; }
    selectSpace(e.target.value);
    history.replaceState(null, '', '/ledger?space=' + encodeURIComponent(L.sid));
    (L.mode === 'year' ? loadYear() : loadMonth(true)).catch(showError);
  });
  // 하루한판으로: 같은 공간을 보도록 하루한판의 보기 범위를 맞춘다
  $('#btn-dashboard').addEventListener('click', () => {
    try { localStorage.setItem('view', isTeam() ? 't:' + L.sid : 'p'); } catch (e) { /* 무시 */ }
    location.href = '/board';
  });
  $('#btn-theme').addEventListener('click', () => {
    const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = next;
    try { localStorage.setItem('theme', next); } catch (e) { /* 저장소 차단 */ }
  });
  $('#btn-logout').addEventListener('click', async () => {
    try { await request('POST', '/api/logout'); } catch (e) { /* 이미 만료 */ }
    location.replace('/login.html');
  });
  // 다른 탭·다른 사람의 변경: 이 탭으로 돌아오면 다시 받는다 (입력 중이거나 창이 열려 있으면 건너뜀)
  document.addEventListener('visibilitychange', () => {
    const typing = document.activeElement && /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName);
    if (document.visibilityState === 'visible' && (L.data || L.ydata) && !$('#dlg').open && !typing) reloadView().catch(() => {});
  });
}

async function init() {
  bindTools();
  try {
    const meta = await request('GET', '/api/ledger/meta');
    L.me = meta.me;
    L.spaces = meta.spaces;
  } catch (e) {
    if (e.status === 401) { location.replace('/login.html'); return; }
    if (e.status === 403 && e.message === 'ledger_denied') { location.replace('/?ledger=denied'); return; } // 가계부 권한 없음
    if (e.status === 403) { location.replace('/board'); return; } // 승인 대기 등: 하루한판이 안내한다
    $('#main').innerHTML = '<div class="empty">서버에 연결할 수 없습니다. 잠시 후 새로고침하세요.</div>';
    return;
  }
  let want = new URLSearchParams(location.search).get('space');
  if (!want) { try { want = localStorage.getItem('ledgerSpace'); } catch (e) { /* 무시 */ } }
  selectSpace(L.spaces.some(s => s.id === want) ? want : personalId());
  L.month = today().slice(0, 7);
  try { if (localStorage.getItem('ledgerView') === 'calendar') L.view = 'calendar'; } catch (e) { /* 무시 */ }
  try { if (localStorage.getItem('ledgerMode') === 'year') { L.mode = 'year'; L.year = Number(L.month.slice(0, 4)); } } catch (e) { /* 무시 */ }
  try {
    await reloadView();
  } catch (e) {
    $('#main').innerHTML = `<div class="empty">가계부를 불러오지 못했습니다: ${esc(e.message)}</div>`;
  }
}

init();
