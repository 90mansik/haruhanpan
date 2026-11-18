'use strict';
// 로그인 화면
(function () {
  const form = document.getElementById('login-form');
  const err = document.getElementById('login-err');
  const button = form.querySelector('button');

  // 마지막으로 쓴 아이디 / 가입 직후 넘어온 아이디를 미리 채운다
  let last = new URLSearchParams(location.search).get('login') || '';
  try { last = last || localStorage.getItem('lastLogin') || ''; } catch (e) { /* 무시 */ }
  form.username.value = last;
  (last ? form.password : form.username).focus();

  // Google 로그인 버튼 (서버에 설정돼 있을 때만)
  fetch('/api/session', { credentials: 'same-origin' }).then(r => r.json()).then(s => {
    if (s.google) {
      document.getElementById('google-btn').classList.remove('hidden');
      document.getElementById('google-or').classList.remove('hidden');
    }
    if (s.googleSignup) {
      document.getElementById('signup-note').textContent =
        '처음이면 Google 계정으로 가입 신청하세요. 관리자 승인 후 사용할 수 있습니다 (초대 링크가 있으면 바로 참여)';
    }
  }).catch(() => {});

  // Google 로그인 후 돌아왔을 때의 오류
  const ERRORS = {
    google_not_invited: '초대받지 않은 Google 계정입니다. 팀 관리자에게 초대 링크를 받아 "Google 계정으로 가입"을 눌러 주세요. 이미 계정이 있다면 아이디로 로그인한 뒤 내 계정에서 Google 을 연결하세요.',
    google_in_use: '이 Google 계정은 이미 다른 사용자에 연결되어 있습니다',
    google_unverified: '이메일 인증이 끝나지 않은 Google 계정입니다',
    invite_invalid: '초대 링크가 만료되었거나 이미 사용되었습니다',
    disabled: '사용이 중지된 계정입니다',
    google: 'Google 로그인에 실패했습니다. 다시 시도하세요',
  };
  const code = new URLSearchParams(location.search).get('error');
  if (code) err.textContent = ERRORS[code] || ERRORS.google;

  function csrfToken() {
    const m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
    return m ? decodeURIComponent(m[1]) : '';
  }

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const username = form.username.value.trim().toLowerCase();
    const password = form.password.value;
    if (!username || !password) { err.textContent = '아이디와 비밀번호를 입력하세요'; return; }
    button.disabled = true;
    err.textContent = '';
    try {
      const res = await fetch('/api/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-XSRF-TOKEN': csrfToken() },
        body: new URLSearchParams({ username, password }),
        credentials: 'same-origin',
      });
      if (res.ok) {
        try { localStorage.setItem('lastLogin', username); } catch (ex) { /* 무시 */ }
        // 초대 링크를 열었다가 로그인하러 왔으면 그 링크로 돌아간다
        let invite = null;
        try { invite = sessionStorage.getItem('inviteToken'); sessionStorage.removeItem('inviteToken'); } catch (ex) { /* 무시 */ }
        location.replace(invite ? '/invite.html#' + invite : '/');
        return;
      }
      if (res.status === 429) err.textContent = '시도가 너무 많습니다. 잠시 후 다시 시도하세요';
      else if (res.status === 401) err.textContent = '아이디 또는 비밀번호가 맞지 않습니다';
      else if (res.status === 403) err.textContent = '페이지를 새로고침한 뒤 다시 시도하세요';
      else err.textContent = '로그인할 수 없습니다 (' + res.status + ')';
    } catch (ex) {
      err.textContent = '서버에 연결할 수 없습니다';
    }
    button.disabled = false;
    form.password.select();
  });
})();
