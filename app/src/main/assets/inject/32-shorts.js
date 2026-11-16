// 32-shorts.js - Shorts in the feed WebView.
//  * A Short the user paused stays paused: m.youtube's own Shorts code calls playVideo() when the
//    comments (or another sheet) open, ~0.4 s after the tap on the comment button. Only a tap on
//    the video itself may resume it - like the official app.

(function () {
  if (window.top !== window || !/(^|\.)youtube\.com$/.test(location.hostname)) return;
  const TT = window.YourTube;
  if (!TT || TT_CONFIG.role !== 'feed') return;

  const onShorts = () => location.pathname.startsWith('/shorts');
  // m.youtube's tap-to-pause layer over the Short (buttons like comments are outside it).
  const PLAYER_TAP = '[class*="ShortsGestureOverlay"][class*="GestureArea"], video, .html5-video-player';

  let lastTapAt = 0;
  let lastTapOnPlayer = false;
  let held = null; // { video, src } paused by the user

  document.addEventListener('pointerdown', (e) => {
    lastTapAt = Date.now();
    lastTapOnPlayer = e.target instanceof Element && !!e.target.closest(PLAYER_TAP);
  }, true);
  const tappedPlayer = () => lastTapOnPlayer && Date.now() - lastTapAt < 1000;

  document.addEventListener('pause', (e) => {
    const v = e.target;
    if (!(v instanceof HTMLVideoElement) || !onShorts() || v.ended) return;
    if (tappedPlayer()) held = { video: v, src: v.currentSrc };
  }, true);

  document.addEventListener('play', (e) => {
    const v = e.target;
    if (!(v instanceof HTMLVideoElement) || !held) return;
    if (!onShorts() || held.video !== v || held.src !== v.currentSrc || tappedPlayer()) { held = null; return; }
    v.pause();
    TT.log('shorts: kept paused (YouTube resumed it without a tap on the video)');
  }, true);

  // Another Short / another page: nothing to hold any more.
  window.addEventListener('tt:navigate', () => { held = null; });
})();
