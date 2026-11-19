'use strict';
/* 하루위키 (하루한판의 위키 탭, 예전 메모)
 * - 페이지는 notes 컬렉션: parentId(상위)·sort(순서)·icon(이모지)·content(Editor.js JSON)·body(글자만, 검색·홈 미리보기)
 * - 편집기는 Editor.js (vendor/editorjs). 위키 탭을 처음 열 때 불러온다
 * - 입력을 1.5초 멈추면 저장 (다른 페이지로 옮기거나 창을 닫으면 바로). 저장은 app.js 의 save() 그대로
 * - 이미지는 서버에 올리고(/api/spaces/{sid}/files) 블록에는 /files/{id} 주소만 남긴다
 * - 저장된 내용을 편집기에 넣기 전에 허용한 태그·주소만 남긴다 (sanitizeWiki)
 * 이 파일은 app.js 보다 먼저 읽히고, 함수는 app.js 의 S·UI·save 등을 불러 쓸 때 쓴다.
 */

// ───────── 편집기 불러오기 ─────────
const EDITOR_FILES = ['editorjs.umd.js', 'header.umd.js', 'editorjs-list.umd.js', 'quote.umd.js', 'code.umd.js',
  'delimiter.umd.js', 'table.umd.js', 'image.umd.js', 'marker.umd.js', 'inline-code.umd.js'];
let editorLoading = null;
/** 편집기 파일 10개를 차례로 불러온다. 전부 끝난 다음에만 준비된 것으로 본다 (중간에 부르면 같은 약속을 기다린다) */
function loadEditor() {
  if (!editorLoading) {
    editorLoading = EDITOR_FILES.reduce((p, f) => p.then(() => new Promise((ok, fail) => {
      const s = document.createElement('script');
      s.src = '/vendor/editorjs/' + f;
      s.onload = ok;
      s.onerror = () => fail(new Error(f));
      document.head.appendChild(s);
    })), Promise.resolve()).catch(e => { editorLoading = null; throw e; });
  }
  return editorLoading;
}

// ───────── 상태 ─────────
let wikiEditor = null;      // 지금 열린 Editor.js
let wikiEditorPage = null;  // 그 편집기가 보여 주는 페이지 id
let wikiDrag = null;        // 끌고 있는 페이지 id
const WIKI_ICONS = ['📄', '📝', '📌', '📚', '📖', '🗂️', '📁', '🗒️', '✅', '💡', '⭐', '🔥', '🎯', '🚀', '🛠️', '⚙️',
  '🐛', '🔒', '🔑', '📊', '📈', '💰', '🧾', '🏠', '🍳', '✈️', '🎁', '🎉', '❤️', '🌱', '☕', '🧠', '💬', '📅', '⏰', '🔗',
  '🗄️', '🐳', '🌐', '🖥️'];

function wikiOpenSet() {
  if (!UI.wikiOpen) {
    UI.wikiOpen = new Set();
    try { JSON.parse(localStorage.getItem('wikiOpen') || '[]').forEach(id => UI.wikiOpen.add(id)); } catch (e) { /* 무시 */ }
  }
  return UI.wikiOpen;
}
function saveOpenSet() {
  try { localStorage.setItem('wikiOpen', JSON.stringify([...wikiOpenSet()])); } catch (e) { /* 무시 */ }
}

// ───────── 트리 도우미 ─────────
const pageTitle = p => p.title || '(제목 없음)';
const pageIcon = p => p.icon || '📄';
const wikiPages = () => inCur(S.notes);
function childrenOf(pid, sid) {
  return wikiPages().filter(p => (p.parentId || '') === (pid || '') && p.spaceId === sid
      && (!pid || byId(S.notes, pid)))   // 상위가 사라진 페이지는 최상위로 보인다 (아래 rootsOf)
    .sort((a, b) => (a.sort || 0) - (b.sort || 0) || (a.updatedAt || '').localeCompare(b.updatedAt || ''));
}
function rootsOf(sid) {
  return wikiPages().filter(p => p.spaceId === sid && (!p.parentId || !byId(S.notes, p.parentId)))
    .sort((a, b) => (a.sort || 0) - (b.sort || 0) || (a.updatedAt || '').localeCompare(b.updatedAt || ''));
}
const siblingsOf = p => p.parentId && byId(S.notes, p.parentId) ? childrenOf(p.parentId, p.spaceId) : rootsOf(p.spaceId);
function ancestorsOf(p) {
  const list = [];
  for (let cur = byId(S.notes, p.parentId); cur && !list.includes(cur); cur = byId(S.notes, cur.parentId)) list.unshift(cur);
  return list;
}
function descendantsOf(p) {
  const out = [];
  const walk = id => S.notes.filter(x => x.parentId === id && x.spaceId === p.spaceId).forEach(c => { out.push(c); walk(c.id); });
  walk(p.id);
  return out;
}
const nextSort = list => Math.max(0, ...list.map(x => x.sort || 0)) + 1000;

// ───────── 내용: 정리·글자 뽑기 ─────────
/** 저장된 HTML 조각에서 허용한 태그·속성만 남긴다 (팀원이 쓴 내용도 그대로 믿지 않는다) */
const WIKI_TAGS = { B: [], STRONG: [], I: [], EM: [], U: [], S: [], BR: [], MARK: ['class'], CODE: ['class'], A: ['href'] };
function sanitizeHtml(html) {
  const tpl = document.createElement('template');
  tpl.innerHTML = String(html ?? '');
  const clean = node => {
    [...node.childNodes].forEach(ch => {
      if (ch.nodeType === 3) return;
      if (ch.nodeType !== 1 || !WIKI_TAGS[ch.tagName]) { clean(ch); ch.replaceWith(...ch.childNodes); return; }
      [...ch.attributes].forEach(a => { if (!WIKI_TAGS[ch.tagName].includes(a.name)) ch.removeAttribute(a.name); });
      if (ch.tagName === 'A') {
        const href = ch.getAttribute('href') || '';
        if (!/^(https?:|mailto:|\/(?!\/)|#)/i.test(href)) ch.removeAttribute('href');
        else { ch.setAttribute('target', '_blank'); ch.setAttribute('rel', 'noopener'); }
      }
      clean(ch);
    });
  };
  clean(tpl.content);
  return tpl.innerHTML;
}
function sanitizeValue(v) {
  if (typeof v === 'string') return sanitizeHtml(v);
  if (Array.isArray(v)) return v.map(sanitizeValue);
  if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, sanitizeValue(x)]));
  return v;
}
function sanitizeWiki(data) {
  const blocks = (data && Array.isArray(data.blocks) ? data.blocks : []).filter(b => b && typeof b.type === 'string')
    .filter(b => b.type !== 'image' || /^\/files\/[A-Za-z0-9_-]+$/.test(((b.data || {}).file || {}).url || ''))
    .map(b => b.type === 'code' ? { ...b, data: { code: String((b.data || {}).code ?? '') } } // 코드는 글자 그대로
      : b.type === 'image' ? { ...b, data: { ...b.data, caption: sanitizeHtml(b.data.caption) } }
        : { ...b, data: sanitizeValue(b.data || {}) });
  return { time: data && data.time, version: data && data.version, blocks };
}
/** 페이지 내용 (예전 메모라 content 가 없으면 글을 문단으로) */
function pageData(p) {
  if (p.content) {
    try { return sanitizeWiki(JSON.parse(p.content)); } catch (e) { /* 깨진 내용이면 글로 */ }
  }
  const lines = String(p.body || '').split('\n');
  return { blocks: lines.filter((l, i) => l.trim() || i < lines.length - 1).map(l => ({ type: 'paragraph', data: { text: esc(l) } })) };
}
const stripTags = html => { const d = document.createElement('div'); d.innerHTML = String(html ?? ''); return d.textContent; };
/** 검색·홈 미리보기용 글자 */
function plainText(data) {
  const listText = items => (items || []).map(it => typeof it === 'string' ? stripTags(it)
    : [stripTags(it.content), listText(it.items)].filter(Boolean).join('\n')).join('\n');
  return (data.blocks || []).map(b => {
    const d = b.data || {};
    if (b.type === 'list') return listText(d.items);
    if (b.type === 'code') return d.code || '';
    if (b.type === 'table') return (d.content || []).map(r => r.map(stripTags).join(' | ')).join('\n');
    if (b.type === 'image') return stripTags(d.caption);
    if (b.type === 'quote') return stripTags(d.text);
    return stripTags(d.text);
  }).filter(Boolean).join('\n');
}

