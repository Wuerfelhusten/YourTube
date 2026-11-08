// 40-links.js - splits navigation between YourTube's two WebViews (see PlayerController.kt):
//  * feed:   tapping a video (/watch) opens it in the player WebView instead of navigating here
//  * player: tapping anything else on YouTube (channel, hashtag, playlist page ...) opens it in
//            the feed and minimizes the player - like the official app.
// m.youtube navigates in-page (pushState), which native code can't intercept, so links are
// caught here in the capture phase. Native code still corrects any navigation that slips through.

(function () {
  if (window.top !== window || !/(^|\.)youtube\.com$/.test(location.hostname)) return;
  const TT = window.YourTube;
  if (!TT) return;
  const role = TT_CONFIG.role;

  // Web Share API (missing in WebView): YouTube's "Share" then opens the native share sheet,
  // like in the official app, instead of its web-only panel.
  try {
    const share = (data) => {
      data = data || {};
      TT.post('share', { url: data.url || location.href, title: data.title || document.title || '', text: data.text || '' });
      return Promise.resolve();
    };
    Object.defineProperty(navigator, 'share', { value: share, configurable: true });
    Object.defineProperty(navigator, 'canShare', { value: () => true, configurable: true });
  } catch (e) { /* ignore */ }

  document.addEventListener('click', (e) => {
    const a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (!a) return;
    let url;
    try { url = new URL(a.getAttribute('href'), location.href); } catch (err) { return; }
    if (!/(^|\.)youtube\.com$/.test(url.hostname) || url.pathname.startsWith('/redirect')) return;
    const isWatch = url.pathname.startsWith('/watch');

    if (role === 'feed' && isWatch) {
      e.preventDefault();
      e.stopImmediatePropagation();
      TT.post('open-video', { url: url.href });
    } else if (role === 'player' && !isWatch) {
      e.preventDefault();
      e.stopImmediatePropagation();
      TT.post('open-in-feed', { url: url.href });
    }
  }, true);
})();
