// 35-player-extras.js - player extras driven by YourTube Settings (PlayerExtras.kt):
//  * default quality / speed for every new video (YouTube's own menu still works afterwards)
//  * SponsorBlock: skips the received segments once, reports each skip to the app (toast)
//  * Return YouTube Dislike: shows the dislike count on the dislike pill
// Player WebView only.

(function () {
  if (window.top !== window || !/(^|\.)youtube\.com$/.test(location.hostname)) return;
  if (TT_CONFIG.role !== 'player') return;
  const TT = window.YourTube;
  if (!TT) return;

  const player = () => document.getElementById('movie_player');
  const video = () => document.querySelector('video');
  const currentId = () => new URLSearchParams(location.search).get('v') || '';
  const isAd = () => { const p = player(); return !!p && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting')); };

  // --- default quality / speed ---------------------------------------------------------------
  let prefs = { quality: 'auto', speed: 1 };
  let appliedFor = '';

  function applyPrefs(force) {
    const id = currentId();
    const p = player();
    const v = video();
    if (!id || !p || !v || isAd()) return;
    const key = id + '|' + prefs.quality + '|' + prefs.speed;
    if (!force && key === appliedFor) return;
    try {
      if (prefs.quality && prefs.quality !== 'auto' && p.setPlaybackQualityRange) {
        // Pick the requested quality, or the best available below it.
        const order = ['hd2160', 'hd1440', 'hd1080', 'hd720', 'large', 'medium', 'small', 'tiny'];
        const available = (p.getAvailableQualityLevels && p.getAvailableQualityLevels()) || [];
        const wanted = order.indexOf(prefs.quality);
        const q = order.slice(Math.max(0, wanted)).find((x) => available.includes(x)) || prefs.quality;
        p.setPlaybackQualityRange(q, q);
      }
    } catch (e) { /* player not ready yet */ }
    // The element accepts speeds beyond YouTube's 2x menu limit.
    if (prefs.speed && Math.abs(v.playbackRate - prefs.speed) > 0.01) v.playbackRate = prefs.speed;
    if (key.split('|')[0] !== guardFor) {
      // YouTube loads the captions module and the audio tracks a few seconds after playback starts:
      // keep correcting for a short window per video, then leave the user's own choice alone.
      guardFor = key.split('|')[0];
      guardUntil = Date.now() + 10000;
    }
    appliedFor = key;
    TT.log('prefs applied: ' + key);
  }

  let guardFor = '';
  let guardUntil = 0;
  setInterval(() => {
    if (Date.now() > guardUntil || guardFor !== currentId() || isAd()) return;
    const p = player();
    if (!p) return;
    if (prefs.originalAudio) forceOriginalAudio(p);
    if (prefs.noAutoCaptions) disableCaptions(p);
  }, 500);

  /** Finds the (obfuscated) language info object of an audio track: the one with isAutoDubbed. */
  function trackInfo(track) {
    if (!track || typeof track !== 'object') return null;
    if ('isAutoDubbed' in track) return track;
    for (const k in track) {
      const v = track[k];
      if (v && typeof v === 'object' && !Array.isArray(v) && 'isAutoDubbed' in v) return v;
    }
    return null;
  }

  /** AI dubbing: switch back to the original-language track (the one YouTube marks as original/default). */
  function forceOriginalAudio(p) {
    try {
      const cur = trackInfo(p.getAudioTrack && p.getAudioTrack());
      if (!cur || !cur.isAutoDubbed) return;
      const tracks = (p.getAvailableAudioTracks && p.getAvailableAudioTracks()) || [];
      const original = tracks.find((t) => { const i = trackInfo(t); return i && !i.isAutoDubbed && (i.isDefault || /original/i.test(i.name || '')); })
        || tracks.find((t) => { const i = trackInfo(t); return i && !i.isAutoDubbed; });
      if (original && p.setAudioTrack) {
        p.setAudioTrack(original);
        TT.log('audio: switched from auto-dubbed to original');
      }
    } catch (e) { /* API not ready */ }
  }

  /** Captions that YouTube switches on by itself (account/region defaults) - off per video. */
  function disableCaptions(p) {
    try {
      const track = p.getOption && p.getOption('captions', 'track');
      if (track && track.languageCode) {
        p.setOption('captions', 'track', {});
        TT.log('captions: auto-enabled track ' + track.languageCode + ' turned off');
      }
    } catch (e) { /* captions module not loaded */ }
  }

  TT.on('player-prefs', (cmd) => {
    prefs = { quality: cmd.quality || 'auto', speed: cmd.speed || 1, originalAudio: !!cmd.originalAudio, noAutoCaptions: !!cmd.noAutoCaptions };
    applyPrefs(true);
  });

  // --- tools from the native player sheet ----------------------------------------------------
  TT.on('player-tool', (cmd) => {
    const p = player();
    const v = video();
    if (!v) return;
    switch (cmd.action) {
      case 'loop': {
        const on = cmd.value === 'on';
        v.loop = on;
        try { p && p.setLoopVideo && p.setLoopVideo(on); } catch (e) { /* ignore */ }
        break;
      }
      case 'speed': v.playbackRate = parseFloat(cmd.value) || 1; break;
      case 'quality':
        try { if (cmd.value === 'auto') p.setPlaybackQualityRange('auto', 'auto'); else p.setPlaybackQualityRange(cmd.value, cmd.value); } catch (e) { /* ignore */ }
        break;
      case 'seek-to':
        try { p.seekTo(parseFloat(cmd.value), true); } catch (e) { v.currentTime = parseFloat(cmd.value); }
        break;
      case 'seek-by':
        try { p.seekTo(Math.max(0, v.currentTime + parseFloat(cmd.value)), true); } catch (e) { v.currentTime += parseFloat(cmd.value); }
        break;
      case 'captions-off': disableCaptions(p); break;
      case 'close-menu': // Back with a YouTube sheet open: close it like m.youtube's own back
        if (location.hash.includes('bottom-sheet')) history.back();
        else { const s = document.querySelector('ytw-scrim'); if (s) s.click(); }
        break;
    }
    TT.log('tool ' + cmd.action + ' ' + cmd.value);
  });
  for (const type of ['loadedmetadata', 'playing']) {
    document.addEventListener(type, (e) => { if (e.target instanceof HTMLVideoElement) setTimeout(() => applyPrefs(false), 300); }, true);
  }
  // A new video in the same page (autoplay, recommendation): YouTube resets speed/quality.
  window.addEventListener('tt:navigate', () => { appliedFor = ''; setTimeout(() => applyPrefs(false), 800); });

  // --- SponsorBlock ------------------------------------------------------------------------------
  let segments = { videoId: '', list: [], skipped: new Set() };

  TT.on('sponsor-segments', (cmd) => {
    segments = { videoId: cmd.videoId, list: cmd.segments || [], skipped: new Set(), marks: cmd.marks !== false };
    marksKey = '';
    drawMarks();
    TT.log('sponsorblock: ' + segments.list.length + ' segments for ' + cmd.videoId);
  });

  // Segment marks on YouTube's own seekbar while its controls are open (the native strip covers the
  // collapsed state). Same palette as the native strip (PlayerTools.kt).
  const SB_COLORS = { sponsor: '#00d400', selfpromo: '#ffff00', interaction: '#cc00ff', intro: '#00ffff', outro: '#0202ed',
    preview: '#008fd6', music_offtopic: '#ff9900', filler: '#7300ff' };
  let marksKey = '';
  function drawMarks() {
    const bar = document.querySelector('yt-chaptered-progress-bar-line');
    const v = video();
    const show = segments.marks && segments.videoId === currentId() && segments.list.length > 0;
    let layer = bar && bar.querySelector(':scope > .tt-sb-marks');
    if (!bar || !v || !show || !(v.duration > 0)) { if (layer) layer.remove(); marksKey = ''; return; }
    const key = segments.videoId + '|' + v.duration + '|' + segments.list.length;
    if (layer && key === marksKey) return;
    if (!layer) {
      layer = document.createElement('div');
      layer.className = 'tt-sb-marks';
      layer.style.cssText = 'position:absolute;inset:0;pointer-events:none;z-index:1';
      if (getComputedStyle(bar).position === 'static') bar.style.position = 'relative';
      bar.appendChild(layer);
    }
    layer.textContent = '';
    for (const s of segments.list) {
      const m = document.createElement('div');
      const l = Math.max(0, Math.min(1, s.start / v.duration)) * 100;
      const w = Math.max(0, Math.min(1, s.end / v.duration)) * 100 - l;
      m.style.cssText = 'position:absolute;top:0;bottom:0;left:' + l + '%;width:max(2px,' + w + '%);background:' + (SB_COLORS[s.category] || '#fff');
      layer.appendChild(m);
    }
    marksKey = key;
  }

  let controlsShown = null;
  let menuShown = null;
  // A YouTube menu / sheet over the player (gear, quality, speed, comments ...): m.youtube marks it
  // with #bottom-sheet and dims the page with a scrim. The app pauses its swipe gestures meanwhile.
  const menuOpen = () => location.hash.includes("bottom-sheet") ||
    [...document.querySelectorAll("ytw-scrim, .ytSpecBottomSheetLayoutHost")].some((e) => e.getBoundingClientRect().height > 0 && getComputedStyle(e).visibility !== "hidden");
  setInterval(() => {
    drawMarks(); // YouTube re-renders the bar (new video, chapters)
    const c = document.querySelector('.player-controls-content');
    const shown = !!c && !isAd() && getComputedStyle(c).visibility === 'visible' && c.getBoundingClientRect().height > 0;
    if (shown !== controlsShown) {
      controlsShown = shown;
      TT.post('player-controls', { visible: shown });
    }
    const menu = menuOpen();
    if (menu !== menuShown) {
      menuShown = menu;
      TT.post('player-menu', { open: menu });
    }
  }, 250);

  let prompted = -1; // index of the "button" segment the native skip button is shown for

  function seekTo(v, t) {
    const p = player();
    try { p && p.seekTo ? p.seekTo(t, true) : (v.currentTime = t); } catch (err) { v.currentTime = t; }
  }

  document.addEventListener('timeupdate', (e) => {
    const v = e.target;
    if (!(v instanceof HTMLVideoElement) || isAd()) return;
    if (segments.videoId !== currentId() || segments.list.length === 0) return;
    const t = v.currentTime;
    let inButton = -1;
    segments.list.forEach((s, i) => {
      const inside = t >= s.start && t < s.end - 0.3;
      if (!inside) return;
      if (s.action === 'auto') {
        // once: seeking back into a skipped segment plays it (manual replay).
        if (s.once && segments.skipped.has(i)) return;
        segments.skipped.add(i);
        seekTo(v, s.end);
        TT.post('sponsor-skipped', { category: s.category, seconds: s.end - s.start });
      } else if (s.action === 'button') {
        inButton = i;
      }
    });
    if (inButton !== prompted) {
      prompted = inButton;
      const s = segments.list[inButton];
      TT.post('sponsor-prompt', s ? { category: s.category, end: s.end } : {});
    }
  }, true);
  window.addEventListener('tt:navigate', () => { if (prompted !== -1) { prompted = -1; TT.post('sponsor-prompt', {}); } });

  // --- Return YouTube Dislike ------------------------------------------------------------------
  let dislikes = { videoId: '', count: -1 };
  const lang = document.documentElement.lang || navigator.language || 'de';
  const compact = new Intl.NumberFormat(lang, { notation: 'compact', maximumFractionDigits: 1 });

  function applyDislikes() {
    if (dislikes.videoId !== currentId() || dislikes.count < 0) return;
    const btn = document.querySelector('button.tt-dislike');
    if (!btn) return;
    let label = btn.querySelector('.tt-pill-label');
    if (!label) {
      label = document.createElement('span');
      label.className = 'tt-pill-label';
      btn.appendChild(label);
    }
    const text = compact.format(dislikes.count);
    if (label.textContent !== text) label.textContent = text;
  }

  TT.on('dislikes', (cmd) => {
    dislikes = { videoId: cmd.videoId, count: cmd.count };
    applyDislikes();
  });
  // The action bar is (re)rendered by YouTube at any time; keep the label in place.
  setInterval(applyDislikes, 1000);
})();
