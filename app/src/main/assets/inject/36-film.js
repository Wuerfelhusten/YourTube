// 36-film.js - the film page (Role.FILM): www.youtube.com with a desktop user agent, for free films
// that m.youtube refuses in mobile browsers. Nobody sees this page as such: the app lays it over
// the player area of the normal m.youtube watch page and draws its own controls (FilmControls.kt).
//  * only the video is shown, filling the viewport, black around it - no desktop UI, no controls
//  * reports the playback state and takes the same commands as 30-player.js (play/pause/seek)
//  * keeps playing in the background like the player page
// Default quality/speed, the tools sheet and the ad fallback come from 35-player-extras.js /
// 15-ad-fallback.js, which also run here.

(function () {
  if (window.top !== window || !/(^|\.)youtube\.com$/.test(location.hostname)) return;
  if (TT_CONFIG.role !== 'film') return;
  const TT = window.YourTube;
  if (!TT) return;

  const root = document.documentElement;
  root.classList.add('tt-film-page');

  // --- only the video ------------------------------------------------------------------------
  const css = document.createElement('style');
  css.id = 'tt-film-page';
  css.textContent = `
    html.tt-film-page, html.tt-film-page body { background: #000 !important; overflow: hidden !important; }
    html.tt-film-page #movie_player {
      position: fixed !important; inset: 0 !important; width: 100vw !important; height: 100vh !important;
      z-index: 2147483646 !important; background: #000 !important; }
    html.tt-film-page #movie_player .html5-video-container { width: 100% !important; height: 100% !important; }
    html.tt-film-page #movie_player video.html5-main-video {
      width: 100% !important; height: 100% !important; left: 0 !important; top: 0 !important; object-fit: contain !important; }
    html.tt-film-page.tt-zoom #movie_player video.html5-main-video { object-fit: cover !important; }
    /* YouTube's desktop UI on top of the video: the app draws its own controls. */
    html.tt-film-page .ytp-chrome-top, html.tt-film-page .ytp-chrome-bottom, html.tt-film-page .ytp-gradient-top,
    html.tt-film-page .ytp-gradient-bottom, html.tt-film-page .ytp-pause-overlay, html.tt-film-page .ytp-large-play-button,
    html.tt-film-page .ytp-ce-element, html.tt-film-page .ytp-endscreen-content, html.tt-film-page .ytp-cards-teaser,
    html.tt-film-page .ytp-cards-button, html.tt-film-page .ytp-paid-content-overlay, html.tt-film-page .ytp-watermark,
    html.tt-film-page .ytp-tooltip, html.tt-film-page .ytp-bezel, html.tt-film-page .ytp-bezel-text-wrapper,
    html.tt-film-page .ytp-iv-player-content, html.tt-film-page .ytp-contextmenu, html.tt-film-page .ytp-popup,
    html.tt-film-page .ytp-autonav-endscreen-countdown-overlay, html.tt-film-page .ytp-suggested-action,
    html.tt-film-page .ytp-overlays-container, html.tt-film-page .branding-img { display: none !important; }
    html.tt-film-page #masthead-container, html.tt-film-page ytd-masthead, html.tt-film-page tp-yt-app-drawer,
    html.tt-film-page #secondary, html.tt-film-page #below, html.tt-film-page ytd-mini-guide-renderer { visibility: hidden !important; }
  `;
  (document.head || root).appendChild(css);
  // The player computes the video size from its box; tell it the box changed.
  const relayout = () => window.dispatchEvent(new Event('resize'));
  setTimeout(relayout, 500);
  setTimeout(relayout, 2000);

  // --- background playback (as on the player page) ---------------------------------------------
  try {
    Object.defineProperty(document, 'hidden', { get: () => false, configurable: true });
    Object.defineProperty(document, 'visibilityState', { get: () => 'visible', configurable: true });
    document.hasFocus = () => true;
  } catch (e) { /* ignore */ }
  for (const type of ['visibilitychange', 'webkitvisibilitychange', 'freeze', 'blur']) {
    window.addEventListener(type, (e) => { e.stopImmediatePropagation(); }, true);
    document.addEventListener(type, (e) => { e.stopImmediatePropagation(); }, true);
  }

  const player = () => document.getElementById('movie_player');
  const video = () => document.querySelector('#movie_player video') || document.querySelector('video');

  // Desktop autoplay may start muted (autoplay policy); the user opened the film on purpose.
  function unmute() {
    const p = player();
    const v = video();
    if (!v || (p && p.classList.contains('ad-showing'))) return;
    try { if (p && p.isMuted && p.isMuted()) { p.unMute(); p.setVolume(100); } } catch (e) { /* not ready */ }
    if (v.muted) v.muted = false;
  }

  // --- state for the app -----------------------------------------------------------------------
  let lastKey = '';
  let lastSent = 0;
  function report(force) {
    const v = video();
    const p = player();
    let qualities = [];
    try { qualities = ((p && p.getAvailableQualityLevels && p.getAvailableQualityLevels()) || []).filter((q) => q !== 'auto'); } catch (e) { /* ignore */ }
    const ad = !!p && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting'));
    const state = {
      videoId: new URLSearchParams(location.search).get('v') || '',
      title: (document.title || '').replace(/^\(\d+\)\s*/, '').replace(/\s+-\s+YouTube$/, ''),
      channel: '',
      playing: !!v && !v.paused && !v.ended,
      // During an ad the film's own timeline is what the user cares about.
      position: ad ? 0 : (v ? v.currentTime || 0 : 0),
      duration: ad ? 0 : (v && isFinite(v.duration) ? v.duration : 0),
      qualities,
    };
    const key = state.videoId + state.playing + state.qualities.join() + ad;
    const now = Date.now();
    if (!force && key === lastKey && now - lastSent < 1000) return;
    lastKey = key;
    lastSent = now;
    TT.post('video-state', state);
  }
  for (const type of ['play', 'playing', 'pause', 'ended', 'loadedmetadata', 'timeupdate', 'seeked']) {
    document.addEventListener(type, (e) => {
      if (!(e.target instanceof HTMLVideoElement)) return;
      if (type === 'play' || type === 'loadedmetadata') unmute();
      report(type !== 'timeupdate');
    }, true);
  }
  setInterval(() => { unmute(); report(false); }, 1000);

  // --- commands from the app (same as the player page) ------------------------------------------
  TT.on('player-control', (cmd) => {
    const v = video();
    const p = player();
    if (!v) return;
    if (cmd.action === 'seek') {
      try { p && p.seekTo ? p.seekTo(cmd.position, true) : (v.currentTime = cmd.position); } catch (e) { v.currentTime = cmd.position; }
      setTimeout(() => report(true), 200);
      return;
    }
    const play = cmd.action === 'play' || (cmd.action === 'toggle' && v.paused);
    try {
      if (play) { p && p.playVideo ? p.playVideo() : v.play(); } else { p && p.pauseVideo ? p.pauseVideo() : v.pause(); }
    } catch (e) {
      play ? v.play() : v.pause();
    }
    setTimeout(() => report(true), 100);
  });
  // Pinch zoom in fullscreen (the player page has its own; here the film is the video).
  TT.on('player-tool', (cmd) => { if (cmd.action === 'zoom') root.classList.toggle('tt-zoom', cmd.value === 'on'); });
  // Native fullscreen / mini: the page always fills its WebView, only re-layout.
  TT.on('player-fullscreen', () => setTimeout(relayout, 300));
  window.addEventListener('resize', () => setTimeout(() => report(true), 300));
})();
