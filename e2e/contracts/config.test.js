const fs = require('fs');
const path = require('path');
const { CASES } = require('./lib/configCases');
const { removeAll } = require('./lib/containers');
const catalog = require('./fixtures/config-catalog.json');

// Five cold engines at once on a CI runner left some first requests past the 30s limit; two keep each start well inside it.
const CONCURRENCY = Number(process.env.CONTRACT_CONFIG_CONCURRENCY || 2);
const outcomes = {};

async function runAll(cases) {
  const queue = [...cases];
  const worker = async () => {
    for (let next = queue.shift(); next; next = queue.shift()) {
      const [name, run] = next;
      outcomes[name] = await run().catch((error) => ({ error: error.message.split('\n')[0] }));
    }
  };
  await Promise.all(Array.from({ length: CONCURRENCY }, worker));
}

beforeAll(() => runAll(CASES), 900000);
afterAll(removeAll);

describe('every documented environment variable form takes effect', () => {
  test.each(CASES.map(([name, , expected]) => [name, expected]))('%s', (name, expected) => {
    expect(outcomes[name]).toEqual(expected);
  });
});

describe('the catalog of keys', () => {
  test('every property has its relaxed environment form and any explicit alias, with no duplicates', () => {
    const properties = catalog.entries.map((entry) => entry.property);
    expect(new Set(properties).size).toBe(properties.length);
    catalog.entries.forEach((entry) => {
      expect(entry.envForms).toContain(entry.property.replace(/[^A-Za-z0-9]/g, '_').toUpperCase());
    });
  });

  // Reads the Spring sources of the engine; skipped on a build that does not have them.
  const root = path.join(__dirname, '..', '..', 'modules');
  const yaml = path.join(root, 'excalibase-graphql-api', 'src', 'main', 'resources', 'application.yaml');
  const sources = fs.existsSync(yaml) ? javaFiles(root) : [];
  const maybe = sources.length ? test : test.skip;

  maybe('every environment placeholder in application.yaml and every @Value key is in the catalog', () => {
    const known = new Set(catalog.entries.flatMap((entry) => [entry.property, ...entry.envForms]));
    const yamlEnv = [...fs.readFileSync(yaml, 'utf8').matchAll(/\$\{([A-Z][A-Z0-9_]*)[:}]/g)].map((match) => match[1]);
    const valueKeys = sources.flatMap((file) => [...fs.readFileSync(file, 'utf8').matchAll(/@Value\("\$\{([a-z][a-z0-9.\-]*)[:}]/g)].map((match) => match[1]));
    expect([...yamlEnv, ...valueKeys].filter((key) => !known.has(key))).toEqual([]);
  });
});

function javaFiles(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const target = path.join(directory, entry.name);
    if (entry.isDirectory()) return entry.name === 'test' || entry.name === 'target' || entry.name === 'node_modules' ? [] : javaFiles(target);
    return target.endsWith('.java') ? [target] : [];
  });
}
