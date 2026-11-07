// 20-app-look.js - turns m.youtube.com into the content area of a native-looking app.
//  * m.youtube's own top bar and bottom tab bar are hidden: TrueTube draws native ones.
//  * <html tt-page="..."> tells CSS which kind of page is shown.
//  * The watch-page action bar gets labelled pills like the official app (like count, share, save).

(function () {
  if (window.top !== window || !/(^|\.)youtube\.com$/.test(location.hostname)) return;
  const TT = window.TrueTube;
  const root = document.documentElement;

  function pageKind(path) {
    if (path.startsWith('/watch')) return 'watch';
    if (path.startsWith('/shorts')) return 'shorts';
    if (path.startsWith('/results')) return 'results';
    if (path === '/' || path === '') return 'home';
    if (path.startsWith('/feed/subscriptions')) return 'subscriptions';
    if (path.startsWith('/feed/library') || path.startsWith('/feed/you')) return 'you';
    return 'other';
  }
  const updatePage = () => root.setAttribute('tt-page', pageKind(location.pathname));
  updatePage();
  window.addEventListener('tt:navigate', updatePage);

  const CSS = `
    :root { --tt-text: #0f0f0f; --tt-text-2: #606060; --tt-pill-bg: rgba(0,0,0,0.05); --tt-pill-sep: rgba(0,0,0,0.15); }
    @media (prefers-color-scheme: dark) {
      :root { --tt-text: #f1f1f1; --tt-text-2: #aaaaaa; --tt-pill-bg: rgba(255,255,255,0.1); --tt-pill-sep: rgba(255,255,255,0.2); }
    }
    /* --- native shell replaces the web chrome --- */
    #header-bar, ytm-mobile-topbar-renderer { display: none !important; }
    ytm-pivot-bar-renderer { display: none !important; }
    ytm-app { padding-top: 0 !important; padding-bottom: 0 !important; }
    ytm-feed-filter-chip-bar-renderer, #filter-chip-bar { top: 0 !important; }
    .player-container.sticky-player, #player-container-id { top: 0 !important; }
    ytm-watch.ambient-topbar { margin-top: 0 !important; }

    /* "open in app" nags make no sense inside an app */
    ytm-app-promo-renderer, .mobile-topbar-header-open-app, ytm-companion-slot { display: none !important; }

    /* --- watch page: action bar as labelled pills (official app style) --- */
    /* Row 1: avatar · name · Subscribe (right) - Row 2: action pills in ONE horizontally
       scrolling row, like the official app. YouTube puts all of them into one container, and we
       must not move its nodes. So the container is the scroller, while the channel row items are
       positioned absolutely against the (non-scrolling) outer renderer: an absolutely positioned
       element whose containing block lies outside a scroll container does not scroll with it. */
    ytm-slim-video-action-bar-renderer { position: relative !important; display: block !important; padding-top: 60px !important; }
    .slim-video-action-bar-actions {
      position: static !important;
      display: flex !important; flex-wrap: nowrap !important; align-items: center !important;
      gap: 8px !important; padding: 0 12px 4px !important; margin: 0 !important;
      justify-content: flex-start !important; overflow-x: auto !important; overflow-y: hidden !important;
      scrollbar-width: none; -webkit-overflow-scrolling: touch;
    }
    .slim-video-action-bar-actions::-webkit-scrollbar { display: none; }
    .slim-video-action-bar-actions > .slim-video-owner-icon {
      position: absolute !important; left: 12px !important; top: 6px !important; margin: 0 !important; }
    .slim-video-action-bar-actions > .slim-subscribe-button {
      position: absolute !important; right: 12px !important; top: 14px !important; margin: 0 !important; flex: none !important; }
    .slim-video-action-bar-actions > like-button-view-model,
    .slim-video-action-bar-actions > dislike-button-view-model,
    .slim-video-action-bar-actions > button-view-model { flex: 0 0 auto !important; margin-left: 0 !important; }
    .tt-channel-subs { font: 400 12px Roboto, Arial, sans-serif; color: var(--tt-text-2); margin-left: 6px; }
    .tt-channel-name { position: absolute; left: 68px; top: 20px; right: 150px;
      font: 500 16px Roboto, Arial, sans-serif; color: var(--tt-text);
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .tt-pill {
      flex: 0 0 auto !important; display: inline-flex !important; align-items: center !important; gap: 6px !important;
      height: 36px !important; padding: 0 14px !important; border-radius: 18px !important;
      background: var(--tt-pill-bg) !important;
      font: 500 14px/36px Roboto, Arial, sans-serif !important; color: var(--tt-text) !important;
      width: auto !important; min-width: 0 !important;
    }
    .tt-pill .tt-pill-label { white-space: nowrap; }
    /* "Report" lives in the overflow menu in the official app; m.youtube keeps it in "mehr". */
    button-view-model:has(.tt-icon-only) { display: none !important; }
    /* Like | Dislike as one segmented pill - only when YouTube actually renders a dislike button. */
    like-button-view-model:has(+ dislike-button-view-model) .tt-pill.tt-like {
      border-radius: 18px 0 0 18px !important; padding-right: 10px !important;
      border-right: 1px solid var(--tt-pill-sep) !important; }
    like-button-view-model:has(+ dislike-button-view-model) { margin-right: -8px !important; }
    .tt-pill.tt-dislike { border-radius: 0 18px 18px 0 !important; padding-left: 10px !important; }
  `;

  function injectStyle() {
    if (document.getElementById('tt-app-look')) return;
    const el = document.createElement('style');
    el.id = 'tt-app-look';
    el.textContent = CSS;
    (document.head || document.documentElement).appendChild(el);
  }
  injectStyle();

  // --- action bar labels ---------------------------------------------------------------
  const lang = root.lang || navigator.language || 'de';
  const compact = new Intl.NumberFormat(lang, { notation: 'compact', maximumFractionDigits: 1 });

  function labelFor(button) {
    const aria = button.getAttribute('aria-label') || '';
    const lower = aria.toLowerCase();
    if (/\blike|mag ich|positive/.test(lower) && !/nicht|dislike/.test(lower)) {
      const m = aria.replace(/[.  \s]/g, '').match(/(\d{1,})/);
      return { cls: 'tt-like', text: m ? compact.format(Number(m[1])) : '' };
    }
    if (/nicht|dislike/.test(lower)) return { cls: 'tt-dislike', text: '' };
    // Short labels like the official app; report stays icon-only (the app hides it in "...").
    if (/playlist|speichern|save/.test(lower)) return { cls: '', text: SAVE[lang.slice(0, 2)] || SAVE.en };
    if (/melden|report/.test(lower)) return { cls: 'tt-icon-only', text: '' };
    // Everything else: use the aria-label itself (already localized by YouTube), shortened.
    return { cls: '', text: aria.replace(/\s*\(.*\)$/, '').split(/[.,]/)[0] };
  }
  const SAVE = { de: 'Speichern', en: 'Save', fr: 'Enregistrer', es: 'Guardar', it: 'Salva', nl: 'Opslaan' };

  /** Official app shows the channel name next to the avatar; m.youtube only has it in the meta line. */
  function addChannelName(bar) {
    if (bar.querySelector('.tt-channel-name')) return;
    const icon = bar.querySelector('.slim-video-owner-icon');
    const handle = document.querySelector('ytm-slim-video-metadata-section-renderer a[href^="/@"], .slim-owner-channel-name, ytm-slim-owner-renderer a');
    const name = (icon && icon.getAttribute('aria-label')) || (handle && handle.textContent.trim());
    if (!icon || !name) return;
    // aria-label looks like "Luis Fonsi, 32,6 Mio. Abonnenten. Zum Kanal." -> name + subscriber count
    const cleaned = name.replace(/^@/, '').replace(/\.?\s*(Zum Kanal|Go to channel)\.?\s*$/i, '').replace(/\.\s*$/, '');
    const split = cleaned.match(/^(.+?),\s*(.+)$/);
    const el = document.createElement('a');
    el.className = 'tt-channel-name';
    el.href = icon.getAttribute('href') || '#';
    el.textContent = split ? split[1] : cleaned;
    if (split) {
      const subs = document.createElement('span');
      subs.className = 'tt-channel-subs';
      subs.textContent = split[2].replace(/\s*(Abonnenten|subscribers)$/i, '');
      el.appendChild(subs);
    }
    icon.after(el);
  }

  function decorateActions() {
    const bar = document.querySelector('.slim-video-action-bar-actions');
    if (!bar) return;
    addChannelName(bar);
    for (const button of bar.querySelectorAll('button')) {
      if (button.dataset.ttPill === '1') continue;
      if (/abonnieren|subscribe/i.test(button.getAttribute('aria-label') || '')) continue;
      const { cls, text } = labelFor(button);
      button.classList.add('tt-pill');
      if (cls) button.classList.add(cls);
      if (text && !button.querySelector('.tt-pill-label')) {
        const span = document.createElement('span');
        span.className = 'tt-pill-label';
        span.textContent = text;
        button.appendChild(span);
      }
      button.dataset.ttPill = '1';
    }
  }

  let timer = 0;
  new MutationObserver(() => {
    injectStyle();
    if (root.getAttribute('tt-page') === 'watch' && !timer) {
      timer = setTimeout(() => { timer = 0; decorateActions(); }, 150);
    }
  }).observe(root, { childList: true, subtree: true });

  // --- commands from the native shell ---------------------------------------------------
  if (TT) {
    // Tab bar: reuse m.youtube's (hidden) pivot items for fast in-page navigation.
    TT.on('nav-tab', (cmd) => {
      const items = document.querySelectorAll('ytm-pivot-bar-item-renderer');
      const target = items[cmd.index];
      const clickable = target && (target.querySelector('a, div[role=tab], .pivot-bar-item-tab') || target);
      if (clickable) clickable.click();
      else location.href = cmd.url;
    });
    TT.on('scroll-top', () => window.scrollTo({ top: 0, behavior: 'smooth' }));

    // User CSS from TrueTube Settings (hide comments, custom CSS). textContent is fine under
    // YouTube's Trusted Types policy (only HTML/script sinks are restricted).
    let userStyle = null;
    TT.on('user-css', (cmd) => {
      if (!userStyle || !userStyle.isConnected) {
        userStyle = document.createElement('style');
        userStyle.id = 'tt-user-css';
        (document.head || document.documentElement).appendChild(userStyle);
      }
      userStyle.textContent = cmd.css || '';
    });
  }
})();
