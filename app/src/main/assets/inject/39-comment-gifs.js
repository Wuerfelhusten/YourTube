// 39-comment-gifs.js - GIFs in comments.
// YouTube serves comment GIFs only to its app and the desktop site; m.youtube gets an image id and
// "GIFs aren't supported on this device". The real image URL is signed, so it can't be built from
// the id. Whenever m.youtube loads a comment page that contains such a GIF, the same request is
// sent once more as the desktop client; its answer has the signed URLs (i.ytimg.com/c_exp/<comment
// id>/...). The placeholder is then replaced by the GIF. If anything fails the placeholder stays.

(function () {
  if (window.top !== window || !/(^|\.)youtube\.com$/.test(location.hostname)) return;
  const TT = window.YourTube;
  if (!TT || (TT_CONFIG.role !== 'player' && TT_CONFIG.role !== 'feed')) return;

  const ATTACHMENT = 'commentImageAttachmentViewModel';
  const MARK = 'x-tt-gif-lookup';
  /** commentId -> image URL */
  const gifs = new Map();

  // A desktop client version from this year is enough; YouTube accepts older ones for a long time.
  const WEB_VERSION = '2.20261101.00.00';

  /** m.youtube sends its request bodies gzip-compressed. */
  function decodeBody(buf) {
    const b = new Uint8Array(buf);
    if (b.length > 2 && b[0] === 0x1f && b[1] === 0x8b && window.DecompressionStream) {
      return new Response(new Blob([buf]).stream().pipeThrough(new DecompressionStream('gzip'))).text();
    }
    return Promise.resolve(new TextDecoder().decode(b));
  }

  const origFetch = window.fetch;
  window.fetch = function (input, init) {
    // m.youtube passes a Request object; read it before fetch consumes it.
    let bodyP = null;
    let headers = (init && init.headers) || null;
    const url = typeof input === 'string' ? input : (input && input.url) || '';
    try {
      if (/\/youtubei\/v1\/next/.test(url)) {
        if (init && typeof init.body === 'string') bodyP = Promise.resolve(init.body);
        else if (input instanceof Request) { bodyP = input.clone().arrayBuffer().then(decodeBody); headers = headers || input.headers; }
      }
    } catch (e) { bodyP = null; }
    const p = origFetch.apply(this, arguments);
    const own = headers && (headers[MARK] || (headers.get && headers.get(MARK)));
    if (bodyP && !own) {
      Promise.all([bodyP, p.then((res) => res.clone().text())]).then(([body, text]) => {
        if (text.includes(ATTACHMENT)) lookup(url, { headers }, body);
      }).catch(() => {});
    }
    return p;
  };

  /**
   * Asks for the same page as the desktop client. The first page of a watch page has the comments
   * inline on m.youtube, but the desktop client only gets a continuation for them: follow it once.
   */
  function lookup(url, init, body, depth) {
    let req;
    try { req = JSON.parse(body); } catch (e) { return; }
    const client = (req.context && req.context.client) || {};
    req.context = Object.assign({}, req.context, {
      client: Object.assign({}, client, { clientName: 'WEB', clientVersion: WEB_VERSION, platform: 'DESKTOP', clientFormFactor: 'UNKNOWN_FORM_FACTOR' }),
    });
    const headers = {};
    const src = init.headers || {};
    if (src.forEach) src.forEach((v, k) => { headers[k] = v; }); else Object.assign(headers, src);
    for (const k of Object.keys(headers)) {
      // Own client headers; the body goes out as plain JSON (no gzip).
      if (/^(x-youtube-client-(name|version)|content-encoding|content-length)$/i.test(k)) delete headers[k];
    }
    headers['X-YouTube-Client-Name'] = '1';
    headers['X-YouTube-Client-Version'] = WEB_VERSION;
    headers[MARK] = '1';
    origFetch(url, { method: 'POST', credentials: 'include', headers, body: JSON.stringify(req) })
      .then((r) => r.text())
      .then((text) => {
        // URLs are JSON-escaped; the comment id is part of the path.
        const re = /https:\/\/i\.ytimg\.com\/c_exp\/([A-Za-z0-9_-]+)\/[^"\\]+(?:\\u0026[^"\\]+)*/g;
        let m, found = 0;
        while ((m = re.exec(text))) {
          const u = m[0].replace(/\\u0026/g, '&');
          const prev = gifs.get(m[1]);
          // Prefer the animated original over static / cropped variants.
          if (!prev || (/animated/.test(u) && !/animated/.test(prev)) || (/original_aspect/.test(u) && !/original_aspect/.test(prev))) {
            gifs.set(m[1], u);
            found++;
          }
        }
        TT.log('comment gifs: ' + found + ' found via desktop lookup');
        if (found) { apply(); return; }
        if (depth) return;
        // Comments section of the desktop watch page: its continuation loads the first comments.
        const i = text.indexOf('comment-item-section');
        const m2 = i >= 0 && /"continuationCommand":{"token":"([^"]+)"/.exec(text.slice(i));
        if (!m2) return;
        const next = { context: req.context, continuation: m2[1] };
        lookup(url, init, JSON.stringify(next), 1);
      })
      .catch((e) => TT.log('comment gifs: lookup failed ' + e));
  }

  const css = document.createElement('style');
  css.textContent = `
    .tt-comment-gif { display: block; max-width: min(240px, 70%); max-height: 240px; height: auto;
      border-radius: 8px; margin: 6px 0 2px; }
  `;
  (document.head || document.documentElement).appendChild(css);

  function apply() {
    if (!gifs.size) return;
    for (const c of document.querySelectorAll('ytm-comment-renderer')) {
      const id = c.data && c.data.commentId;
      const url = id && gifs.get(id);
      if (!url) continue;
      const ph = c.querySelector('.YtmCommentRendererUnsupportedText');
      if (!ph || ph.dataset.ttGif === url) continue;
      ph.dataset.ttGif = url;
      ph.textContent = '';
      const img = document.createElement('img');
      img.className = 'tt-comment-gif';
      img.src = url;
      img.alt = 'GIF';
      img.loading = 'lazy';
      ph.appendChild(img);
    }
  }
  // m.youtube renders comments lazily and re-renders them while scrolling.
  setInterval(apply, 800);
})();
