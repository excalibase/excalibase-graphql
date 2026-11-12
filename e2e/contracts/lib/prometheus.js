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
