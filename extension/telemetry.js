// One record per five-minute UTC bucket. Existing callbacks roll the bucket,
// so collection creates no alarms, timers or native messages.
const INTERVAL_MS = 300_000;
const BUCKETS_PER_DAY = 288;
const names = {
  starts: 'service_worker.starts',
  refreshes: 'service_worker.callbacks',
  messagesIn: 'service_worker.messages_in',
  messagesOut: 'service_worker.messages_out',
};

const bucket = () => Math.floor(Date.now() / INTERVAL_MS);
const retentionStart = (slot) => (Math.floor(slot / BUCKETS_PER_DAY) - 6) * BUCKETS_PER_DAY;
const empty = () => Object.fromEntries(Object.values(names).map((name) => [name, 0]));

let activeSlot = bucket(), pending = empty(), database, prunedDay = -1;

function open() {
  return database ??= new Promise((resolve, reject) => {
    const request = indexedDB.open('focus-resource-telemetry', 1);
    request.onupgradeneeded = () => request.result.createObjectStore('windows');
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  }).catch((error) => {
    database = undefined;
    throw error;
  });
}

async function persist(slot, counts) {
  const db = await open();
  const tx = db.transaction('windows', 'readwrite');
  const store = tx.objectStore('windows');
  const current = bucket();
  const first = retentionStart(current);
  if (first !== prunedDay) {
    store.delete(IDBKeyRange.upperBound(first, true));
    tx.oncomplete = () => { prunedDay = first; };
  }
  if (slot < first) return;
  const request = store.get(slot);
  request.onsuccess = () => {
    const merged = request.result?.counters ?? empty();
    for (const name of Object.values(names)) merged[name] += counts[name];
    store.put({
      v: 1, component: 'chromium-extension', at_ms: (slot + 1) * INTERVAL_MS,
      duration_ms: INTERVAL_MS, counters: merged, gauges: {},
    }, slot);
  };
}

export function start() {
  globalThis.exportFocusResourceTelemetry = exportRows;
  const counts = empty();
  counts[names.starts] = 1;
  void persist(bucket(), counts).catch(() => {});
}

export function count(metric) {
  const slot = bucket();
  if (slot !== activeSlot) {
    void persist(activeSlot, pending).catch(() => {});
    activeSlot = slot;
    pending = empty();
  }
  pending[names[metric]] += 1;
}

// Manual export from the service worker inspector. Include the current in-memory bucket.
export async function exportRows() {
  const db = await open();
  const active = { slot: activeSlot, counts: { ...pending } };
  const rows = await new Promise((resolve, reject) => {
    const request = db.transaction('windows').objectStore('windows')
      .getAll(IDBKeyRange.lowerBound(retentionStart(bucket())));
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  const at_ms = (active.slot + 1) * INTERVAL_MS;
  if (active.slot >= retentionStart(bucket())) {
    let row = rows.find((entry) => entry.at_ms === at_ms);
    if (!row) {
      row = { v: 1, component: 'chromium-extension', at_ms,
        duration_ms: INTERVAL_MS, counters: empty(), gauges: {} };
      rows.push(row);
    }
    for (const name of Object.values(names)) row.counters[name] += active.counts[name];
  }
  return rows;
}
