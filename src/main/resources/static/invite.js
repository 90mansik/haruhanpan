'use strict';
// 초대 링크(팀 참여·가입)와 비밀번호 재설정 링크. 토큰은 주소의 # 뒤에 있어 서버 로그에 남지 않는다.
(function () {
  const token = location.hash.slice(1);
  const content = document.getElementById('content');
  const err = document.getElementById('err');
  const ROLE = { owner: '관리자', editor: '편집', viewer: '보기' };
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  function csrfToken() {
    const m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
    return m ? decodeURIComponent(m[1]) : '';
  }

  async function post(path, body) {
    const res = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
      body: JSON.stringify(body),
      credentials: 'same-origin',
    });
    let json = {};
    try { json = await res.json(); } catch (e) { /* 본문 없음 */ }
    if (!res.ok) throw new Error(json.error || ('오류 ' + res.status));
    return json;
  }

  function form(html, onSubmit) {
    content.className = '';
    content.innerHTML = `<form id="f" class="stack" style="gap:10px" novalidate>${html}</form>`;
    const f = document.getElementById('f');
    f.addEventListener('submit', async e => {
      e.preventDefault();
      err.textContent = '';
      const btn = f.querySelector('button[type=submit]');
      btn.disabled = true;
      try { await onSubmit(f); } catch (ex) { err.textContent = ex.message; }
      btn.disabled = false;
    });
    const first = f.querySelector('input');
    if (first) first.focus();
    return f;
  }

  const strong = (f, a, b) => {
    if (f[a].value.length < 12) throw new Error('비밀번호는 12자 이상');
    if (f[a].value !== f[b].value) throw new Error('두 비밀번호가 다릅니다');
  };

  async function start() {
    if (!token) { content.textContent = '링크가 올바르지 않습니다.'; return; }
    let info;
    try { info = await post('/api/invites/peek', { token }); } catch (e) { content.textContent = '서버에 연결할 수 없습니다.'; return; }
    if (!info.valid) { content.textContent = '만료되었거나 이미 사용한 링크입니다. 관리자에게 새 링크를 요청하세요.'; return; }

    if (info.kind === 'reset') {
      form(`<p style="margin:0">아이디 <b>${esc(info.login)}</b> 의 새 비밀번호를 정하세요.</p>
        <input type="password" name="pw" placeholder="새 비밀번호 (12자 이상)" autocomplete="new-password">
        <input type="password" name="pw2" placeholder="새 비밀번호 확인" autocomplete="new-password">
        <button type="submit">비밀번호 바꾸기</button>`, async f => {
        strong(f, 'pw', 'pw2');
        const res = await post('/api/invites/reset', { token, password: f.pw.value });
        location.replace('/login.html?login=' + encodeURIComponent(res.login));
      });
      return;
    }

    const title = `<p style="margin:0;text-align:center"><b>${esc(info.spaceName)}</b> 팀 공간에<br>${esc(ROLE[info.role] || info.role)} 권한으로 초대되었습니다.</p>`;
    if (info.loggedInAs) {
      form(`${title}<button type="submit">${esc(info.loggedInAs)} 계정으로 참여하기</button>`, async () => {
        await post('/api/invites/accept', { token });
        location.replace('/');
      });
      return;
    }
    let googleOn = false;
    try { googleOn = (await (await fetch('/api/session', { credentials: 'same-origin' })).json()).google; } catch (e) { /* 무시 */ }
    const f = form(`${title}
      ${googleOn ? `<button type="button" class="google-btn" id="google-signup">Google 계정으로 가입하고 참여</button>
      <div class="or"><span>또는 아이디 만들기</span></div>` : ''}
      <input type="text" name="login" placeholder="아이디 (영문 소문자·숫자 3~30자)" autocomplete="username" autocapitalize="none" spellcheck="false">
      <input type="text" name="name" placeholder="이름 (팀에 보이는 이름)" autocomplete="name">
      <input type="password" name="pw" placeholder="비밀번호 (12자 이상)" autocomplete="new-password">
      <input type="password" name="pw2" placeholder="비밀번호 확인" autocomplete="new-password">
      <button type="submit">가입하고 참여하기</button>
      <p class="muted small" style="margin:0;text-align:center">이미 계정이 있나요? <a href="/login.html" id="to-login">로그인 후 참여</a></p>`, async form => {
      strong(form, 'pw', 'pw2');
      const res = await post('/api/invites/accept', {
        token, login: form.login.value.trim().toLowerCase(), name: form.name.value.trim(), password: form.pw.value,
      });
      location.replace('/login.html?login=' + encodeURIComponent(res.login));
    });
    f.querySelector('#to-login').addEventListener('click', () => {
      try { sessionStorage.setItem('inviteToken', token); } catch (e) { /* 무시 */ }
    });
    const g = f.querySelector('#google-signup');
    if (g) g.addEventListener('click', async () => {
      err.textContent = '';
      g.disabled = true;
      try {
        const res = await post('/api/invites/google', { token });
        location.href = res.url; // Google 로그인 화면으로 → 돌아오면 가입·참여 완료
      } catch (e) { err.textContent = e.message; g.disabled = false; }
    });
  }

  start();
})();
