/** Prometheus text exposition: metric name -> sorted label keys (values are never kept). */
function parseMetricShape(text) {
  const shape = {};
  for (const line of text.split('\n')) {
    if (!line || line.startsWith('#')) continue;
    const match = line.match(/^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})?\s+\S+/);
    if (!match) continue;
    const keys = new Set(shape[match[1]] || []);
    for (const label of (match[2] || '').matchAll(/([a-zA-Z_][a-zA-Z0-9_]*)="/g)) keys.add(label[1]);
    shape[match[1]] = [...keys].sort();
  }
  return Object.fromEntries(Object.entries(shape).sort(([a], [b]) => a.localeCompare(b)));
}

module.exports = { parseMetricShape };

function parseSamples(text) {
  const samples = [];
  for (const line of text.split('\n')) {
    const match = line.match(/^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})?\s+(\S+)/);
    if (!match || line.startsWith('#')) continue;
    const labels = {};
    for (const label of (match[2] || '').matchAll(/([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"/g)) labels[label[1]] = label[2];
    samples.push({ name: match[1], labels, value: Number(match[3]) });
  }
  return samples;
}

/** Drops optional names the engine had not produced yet (GC cycles are timing dependent) from both sides. */
function withoutAbsentOptional(expected, actual, optionalPrefixes) {
  const isOptional = (name) => optionalPrefixes.some((prefix) => name.startsWith(prefix));
  const trim = (shape, other) => Object.fromEntries(Object.entries(shape).filter(([name]) => !isOptional(name) || name in other));
  return [trim(expected, actual), trim(actual, expected)];
}

module.exports.parseSamples = parseSamples;
module.exports.withoutAbsentOptional = withoutAbsentOptional;
