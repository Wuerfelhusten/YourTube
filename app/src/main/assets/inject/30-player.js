// 30-player.js - player behaviour like the official app.
//  * Videos start WITH sound: m.youtube autoplays muted (web autoplay policy) and shows a
//    "tap to unmute" button. The app has neither, and in TrueTube the user opened the video
//    on purpose (the WebView allows unmuted playback: mediaPlaybackRequiresUserGesture=false).
// More player features (quality, speed, fullscreen) follow in M5/M10.

(function () {
  if (window.top !== window || !/(^|\.)youtube\.com$/.test(location.hostname)) return;
  const TT = window.TrueTube;

  const style = document.createElement('style');
  style.id = 'tt-player';
  style.textContent = `
    .ytp-unmute, .ytp-unmute-animated, .ytmUnmuteButton { display: none !important; }
  `;
  (document.head || document.documentElement).appendChild(style);

  const isAd = (player) => !!player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'));

  function unmute(video) {
    const player = document.getElementById('movie_player');
    if (!video.muted || isAd(player)) return; // the ad fallback mutes ads on purpose
    try {
      if (player && typeof player.unMute === 'function') player.unMute();
    } catch (e) { /* player API not ready */ }
    video.muted = false;
    if (video.volume === 0) video.volume = 1;
    TT && TT.log('unmuted autoplay');
  }

  // Media events don't bubble, but they can be caught in the capture phase on document.
  for (const type of ['play', 'playing', 'loadeddata', 'volumechange']) {
    document.addEventListener(type, (e) => {
      const v = e.target;
      if (v instanceof HTMLVideoElement && location.pathname.startsWith('/watch')) unmute(v);
    }, true);
  }
})();