// ───────── 저장 ─────────
// 자동 저장 규칙: 입력을 3초 멈추면 저장, 쉬지 않고 입력해도 최대 30초마다 한 번, 마지막 저장과 내용이 같으면 보내지 않는다.
// 다른 페이지로 옮길 때·창을 닫을 때·로그아웃할 때는 기다리지 않고 바로 저장한다
const SAVE_IDLE_MS = 3000;
const SAVE_MAX_MS = 30000;
let noteDirtySince = 0;     // 저장하지 않은 변경이 처음 생긴 때
let noteCapturing = false;  // 최대 대기 시간이 지나 편집기 내용을 담는 중
const savedSig = new Map(); // 페이지 id → 마지막으로 저장한 내용 (제목·아이콘·본문)
// 편집기 출력의 "time" 은 저장할 때마다 바뀌므로 비교에서 뺀다
const noteSig = p => JSON.stringify([p.title || '', p.icon || '', String(p.content || '').replace(/"time":\d+,?/, '')]);
const rememberSaved = p => savedSig.set(p.id, noteSig(p));

/** 쓰던 내용을 바로 저장한다. 바뀐 게 없으면 보내지 않는다 */
function flushNote() {
  if (!noteTimer) return;
  clearTimeout(noteTimer);
  noteTimer = null;
  noteDirtySince = 0;
  const cur = byId(S.notes, wikiEditorPage || UI.noteId);
  if (!cur) return;
  const sig = noteSig(cur);
  if (savedSig.get(cur.id) === sig) { setStatus('변경 없음'); return; }
  send('PUT', `${base(cur.spaceId)}/notes/${encodeURIComponent(cur.id)}`, cur, {
    onOk: res => { if (res.version) cur.version = res.version; savedSig.set(cur.id, sig); },
    onConflict: () => onConflict('notes', cur),
  });
}

/** 편집기에 글자가 들어올 때마다: 쉬지 않고 30초 넘게 입력 중이면 지금까지를 담아 저장한다 */
function noteTyping() {
  if (!noteDirtySince) { noteDirtySince = Date.now(); return; }
  if (noteCapturing || Date.now() - noteDirtySince < SAVE_MAX_MS) return;
  const cur = byId(S.notes, wikiEditorPage);
  if (!cur) return;
  noteCapturing = true;
  captureEditor(cur).then(flushNote).finally(() => { noteCapturing = false; });
}
window.addEventListener('pagehide', flushNote, { capture: true });
/** 누가 언제 마지막으로 고쳤는지. 팀 공간이면 사람(아바타·이름), 내 공간이면 시간만 */
function editedTitle(p) {
  if (!p.updatedAt) return '';
  const when = new Date(p.updatedAt).toLocaleString('ko-KR');
  return isTeam(p.spaceId) && p.updatedBy
    ? `마지막 수정: ${userName(p.updatedBy)} · ${when}${p.createdBy ? ` (만든 사람 ${userName(p.createdBy)})` : ''}`
    : `마지막 수정: ${when}`;
}
function editedLine(p) {
  if (!p.updatedAt) return '';
  return isTeam(p.spaceId) && p.updatedBy
    ? `${avatar(p.updatedBy, true)}<b>${esc(userName(p.updatedBy))}</b> · ${timeAgo(p.updatedAt)} 수정`
    : `${timeAgo(p.updatedAt)} 수정`;
}
function touchPage(p) {
  const who = p.updatedBy;
  p.updatedAt = nowIso();
  p.updatedBy = S.me.id;
  const meta = document.getElementById('note-meta');
  if (meta && (who !== p.updatedBy || !meta.dataset.fresh)) { // 고친 사람이 바뀌면 바로 "나 · 방금"으로
    meta.innerHTML = editedLine(p);
    meta.title = editedTitle(p);
    meta.dataset.fresh = '1';
  }
  setStatus('입력 중…');
  if (!noteDirtySince) noteDirtySince = Date.now();
  clearTimeout(noteTimer);
  noteTimer = setTimeout(flushNote, Date.now() - noteDirtySince >= SAVE_MAX_MS ? 0 : SAVE_IDLE_MS);
}
function destroyEditor() {
  if (wikiEditor) {
    try { wikiEditor.destroy(); } catch (e) { /* 이미 사라짐 */ }
  }
  wikiEditor = null;
  wikiEditorPage = null;
}

// ───────── 화면 ─────────
function renderWiki(root) {
  flushNote();
  destroyEditor();
  const q = UI.noteQuery.trim().toLowerCase();
  const pages = wikiPages();
  if (UI.noteId && !byId(S.notes, UI.noteId)) UI.noteId = null;
  if (UI.noteId && !inView(byId(S.notes, UI.noteId).spaceId)) UI.noteId = null;
  if (!UI.noteId && pages.length) UI.noteId = (rootsOf(viewSpaces()[0])[0] || pages[0]).id;
  const cur = byId(S.notes, UI.noteId);
  const editable = cur ? canEdit(cur.spaceId) : false;
  const open = wikiOpenSet();
  if (cur) ancestorsOf(cur).forEach(a => open.add(a.id)); // 열린 페이지가 보이도록 상위를 펼친다

  const node = (p, depth) => {
    const kids = childrenOf(p.id, p.spaceId);
    const isOpen = open.has(p.id);
    const can = canEdit(p.spaceId);
    return `<div class="wk-node">
      <div class="wk-row ${p.id === UI.noteId ? 'on' : ''}" data-id="${esc(p.id)}" style="--d:${depth}" ${can ? 'draggable="true"' : ''}>
        <button class="wk-tg ${kids.length ? '' : 'none'}" data-act="toggle" aria-label="${isOpen ? '접기' : '펼치기'}">${kids.length ? (isOpen ? '▾' : '▸') : ''}</button>
        <span class="wk-ic">${esc(pageIcon(p))}</span>
        <span class="wk-t" title="${esc(editedTitle(p))}">${esc(pageTitle(p))}</span>
        ${isTeam(p.spaceId) && p.updatedBy ? `<span class="wk-who">${avatar(p.updatedBy, true)}</span>` : ''}
        ${can ? `<button class="wk-act" data-act="child" title="하위 페이지 추가" aria-label="하위 페이지 추가">＋</button>
          <button class="wk-act" data-act="menu" title="더 보기" aria-label="더 보기">⋯</button>` : ''}
      </div>
      ${kids.length && isOpen ? kids.map(k => node(k, depth + 1)).join('') : ''}
    </div>`;
  };
  const tree = viewSpaces().map(sid => {
    const roots = rootsOf(sid);
    return `${isMixed() ? `<div class="wk-space">${spaceTag(sid) || esc(spaceLabel(spaceById(sid)))}</div>` : ''}
      ${roots.length ? roots.map(r => node(r, 0)).join('') : `<div class="empty small">페이지가 없습니다</div>`}`;
  }).join('');
  const results = q ? pages.filter(p => (p.title + ' ' + (p.body || '')).toLowerCase().includes(q))
    .sort((a, b) => (b.updatedAt || '').localeCompare(a.updatedAt || '')) : [];

  let sideMini = false;
  try { sideMini = localStorage.getItem('wikiSideMini') === '1'; } catch (e) { /* 무시 */ }
  root.innerHTML = `
  <div class="wiki ${UI.wikiTree ? 'tree-open' : ''} ${sideMini ? 'side-mini' : ''}">
    <aside class="panel wk-side">
      <div class="wk-side-head"><h2>하루위키</h2><span class="grow"></span>
        <button class="icon wk-side-fold" data-act="fold" title="페이지 목록 접기" aria-label="페이지 목록 접기">«</button>
        ${canEdit() ? `<button class="small" data-act="new" title="${esc(curSpace().name)} 에 새 페이지">＋ 새 페이지</button>` : readonlyNote()}</div>
      <input type="search" id="note-q" placeholder="페이지 검색" value="${esc(UI.noteQuery)}">
      ${isMixed() && canEdit() ? `<div class="small">${targetHint()}</div>` : ''}
      <nav class="wk-tree" aria-label="페이지 목록">${q
        ? (results.length ? results.map(p => `<div class="wk-row wk-hit" data-id="${esc(p.id)}" style="--d:0"><span class="wk-ic">${esc(pageIcon(p))}</span>
            <span class="wk-t">${esc(pageTitle(p))}<span class="muted small wk-path">${esc(ancestorsOf(p).map(pageTitle).join(' › '))}</span></span></div>`).join('')
          : '<div class="empty small">찾는 페이지가 없습니다</div>')
        : tree}</nav>
    </aside>
    <section class="panel wk-main">${cur ? `
      <div class="wk-top">
        <button class="ghost small wk-tree-btn" data-act="tree">☰ 페이지</button>
        <button class="icon wk-side-unfold" data-act="fold" title="페이지 목록 펼치기" aria-label="페이지 목록 펼치기">»</button>
        <nav class="wk-crumbs" aria-label="경로">${ancestorsOf(cur).map(a => `<a href="#wiki" data-go="${esc(a.id)}">${esc(pageIcon(a))} ${esc(pageTitle(a))}</a><span>›</span>`).join('')}
          <span class="cur">${esc(pageTitle(cur))}</span></nav>
        <span class="grow"></span>
        ${editable && editableSpaces().length > 1 && !cur.parentId ? `<select id="note-space" title="이 페이지(하위 포함)를 다른 공간으로">${editableSpaces().map(s =>
          `<option value="${esc(s.id)}" ${s.id === cur.spaceId ? 'selected' : ''}>${esc(spaceLabel(s))}</option>`).join('')}</select>` : ''}
        ${editable ? '<button class="ghost small" data-act="keys" title="입력 단축키">⌨ 단축키</button>' : ''}
        ${editable ? `<button class="ghost small" data-act="pin">${cur.pinned ? '📌 고정 해제' : '📌 홈에 고정'}</button>` : '<span class="badge lang">보기 전용</span>'}
      </div>
      <div class="wk-head">
        <button class="wk-icon" data-act="icon" ${editable ? '' : 'disabled'} title="아이콘 바꾸기">${esc(pageIcon(cur))}</button>
        <input class="wk-title" id="note-title" placeholder="제목 없음" value="${esc(cur.title)}" ${editable ? '' : 'readonly'} autocomplete="off">
        <div class="muted small wk-meta" id="note-meta" title="${esc(editedTitle(cur))}">${editedLine(cur)}</div>
      </div>
      ${editable ? formatBarHtml() : ''}
      <div id="wiki-editor" class="wk-editor"><div class="empty">편집기를 불러오는 중…</div></div>
      ${(() => { const kids = childrenOf(cur.id, cur.spaceId); return kids.length || editable ? `<div class="wk-kids">
        <div class="muted small">하위 페이지</div>
        ${kids.map(k => `<a href="#wiki" data-go="${esc(k.id)}" title="${esc(editedTitle(k))}">${esc(pageIcon(k))} ${esc(pageTitle(k))}<span class="wk-kid-who muted small">${editedLine(k)}</span></a>`).join('')}
        ${editable ? '<button class="ghost small" data-act="child-cur">＋ 하위 페이지</button>' : ''}
      </div>` : ''; })()}`
      : `<div class="empty">${pages.length ? '왼쪽에서 페이지를 고르세요' : canEdit() ? '첫 페이지를 만들어 보세요' : '페이지가 없습니다'}
          ${canEdit() && !pages.length ? '<div style="margin-top:10px"><button data-act="new">＋ 새 페이지</button></div>' : ''}</div>`}
    </section>
  </div>`;

  bindWiki(root, cur, editable);
  if (cur) mountEditor(cur, editable);
}

