// 10-cosmetic.js - applies the cosmetic part of Brave's adblock engine:
//  * hide selectors          -> one CSS rule each ({display:none!important})
//  * procedural filters      -> :has-text(), :upward(), :xpath(), :remove(), :style() ...
//  * generic class/id hiding -> new classes/ids are reported to the app, which asks the engine
// The per-host data is pushed into window.__TT_COSMETIC__ by the adblock DocumentStart script
// (AdblockIntegration.kt), which may run before or after this file.

(function () {
  const TT = window.TrueTube;
  const isTop = window.top === window;

  const applied = new Set();
  const procedural = [];
  let styleEl = null;
  let generic = false;

  // --- CSS hiding --------------------------------------------------------------------------
  function sheet() {
    if (!styleEl || !styleEl.isConnected) {
      styleEl = document.createElement('style');
      styleEl.id = 'tt-cosmetic';
      (document.head || document.documentElement).appendChild(styleEl);
      // Re-insert rules if the element had to be recreated.
      const s = styleEl.sheet;
      for (const sel of applied) insert(s, sel);
    }
    return styleEl.sheet;
  }

  function insert(s, sel) {
    try {
      s.insertRule(sel + '{display:none!important}', s.cssRules.length);
    } catch (e) {
      // invalid selector for this engine - skip it, like a browser would
    }
  }

  function hide(selectors) {
    if (!selectors || selectors.length === 0) return;
    const s = sheet();
    for (const sel of selectors) {
      if (applied.has(sel)) continue;
      applied.add(sel);
      insert(s, sel);
    }
  }

  // --- procedural filters ------------------------------------------------------------------
  function toMatcher(arg) {
    const m = /^\/(.+)\/([a-z]*)$/.exec(arg);
    if (m) {
      try {
        const re = new RegExp(m[1], m[2]);
        return (s) => re.test(s);
      } catch (e) { /* fall through */ }
    }
    return (s) => s.includes(arg);
  }

  function propMatcher(arg) {
    // "name: value" or 'name="value"' ; value may be /regex/
    const i = arg.search(/[:=]/);
    if (i < 0) return null;
    const name = arg.slice(0, i).trim();
    let value = arg.slice(i + 1).trim().replace(/^"(.*)"$/, '$1');
    return { name, test: toMatcher(value) };
  }

  function relative(nodes, sel) {
    const out = [];
    const s = sel.trim();
    const comb = s[0];
    for (const n of nodes) {
      if (comb === '+' || comb === '~') {
        const rest = s.slice(1).trim();
        let sib = n.nextElementSibling;
        while (sib) {
          if (sib.matches(rest)) out.push(sib);
          if (comb === '+') break;
          sib = sib.nextElementSibling;
        }
      } else {
        out.push(...n.querySelectorAll(comb === '>' ? ':scope ' + s : ':scope ' + s));
      }
    }
    return out;
  }

  function select(ops) {
    let nodes = null;
    for (const op of ops) {
      const arg = op.arg;
      switch (op.type) {
        case 'css-selector':
          nodes = nodes === null ? Array.from(document.querySelectorAll(arg)) : relative(nodes, arg);
          break;
        case 'has-text': {
          const t = toMatcher(arg);
          nodes = (nodes || []).filter((n) => t(n.textContent || ''));
          break;
        }
        case 'min-text-length': {
          const min = parseInt(arg, 10) || 0;
          nodes = (nodes || []).filter((n) => (n.textContent || '').length >= min);
          break;
        }
        case 'matches-attr': {
          const p = propMatcher(arg);
          nodes = p ? (nodes || []).filter((n) => n.hasAttribute(p.name) && p.test(n.getAttribute(p.name))) : [];
          break;
        }
        case 'matches-css':
        case 'matches-css-before':
        case 'matches-css-after': {
          const p = propMatcher(arg);
          const pseudo = op.type === 'matches-css' ? null : op.type === 'matches-css-before' ? '::before' : '::after';
          nodes = p ? (nodes || []).filter((n) => p.test(getComputedStyle(n, pseudo).getPropertyValue(p.name))) : [];
          break;
        }
        case 'matches-path':
          if (!toMatcher(arg)(location.pathname + location.search)) return [];
          if (nodes === null) nodes = [document.documentElement];
          break;
        case 'upward': {
          const n = parseInt(arg, 10);
          nodes = (nodes || []).map((el) => {
            if (!isNaN(n) && String(n) === arg.trim()) {
              let p = el;
              for (let i = 0; i < n && p; i++) p = p.parentElement;
              return p;
            }
            return el.parentElement ? el.parentElement.closest(arg) : null;
          }).filter(Boolean);
          break;
        }
        case 'xpath': {
          const ctx = nodes === null ? [document] : nodes;
          const out = [];
          for (const c of ctx) {
            const r = document.evaluate(arg, c, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
            for (let i = 0; i < r.snapshotLength; i++) {
              const node = r.snapshotItem(i);
              if (node.nodeType === 1) out.push(node);
            }
          }
          nodes = out;
          break;
        }
        default:
          TT && TT.log('unsupported procedural op', op.type);
          return [];
      }
      if (nodes.length === 0) return nodes;
    }
    return nodes || [];
  }

  function act(el, action) {
    if (!action) {
      el.style.setProperty('display', 'none', 'important');
      return;
    }
    switch (action.type) {
      case 'remove': el.remove(); break;
      case 'style': el.style.cssText += ';' + action.arg; break;
      case 'remove-attr': el.removeAttribute(action.arg); break;
      case 'remove-class': el.classList.remove(action.arg); break;
    }
  }

  function runProcedural() {
    for (const f of procedural) {
      try {
        for (const el of select(f.selector || [])) act(el, f.action);
      } catch (e) {
        // bad selector - ignore
      }
    }
  }

  // --- generic class/id hiding (top frame, via the bridge) -------------------------------
  const seenClasses = new Set();
  const seenIds = new Set();
  let pendingClasses = [];
  let pendingIds = [];
  let reportTimer = 0;

  function collect(el) {
    if (el.nodeType !== 1) return;
    if (el.id && !seenIds.has(el.id)) { seenIds.add(el.id); pendingIds.push(el.id); }
    for (const c of el.classList) {
      if (!seenClasses.has(c)) { seenClasses.add(c); pendingClasses.push(c); }
    }
  }

  function report() {
    reportTimer = 0;
    if (pendingClasses.length === 0 && pendingIds.length === 0) return;
    TT.post('cosmetic-classids', { host: location.hostname, classes: pendingClasses, ids: pendingIds });
    pendingClasses = [];
    pendingIds = [];
  }

  function scheduleReport() {
    if (!reportTimer) reportTimer = setTimeout(report, 250);
  }

  // --- wiring ------------------------------------------------------------------------------
  function flush() {
    const queue = window.__TT_COSMETIC__ || [];
    while (queue.length) {
      const d = queue.shift();
      if (d.host !== location.hostname) continue;
      hide(d.hide);
      procedural.push(...(d.procedural || []));
      generic = isTop && !d.generichide;
    }
    runProcedural();
  }

  if (TT) {
    TT.cosmeticFlush = flush;
    TT.on('cosmetic-hide', (cmd) => hide(cmd.selectors));
  }
  flush();

  let procTimer = 0;
  const observer = new MutationObserver((mutations) => {
    if (generic) {
      for (const m of mutations) {
        if (m.type === 'attributes') collect(m.target);
        for (const n of m.addedNodes) {
          if (n.nodeType !== 1) continue;
          collect(n);
          for (const child of n.querySelectorAll('[id],[class]')) collect(child);
        }
      }
      scheduleReport();
    }
    if (procedural.length && !procTimer) {
      procTimer = setTimeout(() => { procTimer = 0; runProcedural(); }, 200);
    }
  });

  const start = () => {
    sheet(); // make sure our <style> is in <head> once it exists
    if (generic) {
      for (const el of document.querySelectorAll('[id],[class]')) collect(el);
      scheduleReport();
    }
    runProcedural();
    observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'id'] });
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, { once: true });
  else start();
})();
