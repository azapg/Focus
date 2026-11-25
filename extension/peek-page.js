export function showPeek(result) {
  const get = (id) => document.getElementById(id);
  const status = get('status');
  if (!result?.snapshot) {
    status.textContent = {
      permalink: 'Open a direct link to a public post.',
      unsupported: 'Peek supports public posts on X and Facebook.',
      protection_off: 'Focus protection is paused.',
    }[result?.refusal] ?? 'Snapshot unavailable.';
    return;
  }

  const { site, title, text, image, image_alt: imageAlt } = result.snapshot;
  get('site').textContent = site;
  for (const [id, value] of [['title', title], ['text', text]]) {
    if (!value) continue;
    const el = get(id);
    el.textContent = value;
    el.hidden = false;
  }
  if (typeof image === 'string' && /^data:image\/(?:png|jpeg);base64,[A-Za-z0-9+/=]+$/.test(image)) {
    const el = get('image');
    el.src = image;
    el.alt = imageAlt ?? '';
    el.hidden = false;
  }
  status.textContent = 'Snapshot ready.';
  status.classList.add('screen-reader');
  get('snapshot').hidden = false;
}

const extension = globalThis.chrome?.runtime?.id && location.protocol === 'chrome-extension:';
if (extension) {
  const $ = (id) => document.getElementById(id);
  $('done').addEventListener('click', async () => {
    const tab = await chrome.tabs.getCurrent();
    if (tab) await chrome.tabs.remove(tab.id);
  });
  $('pause').addEventListener('click', async () => {
    const button = $('pause');
    const status = $('pause-status');
    button.disabled = true;
    let opened = false;
    try { opened = (await chrome.runtime.sendMessage({ type: 'peek_open_pause' }))?.opened === true; } catch { /* host unavailable */ }
    status.textContent = opened ? '' : 'Focus unavailable.';
    status.hidden = opened;
    button.disabled = false;
  });
  chrome.runtime.sendMessage({ type: 'peek_load' }).then(showPeek, () => showPeek(null));
}
