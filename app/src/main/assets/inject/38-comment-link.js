// 38-comment-link.js - links to a comment (lc=, e.g. "@x replied" in the notifications):
// open the comments, expand the replies if needed, scroll to the comment and highlight it.
// m.youtube already puts the linked thread first when the watch page is loaded with lc=, but it
// neither opens the comments nor shows the reply. The app sends the id ("open-comment"), because
// m.youtube removes lc= from the URL right after loading. Player WebView only.

(function () {
  if (window.top !== window || !/(^|\.)youtube\.com$/.test(location.hostname)) return;
  if (TT_CONFIG.role !== 'player') return;
  const TT = window.YourTube;
  if (!TT) return;

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const currentId = () => new URLSearchParams(location.search).get('v') || '';

  /** Polls [find] every 250 ms until it returns something (or [ms] passed). */
  async function waitFor(find, ms) {
    const until = Date.now() + ms;
    while (Date.now() < until) {
      const r = find();
      if (r) return r;
      await sleep(250);
    }
    return null;
  }

  const commentId = (el) => {
    const d = el && el.data;
    return d && (d.commentId || (d.comment && (d.comment.commentId || (d.comment.commentRenderer && d.comment.commentRenderer.commentId)))) || '';
  };
  const threads = () => [...document.querySelectorAll('ytm-engagement-panel ytm-comment-thread-renderer')];
  const commentEl = (id) => [...document.querySelectorAll('ytm-engagement-panel ytm-comment-renderer')].find((c) => commentId(c) === id);

  function openComments() {
    if (location.hash.includes("bottom-sheet")) return true; // already open (still loading)
    const teaser = [...document.querySelectorAll('yt-video-metadata-carousel-view-model[role=button], ytm-comments-entry-point-header-renderer')]
      .find((e) => e.querySelector('comments-entry-point-teaser-view-model, yt-comment-teaser-carousel-item-view-model') || /Kommentar|Comment/i.test(e.textContent));
    if (!teaser) return false;
    teaser.click();
    return true;
  }

  function highlight(el) {
    el.scrollIntoView({ block: 'center', behavior: 'smooth' });
    const prev = el.style.cssText;
    el.style.transition = 'background-color .4s';
    el.style.backgroundColor = 'rgba(124, 77, 255, 0.18)';
    el.style.borderRadius = '12px';
    setTimeout(() => { el.style.backgroundColor = 'transparent'; }, 2600);
    setTimeout(() => { el.style.cssText = prev; }, 3200);
  }

  let running = '';

  TT.on('open-comment', async (cmd) => {
    const key = cmd.videoId + '|' + cmd.commentId;
    if (running === key) return;
    running = key;
    try {
      const [parentId] = cmd.commentId.split('.');
      const isReply = cmd.commentId.includes('.');
      if (!(await waitFor(() => currentId() === cmd.videoId, 15000))) return;
      // The comments entry appears below the description once the page has rendered; right after
      // that it ignores taps for a moment (not wired up yet), so tap again until the panel loads.
      let loaded = false;
      for (let i = 0; i < 5 && !loaded; i++) {
        if (!(await waitFor(openComments, i === 0 ? 15000 : 3000))) { TT.log('comment link: no comments entry'); return; }
        loaded = !!(await waitFor(() => threads().length > 0, 3000));
      }
      if (!loaded) { TT.log('comment link: comments did not load'); return; }

      // Linked thread: m.youtube puts it first (the page was loaded with lc=). The elements get
      // their data a moment after they appear, so wait for the ids before searching.
      const threadOf = () => threads().find((t) => commentId(t) === parentId || commentId(t.querySelector('ytm-comment-renderer')) === parentId);
      await waitFor(() => threads().some((t) => commentId(t)), 5000);
      let thread = await waitFor(threadOf, 2000);
      for (let i = 0; i < 6 && !thread; i++) {
        // Not among the loaded ones (link without highlight): load more by scrolling the panel.
        const last = threads().pop();
        if (last) last.scrollIntoView({ block: 'end' });
        thread = await waitFor(threadOf, 2500);
      }
      if (!thread) { TT.log('comment link: thread not found ' + parentId); return; }

      let target = thread.querySelector('ytm-comment-renderer');
      if (isReply) {
        // Replies load in pages of 10 behind "View replies" / "Mehr Antworten ansehen" - only that
        // continuation button, never the buttons of the replies themselves (menu, like ...).
        const moreButton = () => [...thread.querySelectorAll('ytm-comment-replies-renderer ytm-continuation-item-renderer button')].pop();
        target = commentEl(cmd.commentId);
        for (let i = 0; i < 30 && !target; i++) {
          const more = await waitFor(moreButton, 4000);
          if (!more) break;
          more.click();
          target = await waitFor(() => commentEl(cmd.commentId), 4000);
        }
        if (!target) TT.log('comment link: reply not found, showing the thread');
        target = target || thread.querySelector('ytm-comment-renderer');
      }
      // Let the panel finish its open animation before scrolling inside it.
      await sleep(400);
      highlight(target);
      TT.log('comment link: showing ' + cmd.commentId + (target && commentId(target) === cmd.commentId ? '' : ' (thread)'));
    } finally {
      running = '';
    }
  });
})();