function selectPage(id) {
  flushNote();
  UI.noteId = id;
  UI.wikiTree = false;
  render();
  window.scrollTo({ top: 0 });
}

function newPage(parent) {
  flushNote();
  const sid = parent ? parent.spaceId : UI.space;
  const list = parent ? childrenOf(parent.id, sid) : rootsOf(sid);
  const p = { id: uid(), spaceId: sid, version: 0, title: '', body: '', content: '', pinned: false, icon: '',
    parentId: parent ? parent.id : '', sort: nextSort(list), updatedAt: nowIso(), updatedBy: S.me.id };
  S.notes.push(p);
  save('notes', p);
  if (parent) { wikiOpenSet().add(parent.id); saveOpenSet(); }
  UI.noteId = p.id;
  UI.noteQuery = '';
  UI.wikiTree = false;
  render();
  const t = $('#note-title');
  if (t) t.focus();
}

function deletePage(p) {
  const kids = descendantsOf(p);
  if (!confirm(`"${pageTitle(p)}" 페이지를 삭제할까요?${kids.length ? `\n하위 페이지 ${kids.length}개와 이미지도 함께 삭제됩니다.` : ''}`)) return;
  clearTimeout(noteTimer);
  noteTimer = null;
  destroyEditor();
  const gone = new Set([p.id, ...kids.map(k => k.id)]);
  S.notes = S.notes.filter(n => !gone.has(n.id));
  removeItem('notes', p); // 서버가 하위 페이지·이미지까지 지운다
  UI.noteId = p.parentId && byId(S.notes, p.parentId) ? p.parentId : null;
  render();
}

/** 같은 상위 아래 순서를 다시 매긴다 (바뀐 것만 저장) */
function reorder(list) {
  list.forEach((x, i) => {
    const s = (i + 1) * 1000;
    if (x.sort !== s) { x.sort = s; save('notes', x); }
  });
}

/** page 를 target 의 앞(before)·뒤(after)·안(inside) 으로 옮긴다. 같은 공간 안에서만 */
function placePage(page, target, where) {
  if (!page || !target || page.id === target.id || page.spaceId !== target.spaceId) return;
  if (descendantsOf(page).some(d => d.id === target.id)) { setStatus('페이지를 자기 하위로 옮길 수 없습니다', true); return; }
  if (where === 'inside') {
    page.parentId = target.id;
    const kids = childrenOf(target.id, target.spaceId).filter(k => k.id !== page.id);
    page.sort = nextSort(kids);
    wikiOpenSet().add(target.id);
    saveOpenSet();
    save('notes', page);
  } else {
    page.parentId = target.parentId && byId(S.notes, target.parentId) ? target.parentId : '';
    const sibs = siblingsOf(target).filter(s => s.id !== page.id);
    sibs.splice(sibs.indexOf(target) + (where === 'after' ? 1 : 0), 0, page);
    save('notes', page);
    reorder(sibs);
  }
  render();
}

function pageMenu(p, anchor) {
  closeWikiPop();
  const sibs = siblingsOf(p);
  const i = sibs.indexOf(p);
  const items = [
    ['child', '＋ 하위 페이지 추가', true],
    ['up', '↑ 위로', i > 0],
    ['down', '↓ 아래로', i < sibs.length - 1],
    ['indent', '→ 위 페이지 안으로', i > 0],
    ['outdent', '← 한 단계 밖으로', !!(p.parentId && byId(S.notes, p.parentId))],
    ['del', '🗑 삭제', true],
  ];
  const pop = document.createElement('div');
  pop.className = 'wk-pop';
  pop.innerHTML = items.filter(x => x[2]).map(([k, l]) => `<button type="button" data-m="${k}" class="${k === 'del' ? 'danger-text' : ''}">${l}</button>`).join('');
  document.body.appendChild(pop);
  placePop(pop, anchor);
  pop.addEventListener('click', e => {
    const b = e.target.closest('[data-m]');
    if (!b) return;
    closeWikiPop();
    const m = b.dataset.m;
    if (m === 'child') newPage(p);
    else if (m === 'up') placePage(p, sibs[i - 1], 'before');
    else if (m === 'down') placePage(p, sibs[i + 1], 'after');
    else if (m === 'indent') placePage(p, sibs[i - 1], 'inside');
    else if (m === 'outdent') placePage(p, byId(S.notes, p.parentId), 'after');
    else if (m === 'del') deletePage(p);
  });
}

