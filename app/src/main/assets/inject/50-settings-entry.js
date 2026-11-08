// 50-settings-entry.js - adds a "YourTube Settings" entry at the very top of YouTube's settings
// page (m.youtube.com/select_site). It is a clone of YouTube's own first entry, so it looks native
// in light and dark mode; tapping it opens YourTube's native settings screen.

(function () {
  if (window.top !== window || !/(^|\.)youtube\.com$/.test(location.hostname)) return;
  const TT = window.YourTube;
  if (!TT) return;

  const ID = 'tt-settings-entry';
  const TITLE = 'YourTube Settings';
  const SUBTITLE = { de: 'Werbeblocker, Wiedergabe, Oberfläche', en: 'Ad blocker, playback, appearance' };
  // YourTube glyph (play shape), 24x24, drawn in the entry's icon color.
  const ICON = 'M5 3.6c0-1.2 1.3-1.9 2.3-1.3l13.4 8.4c1 .6 1 2 0 2.6L7.3 21.7c-1 .6-2.3-.1-2.3-1.3z';

  function leafTexts(root) {
    const out = [];
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: (n) => (n.textContent.trim() ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT),
    });
    while (walker.nextNode()) out.push(walker.currentNode);
    return out;
  }

  function insert() {
    if (!location.pathname.startsWith('/select_site')) return;
    const host = document.querySelector('ytm-settings');
    if (!host || document.getElementById(ID)) return;
    const template = host.querySelector(':scope > a.setting-generic-category, :scope > button.setting-generic-category');
    if (!template) return;

    const entry = template.cloneNode(true);
    entry.id = ID;
    entry.removeAttribute('href');
    entry.setAttribute('role', 'button');
    entry.setAttribute('aria-label', TITLE);

    const texts = leafTexts(entry);
    if (texts[0]) texts[0].textContent = TITLE;
    const lang = (document.documentElement.lang || 'en').slice(0, 2);
    if (texts[1]) texts[1].textContent = SUBTITLE[lang] || SUBTITLE.en;
    for (const t of texts.slice(2)) t.textContent = '';

    // Replace whatever icon markup the template uses with our own SVG. Built with DOM APIs:
    // m.youtube enforces Trusted Types, so innerHTML assignments throw.
    // YouTube's own icons sit centred (24px) in a 48x48 box (c3-icon); rebuild that box ourselves,
    // because the cloned c3-icon custom element re-renders its own content.
    const iconBox = entry.querySelector('.setting-generic-category-icon');
    if (iconBox) {
      const NS = 'http://www.w3.org/2000/svg';
      const svg = document.createElementNS(NS, 'svg');
      svg.setAttribute('viewBox', '0 0 24 24');
      svg.setAttribute('width', '24');
      svg.setAttribute('height', '24');
      svg.setAttribute('fill', 'currentColor');
      svg.setAttribute('aria-hidden', 'true');
      svg.style.cssText = 'display:block;width:24px;height:24px';
      const path = document.createElementNS(NS, 'path');
      path.setAttribute('d', ICON);
      svg.appendChild(path);
      const box = document.createElement('div');
      box.style.cssText = 'width:48px;height:48px;display:flex;align-items:center;justify-content:center';
      box.appendChild(svg);
      iconBox.replaceChildren(box);
    }

    entry.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopImmediatePropagation();
      TT.post('open-settings', {});
    }, true);

    host.insertBefore(entry, host.firstChild);
    TT.log('settings entry added');
  }

  let timer = 0;
  new MutationObserver(() => {
    if (!timer) timer = setTimeout(() => { timer = 0; insert(); }, 100);
  }).observe(document.documentElement, { childList: true, subtree: true });
  window.addEventListener('tt:navigate', insert);
})();
