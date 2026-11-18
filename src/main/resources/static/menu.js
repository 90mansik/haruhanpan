'use strict';
/* 휴대폰 상단 바 ☰ 메뉴 (홈·하루한판·하루가계부 공용).
 * 상단 .tools 안에서 data-m 이 붙은 버튼은 휴대폰 폭에서 숨기고(style.css), ☰ 를 누르면 목록으로 보여 준다.
 * 목록을 누르면 원래 버튼을 대신 눌러 준다 (기능은 각 화면의 원래 버튼 코드 그대로).
 *   data-m="이름"       메뉴에 보일 이름
 *   data-m-text         이름 뒤에 버튼 글자를 붙인다 (내 계정 · 홍길동)
 *   data-m-info         누를 수 없는 안내 줄 (로그인한 이름)
 */
(function () {
  const tools = document.querySelector('.tools');
  if (!tools) return;
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'icon menu-btn';
  btn.textContent = '☰';
  btn.title = '메뉴';
  btn.setAttribute('aria-label', '메뉴');
  tools.appendChild(btn);

  const dlg = document.createElement('dialog');
  dlg.className = 'menu-sheet';
  document.body.appendChild(dlg);
  dlg.addEventListener('click', e => { if (e.target === dlg) dlg.close(); }); // 바깥을 누르면 닫기

  btn.addEventListener('click', () => {
    const items = [...tools.querySelectorAll('[data-m], [data-m-info]')].filter(el => !el.classList.contains('hidden'));
    dlg.innerHTML = '<div class="menu-list"></div>';
    const list = dlg.firstChild;
    for (const el of items) {
      if (el.hasAttribute('data-m-info')) {
        if (!el.textContent.trim()) continue;
        const info = document.createElement('div');
        info.className = 'menu-info';
        info.textContent = el.textContent;
        list.appendChild(info);
        continue;
      }
      const item = document.createElement('button');
      item.type = 'button';
      item.className = 'menu-item';
      item.textContent = el.dataset.m + (el.hasAttribute('data-m-text') && el.textContent.trim() ? ' · ' + el.textContent.trim() : '');
      item.addEventListener('click', () => { dlg.close(); el.click(); });
      list.appendChild(item);
    }
    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'menu-item muted';
    close.textContent = '닫기';
    close.addEventListener('click', () => dlg.close());
    list.appendChild(close);
    dlg.showModal();
  });
})();
