// 15-ad-fallback.js - last line of defence if a video ad still plays (e.g. new YouTube
// experiments or server-side inserted ads): mute, jump to the end, click "Skip".
// The real blocking happens earlier through Brave's lists (scriptlets + network).

(function () {
  if (window.top !== window) return;
  const TT = window.TrueTube;

  const SKIP_BUTTONS = [
    '.ytp-ad-skip-button',
    '.ytp-ad-skip-button-modern',
    '.ytp-skip-ad-button',
    '.ytp-ad-skip-button-container button',
    '.ytp-ad-skip-button-slot button',
  ].join(',');

  let inAd = false;
  let wasMuted = false;

  function tick() {
    const player = document.querySelector('#movie_player, .html5-video-player');
    const showing = !!player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'));
    const video = player && player.querySelector('video');

    if (showing && video) {
      if (!inAd) {
        inAd = true;
        wasMuted = video.muted;
        TT && TT.post('ad-skipped', {});
        TT && TT.log('video ad detected - skipping');
      }
      video.muted = true;
      if (isFinite(video.duration) && video.duration > 0 && video.currentTime < video.duration - 0.3) {
        try { video.currentTime = video.duration - 0.1; } catch (e) { /* ignore */ }
      }
      const btn = document.querySelector(SKIP_BUTTONS);
      if (btn) btn.click();
    } else if (inAd) {
      inAd = false;
      if (video) video.muted = wasMuted;
    }
  }

  setInterval(tick, 250);
})();
