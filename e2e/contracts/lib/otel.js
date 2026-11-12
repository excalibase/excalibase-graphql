const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { stack } = require('./stack');
const { waitUntil } = require('./http');

const readLines = (file) => {
  const target = path.join(stack.otelOutDir, file);
  return fs.existsSync(target) ? fs.readFileSync(target, 'utf8').split('\n').filter(Boolean).map((line) => JSON.parse(line)) : [];
};

const newTraceparent = () => {
  const traceId = crypto.randomBytes(16).toString('hex');
  const parentSpanId = crypto.randomBytes(8).toString('hex');
  return { traceId, parentSpanId, header: `00-${traceId}-${parentSpanId}-01` };
};

function spansOfTrace(traceId) {
  return readLines('traces.jsonl')
    .flatMap((batch) => batch.resourceSpans.flatMap((resource) => resource.scopeSpans.flatMap((scope) => scope.spans.map((span) => ({ ...span, scope: scope.scope.name })))))
    .filter((span) => span.traceId === traceId);
}

/** Spans of one trace, once the collector has flushed them and no more arrive. */
async function collectTrace(traceId, { minSpans = 1 } = {}) {
  let previous = -1;
  return waitUntil(() => {
    const spans = spansOfTrace(traceId);
    const settled = spans.length >= minSpans && spans.length === previous;
    previous = spans.length;
    return settled ? spans : null;
  }, { timeoutMs: 30000, intervalMs: 1500, label: `trace ${traceId}` });
}

const attributeKeys = (span) => (span.attributes || []).map((attribute) => attribute.key).sort();

/** name -> { kind, count, attributes } so that two builds can be compared without ids or timings. */
function describeSpans(spans, parentSpanId) {
  const described = {};
  for (const span of spans) {
    const entry = described[span.name] || { kind: span.kind, count: 0, attributes: new Set(), scope: span.scope };
    entry.count += 1;
    attributeKeys(span).forEach((key) => entry.attributes.add(key));
    if (span.parentSpanId === parentSpanId) entry.childOfCaller = true;
    described[span.name] = entry;
  }
  return Object.fromEntries(Object.entries(described).sort(([a], [b]) => a.localeCompare(b))
    .map(([name, entry]) => [name, { ...entry, attributes: [...entry.attributes].sort() }]));
}

function attributeValues(spans, name) {
  const span = spans.find((candidate) => candidate.name === name);
  return Object.fromEntries((span.attributes || []).map((attribute) => [attribute.key, Object.values(attribute.value)[0]]));
}

module.exports = { readLines, newTraceparent, collectTrace, describeSpans, attributeValues };
