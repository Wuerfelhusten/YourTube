// 00-core.js - TrueTube runtime core.
// Runs at document start in every youtube.com frame, before any YouTube script.
// Provides window.TrueTube (bridge client) and reports SPA navigations to the app.

const native = typeof TrueTubeNative !== 'undefined' ? TrueTubeNative : null;
const isTop = window.top === window;
const handlers = Object.create(null);

const TT = {
  version: TT_CONFIG.version,
  debug: TT_CONFIG.debug,

  /** Send a message to the app: TT.post('navigation', { url }) */
  post(type, data) {
    if (!native) return;
    try {
      native.postMessage(JSON.stringify(Object.assign({ type }, data || {})));
    } catch (e) {
      console.warn('[TrueTube] post failed', type, e);
    }
  },

  /** Handle a command from the app: TT.on('ping', cmd => ...) */
  on(type, fn) {
    (handlers[type] || (handlers[type] = [])).push(fn);
  },

  log(...args) {
    if (TT.debug) console.log('[TrueTube]', ...args);
  },
};

Object.defineProperty(window, 'TrueTube', { value: TT, writable: false, configurable: false, enumerable: false });

if (native) {
  native.onmessage = (event) => {
    let cmd;
    try {
      cmd = JSON.parse(event.data);
    } catch (e) {
      return;
    }
    for (const fn of handlers[cmd.type] || []) {
      try {
        fn(cmd);
      } catch (e) {
        console.error('[TrueTube] handler failed', cmd.type, e);
      }
    }
  };
}

TT.on('ping', (cmd) => TT.post('pong', { nonce: cmd.nonce }));

// --- SPA navigation tracking (top frame only) ---------------------------------------------
if (isTop) {
  let last = '';
  let timer = 0;

  const report = () => {
    const url = location.href;
    const title = document.title || '';
    const key = url + '\n' + title;
    if (key === last) return;
    last = key;
    TT.post('navigation', { url, title });
    window.dispatchEvent(new CustomEvent('tt:navigate', { detail: { url } }));
  };
  const schedule = () => {
    clearTimeout(timer);
    timer = setTimeout(report, 100);
  };

  for (const method of ['pushState', 'replaceState']) {
    const original = history[method];
    history[method] = function (...args) {
      const result = original.apply(this, args);
      schedule();
      return result;
    };
  }
  window.addEventListener('popstate', schedule);
  window.addEventListener('hashchange', schedule);

  // The <title> is updated after the URL during SPA navigation.
  document.addEventListener('DOMContentLoaded', () => {
    if (document.head) {
      new MutationObserver(schedule).observe(document.head, { childList: true, subtree: true, characterData: true });
    }
    schedule();
  });

  TT.post('ready', { version: TT.version, url: location.href });
  TT.log('core loaded', location.href);
}
