const BASE = process.env.BASE_URL || 'http://localhost:8080';
const total = Number(process.env.TOTAL || 200);
const concurrency = Number(process.env.CONCURRENCY || 20);
const stock = Number(process.env.STOCK || 100);
const productId = Number(process.env.PRODUCT_ID || 1);
const userOffset = Number(process.env.USER_OFFSET || 3000000);

function percentile(arr, p) {
  if (!arr.length) return 0;
  const sorted = [...arr].sort((a, b) => a - b);
  const idx = Math.max(0, Math.ceil(p * sorted.length) - 1);
  return sorted[idx];
}

async function requestJson(url, method = 'GET') {
  const res = await fetch(url, { method });
  const raw = await res.text();
  try {
    return raw ? JSON.parse(raw) : {};
  } catch {
    return {};
  }
}

async function initStock() {
  await fetch(`${BASE}/api/seckill/init?seckillProductId=${productId}&stock=${stock}`, { method: 'POST' });
}

async function getStock() {
  const data = await requestJson(`${BASE}/api/seckill/stock?seckillProductId=${productId}`);
  return Number(data.stock ?? 0);
}

async function getQueueMetrics() {
  const data = await requestJson(`${BASE}/api/seckill/queue/metrics`);
  return {
    queueSize: Number(data.queueSize ?? 0),
    deadLetterSize: Number(data.deadLetterSize ?? 0),
    retryingMessages: Number(data.retryingMessages ?? 0),
    doneMarkers: Number(data.doneMarkers ?? 0)
  };
}

async function hit(userId) {
  const start = process.hrtime.bigint();
  try {
    const res = await fetch(`${BASE}/api/seckill/start?userId=${userId}&seckillProductId=${productId}&quantity=1`, { method: 'POST' });
    const raw = await res.text();
    let payload = null;
    try {
      payload = raw ? JSON.parse(raw) : null;
    } catch {
      payload = null;
    }
    const elapsed = Number(process.hrtime.bigint() - start) / 1e6;
    const message = payload?.message || (!res.ok ? `HTTP_${res.status}` : 'UNKNOWN');
    return { ok: Boolean(payload?.success), ms: elapsed, httpOk: res.ok, message };
  } catch {
    const elapsed = Number(process.hrtime.bigint() - start) / 1e6;
    return { ok: false, ms: elapsed, httpOk: false, message: 'HTTP_ERROR' };
  }
}

async function run() {
  await initStock();
  const beforeQueue = await getQueueMetrics();
  const beforeStock = await getStock();

  const begin = process.hrtime.bigint();
  const workers = [];

  for (let w = 0; w < concurrency; w += 1) {
    workers.push((async () => {
      const one = [];
      for (let i = w + 1; i <= total; i += concurrency) {
        one.push(await hit(userOffset + i));
      }
      return one;
    })());
  }

  const nested = await Promise.all(workers);
  const all = nested.flat();
  const wallMs = Number(process.hrtime.bigint() - begin) / 1e6;

  const success = all.filter((x) => x.ok).length;
  const fail = all.length - success;
  const lat = all.map((x) => x.ms);
  const avg = lat.reduce((a, b) => a + b, 0) / Math.max(1, lat.length);

  const afterStock = await getStock();
  const afterQueue = await getQueueMetrics();

  const reasonCounter = {};
  for (const item of all) {
    if (!item.ok) {
      const key = item.message || 'UNKNOWN';
      reasonCounter[key] = (reasonCounter[key] || 0) + 1;
    }
  }

  const dlqDelta = afterQueue.deadLetterSize - beforeQueue.deadLetterSize;
  const queueDelta = afterQueue.queueSize - beforeQueue.queueSize;
  const doneDelta = afterQueue.doneMarkers - beforeQueue.doneMarkers;
  const consumed = beforeStock - afterStock;

  const checks = {
    noOversell: success <= stock && afterStock >= 0,
    stockMatch: consumed === success,
    noNewDlq: dlqDelta === 0,
    doneNonNegative: doneDelta >= 0
  };

  const result = {
    input: {
      baseUrl: BASE,
      total,
      concurrency,
      productId,
      userOffset,
      stock
    },
    throughput: {
      total: all.length,
      success,
      fail,
      successRate: Number(((success / Math.max(1, all.length)) * 100).toFixed(2)),
      wallMs: Number(wallMs.toFixed(2)),
      rps: Number((all.length / (wallMs / 1000)).toFixed(2))
    },
    latency: {
      avgMs: Number(avg.toFixed(2)),
      p50Ms: Number(percentile(lat, 0.5).toFixed(2)),
      p95Ms: Number(percentile(lat, 0.95).toFixed(2)),
      p99Ms: Number(percentile(lat, 0.99).toFixed(2))
    },
    stock: {
      before: beforeStock,
      after: afterStock,
      consumed,
      configured: stock
    },
    queue: {
      before: beforeQueue,
      after: afterQueue,
      delta: {
        queue: queueDelta,
        deadLetter: dlqDelta,
        doneMarkers: doneDelta,
        retryingMessages: afterQueue.retryingMessages - beforeQueue.retryingMessages
      }
    },
    checks,
    failReasons: reasonCounter
  };

  console.log(JSON.stringify(result, null, 2));
  if (!checks.noOversell || !checks.noNewDlq) {
    process.exit(2);
  }
}

run().catch((err) => {
  console.error(err?.message || err);
  process.exit(1);
});
