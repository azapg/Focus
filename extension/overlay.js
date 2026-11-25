// Serialized by chrome.scripting and run in the isolated world of a blocked document.
export function installGate(top, endedAt) {
  const key = '__focusDnsGate';
  if (globalThis[key]) {
    globalThis[key].update(endedAt);
    return;
  }
  const root = document.documentElement;
  if (!root) return;

  let dialog, time, guard;
  if (top) {
    dialog = document.createElement('dialog');
    dialog.setAttribute('aria-label', 'DNS is back on. This site is blocked.');
    dialog.style.cssText = `
      all: initial !important; position: fixed !important; inset: 0 !important;
      width: 100% !important; height: 100% !important; margin: 0 !important;
      max-width: none !important; max-height: none !important; border: 0 !important;
      box-sizing: border-box !important; display: grid !important; place-items: center !important;
      background: #181b19 !important; color: #f4f5f4 !important;
      color-scheme: dark !important; overflow: auto !important; overscroll-behavior: contain !important;
    `;
    const card = document.createElement('section');
    card.style.cssText = 'padding: 2rem; max-width: 34rem; font: 16px/1.55 system-ui, sans-serif;';
    const heading = document.createElement('h1');
    heading.style.cssText = 'font-size: 1.5rem; font-weight: 600; margin: 0;';
    heading.textContent = 'DNS is back on. This site is blocked.';
    time = document.createElement('p');
    time.style.color = '#b8c0ba';
    card.append(heading, time);
    const surface = document.createElement('div');
    surface.style.cssText = 'all: initial !important; display: block !important;';
    surface.attachShadow({ mode: 'closed' }).append(card);
    dialog.append(surface);
    dialog.addEventListener('cancel', (event) => event.preventDefault());
    for (const type of ['keydown', 'keyup', 'click', 'wheel']) {
      dialog.addEventListener(type, (event) => event.stopPropagation());
    }

    const show = () => {
      if (!dialog.isConnected) document.documentElement?.append(dialog);
      if (dialog.isConnected && !dialog.open) dialog.showModal();
    };
    // A modal makes the rest of the document inert without editing its attributes.
    show();
    guard = new MutationObserver(show);
    guard.observe(document, { childList: true });
    guard.observe(root, { childList: true });
    guard.observe(dialog, { attributes: true, attributeFilter: ['open'] });
  }

  const update = (ms) => {
    if (!time) return;
    time.hidden = !ms;
    time.textContent = ms ? 'Pause ended ' + new Date(ms).toLocaleString() : '';
  };
  const stopMedia = (media) => {
    if (media instanceof HTMLMediaElement && !media.paused) media.pause();
  };
  const onPlay = (event) => stopMedia(event.target);
  const mediaRoots = new Set();
  const mediaObserver = new MutationObserver((records) => {
    for (const { addedNodes } of records) {
      for (const node of addedNodes) if (node instanceof Element) visitMedia(node);
    }
  });
  function visitMedia(node) {
    const elements = node.querySelectorAll('*');
    if (node instanceof Element) inspectElement(node);
    for (const element of elements) inspectElement(element);
  }
  function inspectElement(element) {
    stopMedia(element);
    if (!(element instanceof HTMLElement)) return;
    const shadow = chrome.dom.openOrClosedShadowRoot(element);
    if (shadow && !mediaRoots.has(shadow)) watchMedia(shadow);
  }
  function watchMedia(container) {
    mediaRoots.add(container);
    container.addEventListener('play', onPlay, true);
    mediaObserver.observe(container, { childList: true, subtree: true });
    visitMedia(container);
  }
  const stopPlayback = () => {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {});
  };
  watchMedia(document);
  // Attaching a shadow root to an existing host creates no observable mutation.
  const discoverMedia = setInterval(() => {
    for (const container of mediaRoots) {
      if (container === document || container.host.isConnected) visitMedia(container);
    }
  }, 2000);
  document.addEventListener('fullscreenchange', stopPlayback);
  document.addEventListener('enterpictureinpicture', stopPlayback, true);
  stopPlayback();

  const clear = (message, sender) => {
    if (sender.id !== chrome.runtime.id || message?.type !== 'clear-dns-gate') return;
    chrome.runtime.onMessage.removeListener(clear);
    clearInterval(discoverMedia);
    mediaObserver.disconnect();
    for (const container of mediaRoots) container.removeEventListener('play', onPlay, true);
    document.removeEventListener('fullscreenchange', stopPlayback);
    document.removeEventListener('enterpictureinpicture', stopPlayback, true);
    guard?.disconnect();
    if (dialog?.open) dialog.close();
    dialog?.remove();
    delete globalThis[key];
  };
  chrome.runtime.onMessage.addListener(clear);
  globalThis[key] = { update };
  update(endedAt);
}
