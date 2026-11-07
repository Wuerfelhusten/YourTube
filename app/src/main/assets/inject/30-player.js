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

  // m.youtube persists "muted" (from its muted autoplay) in localStorage and restores it for the
  // next video. Reset the stored preference before the player reads it.
  try {
    const now = Date.now();
    localStorage.setItem('yt-player-volume', JSON.stringify({
      data: JSON.stringify({ volume: 100, muted: false }),
      expiration: now + 30 * 24 * 3600 * 1000,
      creation: now,
    }));
  } catch (e) { /* storage unavailable */ }

  /** Unmute through the player API (keeps YouTube's own state in sync) and the element. */
  function unmute() {
    if (!location.pathname.startsWith('/watch')) return;
    const player = document.getElementById('movie_player');
    const video = document.querySelector('video');
    if (!video || isAd(player)) return; // the ad fallback mutes ads on purpose
    let apiMuted = false;
    try { apiMuted = !!(player && player.isMuted && player.isMuted()); } catch (e) { /* not ready */ }
    if (!video.muted && !apiMuted) return;
    try {
      if (player && player.unMute) player.unMute();
      if (player && player.setVolume) player.setVolume(100);
    } catch (e) { /* player API not ready */ }
    video.muted = false;
    if (video.volume < 0.05) video.volume = 1;
    TT && TT.log('unmuted (api muted: ' + apiMuted + ')');
  }

  // Media events don't bubble, but they can be caught in the capture phase on document.
  for (const type of ['play', 'playing', 'loadeddata', 'volumechange']) {
    document.addEventListener(type, (e) => { if (e.target instanceof HTMLVideoElement) unmute(); }, true);
  }
  // YouTube may re-mute later (new video in the same page, after ads, player re-init).
  setInterval(unmute, 1000);

  if (TT_CONFIG.role !== 'player') return;

  // --- now-playing state for the native mini player ---------------------------------------
  let lastSent = 0;
  let lastKey = '';
  function report(force) {
    const v = document.querySelector('video');
    const params = new URLSearchParams(location.search);
    const channelEl = document.querySelector('.tt-channel-name');
    const state = {
      videoId: params.get('v') || '',
      title: (document.title || '').replace(/\s+-\s+YouTube$/, ''),
      channel: channelEl ? (channelEl.firstChild && channelEl.firstChild.textContent || '').trim() : '',
      playing: !!v && !v.paused && !v.ended,
      position: v ? v.currentTime || 0 : 0,
      duration: v && isFinite(v.duration) ? v.duration : 0,
    };
    const key = state.videoId + state.title + state.channel + state.playing;
    const now = Date.now();
    if (!force && key === lastKey && now - lastSent < 1000) return;
    lastKey = key;
    lastSent = now;
    TT.post('video-state', state);
  }
  for (const type of ['play', 'pause', 'ended', 'loadedmetadata', 'timeupdate']) {
    document.addEventListener(type, (e) => { if (e.target instanceof HTMLVideoElement) report(type !== 'timeupdate'); }, true);
  }
  window.addEventListener('tt:navigate', () => setTimeout(() => report(true), 300));
  // Title/channel arrive after the URL; report once more when the page settled.
  setInterval(() => report(false), 2000);

  // --- commands ----------------------------------------------------------------------------
  TT.on('player-control', (cmd) => {
    const v = document.querySelector('video');
    const p = document.getElementById('movie_player');
    if (!v) return;
    const play = cmd.action === 'play' || (cmd.action === 'toggle' && v.paused);
    try {
      if (play) { p && p.playVideo ? p.playVideo() : v.play(); } else { p && p.pauseVideo ? p.pauseVideo() : v.pause(); }
    } catch (e) {
      play ? v.play() : v.pause();
    }
    setTimeout(() => report(true), 100);
  });

  // Mini player: the native side shrinks the whole WebView; the page then shows nothing but the
  // video, filling the viewport, without controls (taps are handled natively).
  const mini = document.createElement('style');
  mini.id = 'tt-mini-style';
  mini.textContent = `
    /* Layout stays untouched (any viewport/layout change makes m.youtube pause); the native side
       shows only the top 16:9 strip = the sticky player. Just hide the player controls. */
    html.tt-mini .ytp-chrome-top, html.tt-mini .ytp-chrome-bottom, html.tt-mini .ytp-gradient-top,
    html.tt-mini .ytp-gradient-bottom, html.tt-mini #player-control-container, html.tt-mini .player-controls-content,
    html.tt-mini ytm-custom-control, html.tt-mini .ytmCustomControlHost { display: none !important; }
  `;
  (document.head || document.documentElement).appendChild(mini);
  TT.on('player-mini', (cmd) => {
    const root = document.documentElement;
    if (root.classList.contains('tt-mini') === !!cmd.on) return;
    const v = document.querySelector('video');
    const wasPlaying = !!v && !v.paused && !v.ended;
    root.classList.toggle('tt-mini', !!cmd.on);
    // m.youtube pauses when the player suddenly becomes tiny / changes layout; the official app
    // keeps playing in the mini player, so resume if it was playing.
    if (wasPlaying) {
      for (const delay of [50, 300, 800]) {
        setTimeout(() => {
          const vv = document.querySelector('video');
          if (vv && vv.paused) {
            const p = document.getElementById('movie_player');
            try { p && p.playVideo ? p.playVideo() : vv.play(); } catch (e) { vv.play(); }
          }
        }, delay);
      }
    }
  });
})();