function iconPicker(p, anchor) {
  closeWikiPop();
  const pop = document.createElement('div');
  pop.className = 'wk-pop wk-icons';
  pop.innerHTML = WIKI_ICONS.map(ic => `<button type="button" data-ic="${ic}" aria-label="${ic}">${ic}</button>`).join('')
    + '<button type="button" data-ic="" class="wk-noicon">아이콘 없애기</button>';
  document.body.appendChild(pop);
  placePop(pop, anchor);
  pop.addEventListener('click', e => {
    const b = e.target.closest('[data-ic]');
    if (!b) return;
    closeWikiPop();
    p.icon = b.dataset.ic;
    touchPage(p);
    render();
  });
}

function keysHelp(anchor) {
  closeWikiPop();
  const pop = document.createElement('div');
  pop.className = 'wk-pop wk-keys';
  const row = (k, v) => `<div><code>${esc(k)}</code><span>${v}</span></div>`;
  pop.innerHTML = '<b>줄 맨 앞에서 (스페이스)</b>'
    + row('# / ## / ###', '제목 1·2·3') + row('- 또는 *', '글머리 목록') + row('1.', '번호 목록') + row('[] 또는 [x]', '체크리스트')
    + row('>', '인용') + row('```', '코드 블록') + row('---', '구분선') + row('/', '블록 메뉴')
    + '<b>문장 안에서</b>'
    + row('**글자**', '<b>굵게</b>') + row('*글자*', '<i>기울임</i>') + row('~~글자~~', '<s>취소선</s>') + row('`글자`', '코드') + row('==글자==', '형광펜')
    + '<b>단축키</b>' + row('⌘/Ctrl + B · I', '굵게 · 기울임') + row('⌘/Ctrl + K', '링크') + row('Tab · Shift+Tab', '목록 들여쓰기·내어쓰기');
  document.body.appendChild(pop);
  placePop(pop, anchor);
}

function placePop(pop, anchor) {
  const r = anchor.getBoundingClientRect();
  const left = Math.min(Math.max(8, r.left), window.innerWidth - pop.offsetWidth - 8);
  pop.style.left = left + window.scrollX + 'px';
  pop.style.top = r.bottom + window.scrollY + 4 + 'px';
  setTimeout(() => document.addEventListener('click', closeWikiPopOutside, { capture: true }), 0);
}
function closeWikiPopOutside(e) { if (!e.target.closest('.wk-pop')) closeWikiPop(); }
function closeWikiPop() {
  document.querySelectorAll('.wk-pop').forEach(x => x.remove());
  document.removeEventListener('click', closeWikiPopOutside, { capture: true });
}

