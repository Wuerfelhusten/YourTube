// 05-data-filter.js - TrueTube's data layer: cleans YouTube's own data BEFORE m.youtube renders it.
//
// Instead of hiding finished UI (what APK patchers have to do), feed items are removed from
// ytInitialData and from /youtubei/v1/* responses, so there are no gaps, no flicker and no
// dependency on CSS class names. Titles can be rewritten here too (DeArrow).
//
// Rules come from window.__TT_RULES__, a separate DocumentStart script that the app re-registers
// whenever the settings change (DataRules.kt) - so they apply to the very first page load.

(function () {
  if (!/(^|\.)youtube\.com$/.test(location.hostname)) return;
  const TT = window.TrueTube;

  const rules = () => window.__TT_RULES__ || {};
  const stats = { removed: 0, titles: 0 };
  /** DeArrow titles by video id, filled by the app (dearrow-titles command). */
  const titles = new Map();

  // --- helpers -----------------------------------------------------------------------------
  function durationSeconds(lockup) {
    const overlays = lockup?.contentImage?.thumbnailViewModel?.overlays || [];
    for (const o of overlays) {
      for (const b of o?.thumbnailBottomOverlayViewModel?.badges || []) {
        const t = b?.thumbnailBadgeViewModel?.text;
        if (t && /^\d+(:\d{2}){1,2}$/.test(t)) return t.split(':').reduce((acc, x) => acc * 60 + Number(x), 0);
      }
    }
    return -1; // live, premiere or unknown
  }

  function watchedPercent(lockup) {
    const overlays = lockup?.contentImage?.thumbnailViewModel?.overlays || [];
    for (const o of overlays) {
      const p = o?.thumbnailOverlayProgressBarViewModel?.startPercent;
      if (typeof p === 'number') return p;
      for (const b of o?.thumbnailBottomOverlayViewModel?.progressBar ? [o.thumbnailBottomOverlayViewModel.progressBar] : []) {
        const q = b?.thumbnailOverlayProgressBarViewModel?.startPercent;
        if (typeof q === 'number') return q;
      }
    }
    return 0;
  }

  function textOf(lockup) {
    const meta = lockup?.metadata?.lockupMetadataViewModel;
    const parts = [meta?.title?.content || ''];
    for (const row of meta?.metadata?.contentMetadataViewModel?.metadataRows || []) {
      for (const part of row?.metadataParts || []) parts.push(part?.text?.content || '');
    }
    return parts.join(' ').toLowerCase();
  }

  function keywordHit(text, keywords) {
    return keywords.some((k) => k && text.includes(k));
  }

  /** Should this list entry be dropped? Entries are objects like { richItemRenderer: {...} }. */
  function shouldDrop(entry, r) {
    if (!entry || typeof entry !== 'object') return false;
    const content = entry.richItemRenderer?.content || entry;

    // Shorts shelves / single Shorts in feeds
    if (r.hideShorts) {
      if (entry.richSectionRenderer && JSON.stringify(entry.richSectionRenderer).includes('shortsLockupViewModel')) return true;
      if (entry.reelShelfRenderer || content.shortsLockupViewModel || content.reelItemRenderer) return true;
      if (content.gridShelfViewModel && JSON.stringify(content.gridShelfViewModel).includes('shortsLockupViewModel')) return true;
    }
    // Community posts
    if (r.hidePosts && (entry.richSectionRenderer && /backstagePost|postRenderer|sharedPostRenderer/.test(JSON.stringify(entry.richSectionRenderer))
      || content.backstagePostThreadRenderer || content.postRenderer || content.sharedPostRenderer)) return true;

    const lockup = content.lockupViewModel;
    if (!lockup) return false;
    // Mixes ("Mix - ...", auto-generated radio playlists start with RD)
    if (r.hideMixes && lockup.contentType === 'LOCKUP_CONTENT_TYPE_PLAYLIST' && /^RD/.test(lockup.contentId || '')) return true;
    if (lockup.contentType === 'LOCKUP_CONTENT_TYPE_VIDEO') {
      if (r.minDuration > 0) {
        const d = durationSeconds(lockup);
        if (d >= 0 && d < r.minDuration) return true;
      }
      if (r.hideWatched && watchedPercent(lockup) >= 90) return true;
    }
    if (r.keywords && r.keywords.length && keywordHit(textOf(lockup), r.keywords)) return true;
    return false;
  }

  function rewriteTitle(lockup) {
    const t = titles.get(lockup.contentId);
    const title = lockup?.metadata?.lockupMetadataViewModel?.title;
    if (t && title && title.content !== t) {
      title.content = t;
      stats.titles++;
    }
  }

  /** Walks the whole response; filters every array in place. */
  function clean(obj, depth) {
    if (!obj || typeof obj !== 'object' || depth > 60) return;
    const r = rules();
    if (Array.isArray(obj)) {
      for (let i = obj.length - 1; i >= 0; i--) {
        if (shouldDrop(obj[i], r)) {
          obj.splice(i, 1);
          stats.removed++;
        } else {
          clean(obj[i], depth + 1);
        }
      }
      return;
    }
    if (obj.lockupViewModel && titles.size) rewriteTitle(obj.lockupViewModel);
    for (const k in obj) {
      const v = obj[k];
      if (v && typeof v === 'object') clean(v, depth + 1);
    }
  }

  function active() {
    const r = rules();
    return r.hideShorts || r.hidePosts || r.hideMixes || r.hideWatched || r.minDuration > 0 || (r.keywords && r.keywords.length) || titles.size > 0;
  }

  function looksLikeYtData(o) {
    return o && typeof o === 'object' && !Array.isArray(o) &&
      ('contents' in o || 'onResponseReceivedActions' in o || 'onResponseReceivedEndpoints' in o || 'continuationContents' in o);
  }

  function process(o) {
    if (!active() || !looksLikeYtData(o)) return o;
    const before = stats.removed;
    try { clean(o, 0); } catch (e) { console.warn('[TrueTube] data filter failed', e); }
    if (stats.removed !== before) TT && TT.log('data filter removed ' + (stats.removed - before) + ' items');
    return o;
  }

  // --- hooks ---------------------------------------------------------------------------------
  // (1) Inline initial data is parsed via JSON.parse.
  const origParse = JSON.parse;
  JSON.parse = function (text, reviver) {
    const value = origParse.call(this, text, reviver);
    return process(value);
  };

  // (2) SPA navigation and infinite scroll load JSON via fetch.
  const isApi = (url) => /\/youtubei\/v1\/(browse|search|next|guide|reel\/reel_watch_sequence)/.test(url);
  const origJson = Response.prototype.json;
  Response.prototype.json = function () {
    const url = this.url || '';
    return origJson.call(this).then((v) => (isApi(url) ? process(v) : v));
  };
  const origText = Response.prototype.text;
  Response.prototype.text = function () {
    const url = this.url || '';
    if (!isApi(url)) return origText.call(this);
    return origText.call(this).then((t) => {
      if (!active()) return t;
      try { return JSON.stringify(process(origParse(t))); } catch (e) { return t; }
    });
  };

  // (3) In case ytInitialData is assigned as an object literal (no JSON.parse).
  let initial;
  try {
    Object.defineProperty(window, 'ytInitialData', {
      configurable: true,
      get: () => initial,
      set: (v) => { initial = process(v); },
    });
  } catch (e) { /* already defined */ }

  // --- DeArrow (titles from the app) ---------------------------------------------------------
  function applyTitlesToDom() {
    if (!titles.size) return;
    for (const a of document.querySelectorAll('a[href*="/watch?v="]')) {
      const id = new URL(a.href, location.href).searchParams.get('v');
      const t = id && titles.get(id);
      if (!t) continue;
      // The title element is the text-heavy child of the item; m.youtube marks it as heading.
      const item = a.closest('ytm-video-with-context-renderer, ytm-rich-item-renderer, ytm-compact-video-renderer, .media-item-metadata, yt-lockup-view-model') || a;
      const el = item.querySelector('h3 span, .media-item-headline span, [role="heading"] span, .yt-core-attributed-string');
      if (el && el.textContent !== t) el.textContent = t;
    }
  }

  if (TT) {
    TT.on('dearrow-titles', (cmd) => {
      for (const [id, t] of Object.entries(cmd.titles || {})) titles.set(id, t);
      applyTitlesToDom();
    });
    TT.dataStats = stats;

    // Report the video ids on screen so the app can fetch DeArrow titles for them.
    let reported = new Set();
    let timer = 0;
    const reportIds = () => {
      timer = 0;
      if (!rules().dearrow) return;
      const ids = [];
      for (const a of document.querySelectorAll('a[href*="/watch?v="]')) {
        const id = new URL(a.href, location.href).searchParams.get('v');
        if (id && !reported.has(id)) { reported.add(id); ids.push(id); }
      }
      if (ids.length) TT.post('video-ids', { ids });
      applyTitlesToDom();
    };
    const start = () => new MutationObserver(() => { if (!timer) timer = setTimeout(reportIds, 400); })
      .observe(document.documentElement, { childList: true, subtree: true });
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, { once: true }); else start();
  }
})();
