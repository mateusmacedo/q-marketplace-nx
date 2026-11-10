// Leitura de /q/metrics da api (latência de inventário) e do worker (outbox), cada uma na porta de gestão do papel
// (ADR 020).
import http from 'k6/http';
import { params } from '../config.js';

function parseMetrics(text) {
  const samples = [];
  for (const line of text.split('\n')) {
    if (line === '' || line.startsWith('#')) continue;
    const match = /^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+(\S+)/.exec(line);
    if (!match) continue;
    const labels = {};
    const labelPattern = /\b(\w+)="([^"]*)"/g;
    let label;
    while ((label = labelPattern.exec(match[2] || '')) !== null) labels[label[1]] = label[2];
    samples.push({ name: match[1], labels, value: Number(match[3]) });
  }
  return samples;
}

function maxOf(samples, name, filter) {
  let result = null;
  for (const s of samples) {
    if (s.name !== name || Number.isNaN(s.value)) continue;
    if (filter && Object.keys(filter).some((k) => s.labels[k] !== filter[k])) continue;
    result = result === null ? s.value : Math.max(result, s.value);
  }
  return result;
}

export function sumOf(samples, name, filter) {
  return samples
    .filter(
      (s) => s.name === name && !Object.keys(filter || {}).some((k) => s.labels[k] !== filter[k]),
    )
    .reduce((acc, s) => acc + s.value, 0);
}

// Buckets de catalog.inventory.update.latency somados por operação e limite, como sum by (le, operation).
export function latencyBuckets(samples) {
  const buckets = {};
  for (const s of samples) {
    if (s.name !== 'catalog_inventory_update_latency_seconds_bucket') continue;
    const op = s.labels.operation;
    buckets[op] = buckets[op] || {};
    buckets[op][s.labels.le] = (buckets[op][s.labels.le] || 0) + s.value;
  }
  return buckets;
}

// Mesma interpolação do histogram_quantile do Prometheus, sobre o aumento dos buckets durante o teste.
export function quantileFromDelta(q, before, after) {
  const bounds = Object.keys(after)
    .map((le) => ({
      le: le === '+Inf' ? Infinity : Number(le),
      count: after[le] - (before?.[le] || 0),
    }))
    .sort((a, b) => a.le - b.le);
  const total = bounds.at(-1)?.count ?? 0;
  if (total <= 0) return null;
  const rank = q * total;
  let previous = { le: 0, count: 0 };
  for (const b of bounds) {
    if (b.count >= rank) {
      if (b.le === Infinity) return previous.le;
      if (b.count === previous.count) return b.le;
      return (
        previous.le + ((b.le - previous.le) * (rank - previous.count)) / (b.count - previous.count)
      );
    }
    previous = b;
  }
  return null;
}

export function readMetrics(tags) {
  const requestParams = { tags: { kind: 'metrics', name: 'GET /q/metrics', ...tags } };
  const responses = http.batch(
    params.managementUrls.map((url) => ['GET', `${url}/q/metrics`, null, requestParams]),
  );
  const failed = responses.find((res) => res.status !== 200);
  if (failed) return { ok: false, status: failed.status };
  const samples = responses.flatMap((res) => parseMetrics(res.body));
  return {
    ok: true,
    samples,
    lagMax: maxOf(samples, 'catalog_outbox_publish_lag_seconds_max') || 0,
    lagP99: maxOf(samples, 'catalog_outbox_publish_lag_seconds', { quantile: '0.99' }),
    pending: maxOf(samples, 'catalog_outbox_pending'),
  };
}