function bindWiki(root, cur, editable) {
  $('#note-q', root).addEventListener('input', e => {
    UI.noteQuery = e.target.value;
    render();
    const input = $('#note-q');
    input.focus();
    input.setSelectionRange(input.value.length, input.value.length);
  });
  on(root, 'click', '[data-act="new"]', () => newPage(null));
  on(root, 'click', '[data-act="fold"]', () => {
    const w = root.querySelector('.wiki');
    const mini = !w.classList.contains('side-mini');
    w.classList.toggle('side-mini', mini);
    try { localStorage.setItem('wikiSideMini', mini ? '1' : '0'); } catch (e) { /* 무시 */ }
  });
  on(root, 'click', '[data-act="tree"]', () => { UI.wikiTree = !UI.wikiTree; root.querySelector('.wiki').classList.toggle('tree-open', UI.wikiTree); });
  on(root, 'click', '[data-go]', (el, e) => { e.preventDefault(); selectPage(el.dataset.go); });
  on(root, 'click', '.wk-row', (el, e) => {
    const act = e.target.closest('[data-act]');
    const p = byId(S.notes, el.dataset.id);
    if (!p) return;
    if (act && act.dataset.act === 'toggle') {
      const open = wikiOpenSet();
      if (open.has(p.id)) open.delete(p.id); else open.add(p.id);
      saveOpenSet();
      render();
    } else if (act && act.dataset.act === 'child') newPage(p);
    else if (act && act.dataset.act === 'menu') { e.stopPropagation(); pageMenu(p, act); }
    else selectPage(p.id);
  });

  // 끌어서 옮기기: 줄의 위쪽 1/4 = 앞, 아래쪽 1/4 = 뒤, 가운데 = 안으로
  const zone = (el, e) => {
    const r = el.getBoundingClientRect();
    const y = (e.clientY - r.top) / r.height;
    return y < 0.25 ? 'before' : y > 0.75 ? 'after' : 'inside';
  };
  on(root, 'dragstart', '.wk-row[draggable]', (el, e) => { wikiDrag = el.dataset.id; e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', wikiDrag); });
  on(root, 'dragover', '.wk-row', (el, e) => {
    if (!wikiDrag || wikiDrag === el.dataset.id) return;
    e.preventDefault();
    root.querySelectorAll('.wk-row.drop-before,.wk-row.drop-after,.wk-row.drop-inside').forEach(x => x.classList.remove('drop-before', 'drop-after', 'drop-inside'));
    el.classList.add('drop-' + zone(el, e));
  });
  on(root, 'dragleave', '.wk-row', el => el.classList.remove('drop-before', 'drop-after', 'drop-inside'));
  on(root, 'drop', '.wk-row', (el, e) => {
    e.preventDefault();
    const page = byId(S.notes, wikiDrag);
    wikiDrag = null;
    placePage(page, byId(S.notes, el.dataset.id), zone(el, e));
  });
  on(root, 'dragend', '.wk-row', () => { wikiDrag = null; });

  if (!cur || !editable) return;
  on(root, 'click', '[data-act="child-cur"]', () => newPage(cur));
  on(root, 'click', '[data-act="icon"]', (el, e) => { e.stopPropagation(); iconPicker(cur, el); });
  on(root, 'click', '[data-act="pin"]', () => { cur.pinned = !cur.pinned; save('notes', cur); render(); });
  on(root, 'click', '[data-act="keys"]', (el, e) => { e.stopPropagation(); keysHelp(el); });
  const title = $('#note-title', root);
  title.addEventListener('input', () => {
    cur.title = title.value;
    const t = root.querySelector(`.wk-row[data-id="${CSS.escape(cur.id)}"] .wk-t`);
    if (t) t.textContent = pageTitle(cur);
    const c = root.querySelector('.wk-crumbs .cur');
    if (c) c.textContent = pageTitle(cur);
    touchPage(cur);
  });
  title.addEventListener('keydown', e => {
    if (e.key === 'Enter' && wikiEditor) { e.preventDefault(); try { wikiEditor.caret.setToFirstBlock('start'); } catch (x) { /* 빈 문서 */ } }
  });
  const spaceSel = $('#note-space', root);
  if (spaceSel) spaceSel.addEventListener('change', async () => {
    const to = spaceSel.value;
    const kids = descendantsOf(cur).length;
    if (!confirm(`이 페이지${kids ? `와 하위 페이지 ${kids}개` : ''}를 "${spaceById(to).name}" 으로 옮길까요?${isTeam(to) ? '\n팀원 모두가 보게 됩니다.' : ''}`)) {
      spaceSel.value = cur.spaceId;
      return;
    }
    flushNote();
    destroyEditor();
    await moveItem('notes', cur, to);
  });
}

// ───────── 노션식 마크다운 단축 입력 ─────────
/*
 * Editor.js 에는 없어서 직접 붙인다. 한글 조합 중에는 건드리지 않는다.
 *   줄 맨 앞:  "# " "## " "### " → 제목,  "- " "* " → 글머리,  "1. " → 번호,  "[] " "[x] " → 체크리스트,
 *              "> " → 인용,  "```" → 코드,  "---" → 구분선
 *   문장 안:   **굵게**  __굵게__  *기울임*  _기울임_  ~~취소선~~  `코드`  ==형광펜==
 */
const MD_BLOCKS = [
  [/^(#{1,3}) /, m => ['header', { text: '', level: m[1].length }]],
  [/^[-*+] /, () => ['list', { style: 'unordered', meta: {}, items: [] }]],
  [/^1[.)] /, () => ['list', { style: 'ordered', meta: { start: 1 }, items: [] }]],
  [/^\[( |x|X)?\] /, m => ['list', { style: 'checklist', meta: {}, items: [], checked: /x/i.test(m[1] || '') }]],
  [/^> /, () => ['quote', { text: '', caption: '', alignment: 'left' }]],
];
const MD_INLINE = [
  [/\*\*([^*\s](?:[^*]*[^*\s])?)\*\*$/, 'b'],
  [/__([^_\s](?:[^_]*[^_\s])?)__$/, 'b'],
  [/~~([^~\s](?:[^~]*[^~\s])?)~~$/, 's'],
  [/==([^=\s](?:[^=]*[^=\s])?)==$/, 'mark'],
  [/`([^`\s](?:[^`]*[^`\s])?)`$/, 'code'],
  [/(?:^|[^*\w])\*([^*\s](?:[^*]*[^*\s])?)\*$/, 'i'],
  [/(?:^|[^_\w])_([^_\s](?:[^_]*[^_\s])?)_$/, 'i'],
];

function caretOffsetIn(el) {
  const sel = getSelection();
  if (!sel.rangeCount) return -1;
  const r = sel.getRangeAt(0);
  if (!r.collapsed || !el.contains(r.startContainer)) return -1;
  const pre = document.createRange();
  pre.selectNodeContents(el);
  pre.setEnd(r.startContainer, r.startOffset);
  return pre.toString().length;
}
/** el 앞의 글자 n 개를 지운 HTML (표시 기호를 지우고 나머지 서식은 그대로) */
function htmlWithoutPrefix(el, n) {
  const copy = el.cloneNode(true);
  const walker = document.createTreeWalker(copy, NodeFilter.SHOW_TEXT);
  let left = n;
  for (let t = walker.nextNode(); t && left > 0; t = walker.nextNode()) {
    const cut = Math.min(left, t.data.length);
    t.data = t.data.slice(cut);
    left -= cut;
  }
  return copy.innerHTML.replace(/^(\s|&nbsp;)+/, '');
}

/** index 블록을 새 블록으로 바꾼다. 편집기의 "바꿔 끼우기"는 첫 블록에서 바꾸지 않고 추가만 해서, 넣고 지우는 두 단계로 한다 */
function replaceBlock(editor, index, type, data) {
  editor.blocks.insert(type, data, undefined, index, true);
  editor.blocks.delete(index + 1);
  setTimeout(() => focusBlockText(editor, index), 0);
}

/** 줄 맨 앞 기호를 블록으로 바꾼다. @return 바꿨으면 true */
function mdBlock(editor) {
  const index = editor.blocks.getCurrentBlockIndex();
  const block = index >= 0 && editor.blocks.getBlockByIndex(index);
  if (!block || block.name !== 'paragraph') return false;
  const el = block.holder.querySelector('.ce-paragraph');
  if (!el) return false;
  const text = el.textContent.replace(/\u00a0/g, ' ');
  const at = caretOffsetIn(el);
  const replace = (type, data) => replaceBlock(editor, index, type, data);
  if (text === '```' && at === 3) { replace('code', { code: '' }); return true; }
  if (text === '---' && at === 3) {
    editor.blocks.insert('delimiter', {}, undefined, index, false);
    editor.blocks.delete(index + 1);
    setTimeout(() => {
      editor.blocks.insert('paragraph', { text: '' }, undefined, index + 1, true);
      setTimeout(() => focusBlockText(editor, index + 1), 0);
    }, 0);
    return true;
  }
  for (const [re, make] of MD_BLOCKS) {
    const m = text.match(re);
    if (!m || at !== m[0].length) continue;
    const rest = htmlWithoutPrefix(el, m[0].length);
    const [type, data] = make(m);
    if (type === 'header' || type === 'quote') {
      // 제목·인용은 같은 블록을 바꾼다 (새 블록으로 갈아 끼우면 편집기가 지금 블록을 놓쳐 Enter 가 안 먹는다)
      el.innerHTML = rest;
      editor.blocks.convert(block.id, type, type === 'header' ? { level: data.level } : { alignment: 'left' })
        .then(() => setTimeout(() => focusBlockText(editor, index), 0))
        .catch(() => replace(type, { ...data, text: rest }));
      return true;
    }
    if (type === 'list') {
      const checked = data.checked;
      delete data.checked;
      data.items = [{ content: rest, meta: data.style === 'checklist' ? { checked } : {}, items: [] }];
    } else {
      data.text = rest;
    }
    replace(type, data);
    return true;
  }
  return false;
}

/**
 * 블록의 첫 입력칸(인용이면 본문, 출처 칸 아님) 끝에 커서를 둔다.
 * 편집기의 커서 기능으로 블록에 들어가야 Enter 등이 그 블록 기준으로 동작한다
 */
function focusBlockText(editor, index) {
  const block = editor.blocks.getBlockByIndex(index);
  const el = block && block.holder.querySelector('[contenteditable="true"], textarea');
  const len = el ? (el.tagName === 'TEXTAREA' ? el.value.length : el.textContent.length) : 0;
  // 'default' + 글자 수 = 첫 입력칸의 그 위치. 편집기 기능으로 옮겨야 편집기가 지금 입력칸을 바로 안다
  try { editor.caret.setToBlock(index, 'default', len); } catch (e) { /* 블록이 사라짐 */ }
}

/** 방금 닫은 **…** 같은 표시를 서식으로 바꾼다 */
function mdInline() {
  const sel = getSelection();
  if (!sel.rangeCount || !sel.getRangeAt(0).collapsed) return;
  const node = sel.anchorNode;
  if (!node || node.nodeType !== 3) return;
  const holderEl = node.parentElement && node.parentElement.closest('[contenteditable="true"]');
  if (!holderEl || node.parentElement.closest('code, .ce-code, pre')) return; // 코드 안에서는 그대로
  const before = node.data.slice(0, sel.anchorOffset);
  for (const [re, tag] of MD_INLINE) {
    const m = before.match(re);
    if (!m) continue;
    const full = m[0].slice(m[0].indexOf(m[0].replace(/^[^*_~=`]/, ''))); // 앞에 붙은 구분 글자 제외
    const start = before.length - full.length;
    const after = node.data.slice(sel.anchorOffset);
    const el = document.createElement(tag);
    if (tag === 'mark') el.className = 'cdx-marker';
    if (tag === 'code') el.className = 'inline-code';
    el.textContent = m[1];
    const tail = document.createTextNode(after || '\u200b'); // 서식 밖에서 이어 쓰도록 빈 글자 하나
    node.data = node.data.slice(0, start);
    node.after(el, tail);
    const r = document.createRange();
    r.setStart(tail, after ? 0 : 1);
    r.collapse(true);
    sel.removeAllRanges();
    sel.addRange(r);
    return;
  }
}

function attachMarkdown(holder, editor) {
  const check = e => {
    if (e.isComposing) return;
    if (!mdBlock(editor)) mdInline();
  };
  // 제목·인용에서 Enter = 새 문단 (커서 뒤 글자는 새 문단으로). 인용의 Shift+Enter 는 줄바꿈. 노션과 같게.
  // 편집기는 블록을 바꾼 직후 잠시 Enter 를 무시해서, 이 두 블록은 직접 처리한다
  holder.addEventListener('keydown', e => {
    if (e.key !== 'Enter' || e.shiftKey || e.isComposing) return;
    const input = e.target.closest('.ce-header, .cdx-quote__text');
    if (!input) return;
    const index = editor.blocks.getCurrentBlockIndex();
    const block = index >= 0 && editor.blocks.getBlockByIndex(index);
    if (!block || !block.holder.contains(input)) return;
    e.preventDefault();
    e.stopPropagation();
    let tail = '';
    const sel = getSelection();
    if (sel.rangeCount && input.contains(sel.anchorNode)) {
      const r = sel.getRangeAt(0);
      r.deleteContents();
      const rest = document.createRange();
      rest.setStart(r.startContainer, r.startOffset);
      rest.setEnd(input, input.childNodes.length);
      const box = document.createElement('div');
      box.appendChild(rest.extractContents());
      tail = box.innerHTML.replace(/^(<br>)+$/, '');
    }
    editor.blocks.insert('paragraph', { text: tail }, undefined, index + 1, true);
    setTimeout(() => { try { editor.caret.setToBlock(index + 1, 'start'); } catch (x) { /* 무시 */ } }, 0);
  }, true);
  holder.addEventListener('input', check);
  holder.addEventListener('compositionend', () => setTimeout(() => check({ isComposing: false }), 0));
}

// ───────── 취소선·밑줄 도구 (Editor.js 에 없어서 직접. sanitize 에 태그를 넣어야 저장된다) ─────────
/** 태그 하나로 감싸는 인라인 도구 (취소선 <s>, 밑줄 <u>). 편집기 기본에 없어서 직접 만든다 */
function tagTool(tag, title, icon) {
  return class {
    static get isInline() { return true; }
    static get title() { return title; }
    static get sanitize() { return tag === 's' ? { s: {}, strike: {} } : { [tag]: {} }; }
    constructor({ api }) { this.api = api; this.button = null; }
    render() {
      this.button = document.createElement('button');
      this.button.type = 'button';
      this.button.classList.add(this.api.styles.inlineToolButton);
      this.button.innerHTML = icon;
      return this.button;
    }
    surround(range) {
      if (!range) return;
      const found = this.api.selection.findParentTag(tag.toUpperCase());
      if (found) { found.replaceWith(...found.childNodes); return; }
      const el = document.createElement(tag);
      el.appendChild(range.extractContents());
      range.insertNode(el);
      this.api.selection.expandToTag(el);
    }
    checkState() {
      const on = !!this.api.selection.findParentTag(tag.toUpperCase());
      this.button.classList.toggle(this.api.styles.inlineToolButtonActive, on);
      return on;
    }
  };
}
const StrikeTool = tagTool('s', '취소선', '<span style="text-decoration:line-through;font-weight:600;font-size:14px">S</span>');
const UnderlineTool = tagTool('u', '밑줄', '<span style="text-decoration:underline;font-weight:600;font-size:14px">U</span>');

// ───────── 이미지 크기 (이미지 블록 ⠿ 메뉴). 값은 블록의 tunes.imageSize 로 저장된다 ─────────
class ImageSizeTune {
  static get isTune() { return true; }
  constructor({ data, block }) { this.block = block; this.data = { size: (data && data.size) || 100 }; this.wrapper = null; }
  render() {
    return [25, 50, 75, 100].map(size => ({
      icon: `<svg width="20" height="20" viewBox="0 0 20 20"><rect x="${10 - size / 10}" y="5" width="${size / 5}" height="10" rx="2" fill="none" stroke="currentColor" stroke-width="1.6"/></svg>`,
      title: size === 100 ? '이미지 원래 크기' : `이미지 ${size}%`,
      isActive: this.data.size === size,
      closeOnActivate: true,
      onActivate: () => { this.data.size = size; this.apply(); this.block.dispatchChange(); },
    }));
  }
  wrap(content) {
    this.wrapper = document.createElement('div');
    this.wrapper.className = 'wk-imgsize';
    this.wrapper.appendChild(content);
    this.apply();
    return this.wrapper;
  }
  apply() { if (this.wrapper) this.wrapper.style.width = this.data.size === 100 ? '' : this.data.size + '%'; }
  save() { return this.data; }
}

// ───────── 서식 막대 (편집기 위에 고정) ─────────
function formatBarHtml() {
  const b = (act, label, title) => `<button type="button" class="wk-tb" data-fmt="${act}" title="${title}">${label}</button>`;
  return `<div class="wk-toolbar" role="toolbar" aria-label="서식">
    <select class="wk-tb-sel" id="wk-format" title="블록 형식" aria-label="블록 형식">
      <option value="paragraph">본문</option><option value="h1">제목 1</option><option value="h2">제목 2</option>
      <option value="h3">제목 3</option><option value="quote">인용</option><option value="code">코드 블록</option>
      <option value="unordered">글머리 목록</option><option value="ordered">번호 목록</option><option value="checklist">체크리스트</option>
    </select>
    ${b('bold', '<b>B</b>', '굵게 (⌘B)')}${b('italic', '<i style="font-family:Georgia,serif">I</i>', '기울임 (⌘I)')}${b('underline', '<u>U</u>', '밑줄 (⌘U)')}${b('strike', '<s>S</s>', '취소선 (⌘⇧X)')}
    ${b('code', '<span class="mono">&lt;/&gt;</span>', '인라인 코드 (⌘⇧C)')}${b('mark', '<mark>A</mark>', '형광펜 (⌘⇧M)')}${b('link', '<svg class="wk-tb-ic" viewBox="0 0 24 24" aria-hidden="true"><path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/></svg>', '링크 (⌘K)')}
    <span class="wk-tb-sep"></span>
    ${b('unordered', '<svg class="wk-tb-ic" viewBox="0 0 24 24" aria-hidden="true"><path d="M10 6h10M10 12h10M10 18h10"/><circle cx="5" cy="6" r="1.2" fill="currentColor"/><circle cx="5" cy="12" r="1.2" fill="currentColor"/><circle cx="5" cy="18" r="1.2" fill="currentColor"/></svg>', '글머리 목록 (- 띄어쓰기)')}${b('ordered', '<svg class="wk-tb-ic" viewBox="0 0 24 24" aria-hidden="true"><path d="M10 6h10M10 12h10M10 18h10M4 5l1.5-1v5M3.8 15.5a1.6 1.6 0 1 1 2.6 1.3L4 19h3"/></svg>', '번호 목록 (1. 띄어쓰기)')}${b('checklist', '<svg class="wk-tb-ic" viewBox="0 0 24 24" aria-hidden="true"><rect x="3.5" y="4" width="6" height="6" rx="1.5"/><path d="m4.8 7 1.2 1.2L8.4 5.8M13 7h7M3.5 14h6v6h-6zM13 17h7"/></svg>', '체크리스트 ([] 띄어쓰기)')}
    <select class="wk-tb-sel" id="wk-insert" title="블록 넣기" aria-label="블록 넣기">
      <option value="">넣기</option><option value="table">표</option><option value="delimiter">구분선</option>
      <option value="code">코드 블록</option><option value="quote">인용</option><option value="subpage">하위 페이지</option>
    </select>
    <span class="wk-tb-sep"></span>
    ${b('image', '<svg class="wk-tb-ic" viewBox="0 0 24 24" aria-hidden="true"><rect x="3.5" y="4.5" width="17" height="15" rx="2.5"/><circle cx="9" cy="10" r="1.6"/><path d="m4 18 5-5 4 4 3-3 4 4"/></svg>', '이미지 넣기 (붙여넣기·끌어 놓기도 됩니다)')}
  </div>`;
}

let wikiBlockIndex = 0; // 마지막으로 커서가 있던 블록 (막대를 누르면 편집기에서 포커스가 빠지므로 기억해 둔다)
let wikiRange = null;   // 마지막 선택 영역 (서식 적용용)
function rememberSelection() {
  const holder = document.getElementById('wiki-editor');
  const sel = getSelection();
  if (!wikiEditor || !holder || !sel.rangeCount || !holder.contains(sel.anchorNode)) return;
  wikiRange = sel.getRangeAt(0).cloneRange();
  const i = wikiEditor.blocks.getCurrentBlockIndex();
  if (i >= 0) wikiBlockIndex = i;
  const fmt = document.getElementById('wk-format');
  const block = wikiEditor.blocks.getBlockByIndex(wikiBlockIndex);
  if (fmt && block && document.activeElement !== fmt) {
    const h = block.holder.querySelector('h1,h2,h3');
    const list = block.holder.querySelector('.cdx-list');
    fmt.value = block.name === 'header' && h ? h.tagName.toLowerCase()
      : block.name === 'list' && list ? (list.classList.contains('cdx-list-ordered') ? 'ordered' : list.querySelector('.cdx-list__checkbox') ? 'checklist' : 'unordered')
        : ['quote', 'code'].includes(block.name) ? block.name : 'paragraph';
  }
}
document.addEventListener('selectionchange', rememberSelection);

function restoreSelection() {
  if (!wikiRange) return false;
  const sel = getSelection();
  sel.removeAllRanges();
  sel.addRange(wikiRange);
  return !wikiRange.collapsed;
}

/** 선택한 글자를 태그로 감싸거나(이미 감싸져 있으면 푼다) */
function toggleInline(tag, cls) {
  if (!restoreSelection()) { setStatus('먼저 글자를 선택하세요'); return; }
  const sel = getSelection();
  const range = sel.getRangeAt(0);
  const node = range.commonAncestorContainer.nodeType === 1 ? range.commonAncestorContainer : range.commonAncestorContainer.parentElement;
  const found = node.closest(cls ? `${tag}.${cls}` : tag);
  if (found && document.getElementById('wiki-editor').contains(found)) { found.replaceWith(...found.childNodes); return; }
  const el = document.createElement(tag);
  if (cls) el.className = cls;
  el.appendChild(range.extractContents());
  range.insertNode(el);
  sel.removeAllRanges();
  const r = document.createRange();
  r.selectNodeContents(el);
  sel.addRange(r);
}

/** 지금 블록을 다른 형식으로 바꾼다 (글자는 그대로) */
async function convertCurrent(editor, kind) {
  const index = wikiBlockIndex;
  const block = editor.blocks.getBlockByIndex(index);
  if (!block) return;
  const data = (await editor.save()).blocks[index] || { type: 'paragraph', data: { text: '' } };
  const html = data.type === 'list' ? (data.data.items || []).map(i => i.content).join('<br>')
    : data.type === 'code' ? esc(data.data.code || '') : (data.data && data.data.text) || '';
  const lists = { unordered: 'unordered', ordered: 'ordered', checklist: 'checklist' };
  let type, next;
  if (kind === 'paragraph') { type = 'paragraph'; next = { text: html }; }
  else if (/^h[123]$/.test(kind)) { type = 'header'; next = { text: html, level: Number(kind[1]) }; }
  else if (kind === 'quote') { type = 'quote'; next = { text: html, caption: '', alignment: 'left' }; }
  else if (kind === 'code') { type = 'code'; next = { code: stripTags(html.replace(/<br\s*\/?>/gi, '\n')) }; }
  else if (lists[kind]) {
    type = 'list';
    const parts = data.type === 'list' ? (data.data.items || []).map(i => i.content) : [html];
    next = { style: lists[kind], meta: {}, items: parts.map(c => ({ content: c, meta: kind === 'checklist' ? { checked: false } : {}, items: [] })) };
  } else return;
  replaceBlock(editor, index, type, next);
}

function insertAfterCurrent(editor, type, data) {
  const index = Math.min(wikiBlockIndex + 1, editor.blocks.getBlocksCount());
  editor.blocks.insert(type, data, undefined, index, true);
  return index;
}

function bindFormatBar(editor) {
  const bar = document.querySelector('.wk-toolbar');
  if (!bar) return;
  // 막대를 눌러도 편집기의 선택 영역이 풀리지 않게
  bar.addEventListener('mousedown', e => { if (e.target.closest('button')) e.preventDefault(); });
  bar.addEventListener('click', e => {
    const btn = e.target.closest('[data-fmt]');
    if (!btn) return;
    const f = btn.dataset.fmt;
    if (f === 'bold' || f === 'italic') { if (restoreSelection()) document.execCommand(f); else setStatus('먼저 글자를 선택하세요'); }
    else if (f === 'strike') toggleInline('s');
    else if (f === 'underline') toggleInline('u');
    else if (f === 'code') toggleInline('code', 'inline-code');
    else if (f === 'mark') toggleInline('mark', 'cdx-marker');
    else if (f === 'link') {
      if (!restoreSelection()) { setStatus('먼저 링크로 만들 글자를 선택하세요'); return; }
      const saved = wikiRange.cloneRange();
      const url = (prompt('링크 주소 (https://…)') || '').trim();
      if (!url) return;
      if (!/^(https?:|mailto:|\/)/i.test(url)) { setStatus('https:// 로 시작하는 주소를 넣어 주세요', true); return; }
      const sel = getSelection();
      sel.removeAllRanges();
      sel.addRange(saved);
      document.execCommand('createLink', false, url);
    } else if (f === 'image') {
      const index = insertAfterCurrent(editor, 'image', {});
      // 새 이미지 블록의 "이미지 고르기" 버튼을 바로 눌러 파일 창을 연다
      const pickBtn = editor.blocks.getBlockByIndex(index).holder.querySelector('.cdx-button');
      if (pickBtn) pickBtn.click();
    } else convertCurrent(editor, f);
  });
  const fmt = document.getElementById('wk-format');
  fmt.addEventListener('change', () => convertCurrent(editor, fmt.value));
  const ins = document.getElementById('wk-insert');
  ins.addEventListener('change', () => {
    const v = ins.value;
    ins.value = '';
    if (!v) return;
    if (v === 'subpage') { const cur = byId(S.notes, wikiEditorPage); if (cur) newPage(cur); return; }
    const data = { table: { withHeadings: true, content: [['', '', ''], ['', '', '']] }, delimiter: {}, code: { code: '' },
      quote: { text: '', caption: '', alignment: 'left' } }[v];
    const index = insertAfterCurrent(editor, v, data);
    setTimeout(() => focusBlockText(editor, index), 0);
  });
}

// ───────── 블록 끌어 옮기기 (⠿ 손잡이를 끈다. 그냥 누르면 원래대로 설정 메뉴) ─────────
function attachBlockDrag(holder, editor) {
  let drag = null;
  const blocksEls = () => [...holder.querySelectorAll('.ce-block')];
  const gapAt = y => {
    const els = blocksEls();
    for (let i = 0; i < els.length; i++) {
      const r = els[i].getBoundingClientRect();
      if (y < r.top + r.height / 2) return i;
    }
    return els.length;
  };
  holder.addEventListener('pointerdown', e => {
    const handle = e.target.closest('.ce-toolbar__settings-btn');
    if (!handle || e.button !== 0) return;
    const els = blocksEls();
    const y = handle.getBoundingClientRect().top + 4;
    const from = els.findIndex(el => { const r = el.getBoundingClientRect(); return y >= r.top - 2 && y <= r.bottom; });
    if (from < 0) return;
    drag = { from, startY: e.clientY, moving: false, line: null };
  });
  window.addEventListener('pointermove', e => {
    if (!drag) return;
    if (!drag.moving) {
      if (Math.abs(e.clientY - drag.startY) < 6) return;
      drag.moving = true;
      try { editor.toolbar.close(); } catch (x) { /* 무시 */ }
      blocksEls()[drag.from].classList.add('wk-dragging');
      drag.line = document.createElement('div');
      drag.line.className = 'wk-drop-line';
      holder.appendChild(drag.line);
      document.body.classList.add('wk-drag-on');
    }
    e.preventDefault();
    const els = blocksEls();
    const g = gapAt(e.clientY);
    const hr = holder.getBoundingClientRect();
    const ref = g < els.length ? els[g].getBoundingClientRect().top : els[els.length - 1].getBoundingClientRect().bottom;
    drag.line.style.top = (ref - hr.top - 1) + 'px';
    drag.gap = g;
  }, { passive: false });
  const end = () => {
    if (!drag) return;
    const d = drag;
    drag = null;
    if (!d.moving) return;
    document.body.classList.remove('wk-drag-on');
    d.line.remove();
    holder.querySelectorAll('.wk-dragging').forEach(x => x.classList.remove('wk-dragging'));
    const to = d.gap > d.from ? d.gap - 1 : d.gap;
    // 끌기 끝에 생기는 클릭이 설정 메뉴를 열지 않게
    window.addEventListener('click', ev => { ev.stopPropagation(); ev.preventDefault(); }, { capture: true, once: true });
    if (to !== d.from && to >= 0) {
      editor.blocks.move(to, d.from);
      const cur = byId(S.notes, wikiEditorPage);
      if (cur) captureEditor(cur);
    }
  };
  window.addEventListener('pointerup', end);
  window.addEventListener('pointercancel', end);
}

// ───────── 커서 따라 스크롤 ─────────
// 편집 카드(.wk-main)가 자기 안에서 스크롤되므로, 엔터로 새 줄이 화면 아래에 생겨도 브라우저가 따라가지 않는다.
// 커서가 보이는 영역(위쪽 서식 막대 아래 ~ 아래쪽 여백 위)을 벗어나면 그만큼 바로 스크롤한다
function attachCaretFollow(holder) {
  const follow = () => requestAnimationFrame(() => requestAnimationFrame(() => keepCaretVisible(holder)));
  const KEYS = ['Enter', 'ArrowDown', 'ArrowUp', 'Backspace', 'Delete', 'PageDown', 'PageUp'];
  holder.addEventListener('keydown', e => { if (KEYS.includes(e.key)) follow(); });
  holder.addEventListener('input', follow);
  holder.addEventListener('paste', () => setTimeout(follow, 60));
}

/** 지금 커서 위치 (빈 줄이면 글자 위치가 없어서 그 줄 칸의 위쪽 한 줄로 친다) */
function caretRect(holder) {
  const sel = getSelection();
  if (!sel.rangeCount || !holder.contains(sel.anchorNode)) return null;
  const range = sel.getRangeAt(0).cloneRange();
  range.collapse(false);
  const rect = range.getClientRects()[0];
  if (rect && rect.height) return rect;
  const node = sel.focusNode.nodeType === 1 ? sel.focusNode : sel.focusNode.parentElement;
  const box = (node.closest('[contenteditable="true"]') || node).getBoundingClientRect();
  const line = parseFloat(getComputedStyle(node).lineHeight) || 24;
  return { top: box.top, bottom: Math.min(box.bottom, box.top + line) };
}

function keepCaretVisible(holder) {
  const rect = caretRect(holder);
  if (!rect) return;
  const main = holder.closest('.wk-main');
  const inCard = main && getComputedStyle(main).overflowY !== 'visible' && main.scrollHeight > main.clientHeight;
  const view = inCard ? main.getBoundingClientRect() : { top: 0, bottom: window.innerHeight };
  const bar = document.querySelector('.wk-toolbar');
  const barBottom = bar ? bar.getBoundingClientRect().bottom : 0;
  const top = Math.max(view.top, barBottom) + 8;
  const bottom = view.bottom - (window.innerWidth <= 760 ? 100 : 40); // 휴대폰: 아래쪽 탭 바 자리
  const delta = rect.bottom > bottom ? rect.bottom - bottom : rect.top < top ? rect.top - top : 0;
  if (delta) (inCard ? main : window).scrollBy({ top: delta, behavior: 'auto' });
}

// ───────── 편집기 ─────────
async function mountEditor(cur, editable) {
  const holder = $('#wiki-editor');
  try {
    await loadEditor();
  } catch (e) {
    holder.innerHTML = '<div class="empty">편집기를 불러오지 못했습니다. 새로고침해 주세요.</div>';
    return;
  }
  if (!holder.isConnected || UI.noteId !== cur.id) return; // 그 사이 다른 페이지로 옮김
  holder.innerHTML = '';
  wikiEditorPage = cur.id;
  if (!noteTimer) rememberSaved(cur); // 지금 서버에 있는 내용 (이후 바뀐 게 없으면 저장하지 않는다)
  const sid = cur.spaceId;
  let editor;
  try {
    editor = createEditor(holder, cur, editable, sid);
  } catch (e) {
    wikiEditorPage = null;
    holder.innerHTML = `<div class="empty">편집기를 열지 못했습니다 (${esc(e.message)}). 새로고침해 주세요.</div>`;
    return;
  }
  wikiEditor = editor;
  editor.isReady.then(() => {
    if (!editable) return;
    attachMarkdown(holder, editor);
    attachBlockDrag(holder, editor);
    attachCaretFollow(holder);
    holder.addEventListener('input', noteTyping);
    bindFormatBar(editor);
    const t = $('#note-title');
    if (t && !t.value && document.activeElement !== t) t.focus();
  }).catch(() => {});
}

function createEditor(holder, cur, editable, sid) {
  return new EditorJS({
    holder,
    data: pageData(cur),
    readOnly: !editable,
    minHeight: 120,
    placeholder: "'/' 로 블록 넣기 · # 제목 · - 목록 · [] 체크 · **굵게**",
    tools: {
      header: { class: Header, inlineToolbar: true, config: { levels: [1, 2, 3], defaultLevel: 2, placeholder: '제목' } },
      list: { class: EditorjsList, inlineToolbar: true, config: { defaultStyle: 'unordered' } },
      quote: { class: Quote, inlineToolbar: true },
      code: { class: CodeTool, config: { placeholder: '코드' } },
      delimiter: Delimiter,
      table: { class: Table, inlineToolbar: true, config: { rows: 2, cols: 3 } },
      image: {
        class: ImageTool,
        tunes: ['imageSize'],
        config: {
          features: { caption: false, border: true, background: true, stretch: true }, // 이미지 아래 설명칸은 쓰지 않는다
          buttonContent: '이미지 고르기 (또는 붙여넣기·끌어 놓기)',
          uploader: {
            uploadByFile: file => uploadWikiImage(sid, cur.id, file),
            // 다른 사이트 주소의 이미지는 서버가 대신 받아 오지 않는다 (내 서버로 다른 곳을 찌르는 공격 방지)
            uploadByUrl: () => Promise.resolve({ success: 0 }),
          },
        },
      },
      marker: { class: Marker, shortcut: 'CMD+SHIFT+M' },
      strike: { class: StrikeTool, shortcut: 'CMD+SHIFT+X' },
      underline: { class: UnderlineTool, shortcut: 'CMD+U' },
      imageSize: ImageSizeTune,
      inlineCode: { class: InlineCode, shortcut: 'CMD+SHIFT+C' },
    },
    i18n: { messages: WIKI_I18N },
    onChange: () => { if (editable) captureEditor(cur); },
  });
}

/** 편집기 내용을 페이지에 담고 저장을 예약한다 */
async function captureEditor(cur) {
  if (!wikiEditor || wikiEditorPage !== cur.id) return;
  try {
    const out = await wikiEditor.save();
    cur.content = JSON.stringify(out).replace(/\u200b/g, '');
    cur.body = plainText(out);
    touchPage(cur);
  } catch (e) { /* 저장 직전에 편집기가 사라짐 */ }
}

async function uploadWikiImage(sid, pageId, file) {
  const fd = new FormData();
  fd.append('file', file);
  setStatus('이미지 올리는 중…');
  try {
    const res = await fetch(`/api/spaces/${encodeURIComponent(sid)}/files?page=${encodeURIComponent(pageId)}`, {
      method: 'POST', headers: { 'X-XSRF-TOKEN': csrfToken() }, body: fd, credentials: 'same-origin',
    });
    const json = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(json.error || String(res.status));
    setStatus('이미지를 올렸습니다');
    // 이미지 도구는 업로드를 마친 뒤 바뀜 신호를 보내지 않는다 → 그림이 블록에 들어간 다음 직접 담는다
    setTimeout(() => { const cur = byId(S.notes, pageId); if (cur) captureEditor(cur); }, 400);
    return json;
  } catch (e) {
    setStatus('이미지를 올리지 못했습니다 — ' + e.message, true);
    return { success: 0 };
  }
}

const WIKI_I18N = {
  ui: {
    blockTunes: { toggler: { 'Click to tune': '설정', 'or drag to move': '끌어서 옮기기' } },
    inlineToolbar: { converter: { 'Convert to': '바꾸기' } },
    toolbar: { toolbox: { Add: '추가' } },
    popover: { Filter: '찾기', 'Nothing found': '없습니다', 'Convert to': '바꾸기' },
  },
  toolNames: {
    Text: '본문', Heading: '제목', 'Unordered List': '글머리 목록', 'Ordered List': '번호 목록', Checklist: '체크리스트',
    Quote: '인용', Code: '코드', Delimiter: '구분선', Table: '표', Image: '이미지', Marker: '형광펜', InlineCode: '코드',
    Bold: '굵게', Italic: '기울임', Link: '링크',
  },
  tools: {
    link: { 'Add a link': '링크 주소' },
    stub: { 'The block can not be displayed correctly.': '이 블록은 표시할 수 없습니다' },
    image: { Caption: '설명', 'Select an Image': '이미지 고르기', 'With border': '테두리', 'Stretch image': '넓게', 'With background': '배경' },
    table: { 'With headings': '머리글 있음', 'Without headings': '머리글 없음', 'Add row above': '위에 행 추가', 'Add row below': '아래에 행 추가',
      'Add column to left': '왼쪽에 열 추가', 'Add column to right': '오른쪽에 열 추가', 'Delete row': '행 삭제', 'Delete column': '열 삭제' },
    quote: { 'Align Left': '왼쪽 정렬', 'Align Center': '가운데 정렬' },
    list: { Unordered: '글머리', Ordered: '번호', Checklist: '체크리스트' },
  },
  blockTunes: {
    delete: { Delete: '삭제', 'Click to delete': '한 번 더 누르면 삭제' },
    moveUp: { 'Move up': '위로' },
    moveDown: { 'Move down': '아래로' },
  },
};
