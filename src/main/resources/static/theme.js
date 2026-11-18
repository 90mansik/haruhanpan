// 테마: localStorage 'theme' 값, 없으면 OS 설정. <head> 에서 바로 실행해 깜빡임을 막는다.
// 설치한 앱(PWA)의 상태 표시줄 색(theme-color)도 상단 바 색에 맞추고, 서비스 워커를 등록한다 (모든 화면 공용).
(function () {
  let saved = null;
  try { saved = localStorage.getItem('theme'); } catch (e) { /* 저장소 차단 시 OS 설정 */ }
  const dark = saved ? saved === 'dark' : window.matchMedia('(prefers-color-scheme: dark)').matches;
  const root = document.documentElement;
  root.dataset.theme = dark ? 'dark' : 'light';

  // 상단 바 색 = style.css 의 --panel (라이트 #fff, 다크 #1e2125). 화면에서 테마를 바꾸면 따라 바꾼다
  const syncBar = () => {
    const meta = document.querySelector('meta[name=theme-color]');
    if (meta) meta.content = root.dataset.theme === 'dark' ? '#1e2125' : '#ffffff';
  };
  syncBar();
  new MutationObserver(syncBar).observe(root, { attributes: true, attributeFilter: ['data-theme'] });

  if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => { navigator.serviceWorker.register('/sw.js').catch(() => { /* 지원 안 하면 일반 웹으로 */ }); });
  }
})();
