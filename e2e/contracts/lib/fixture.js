const fs = require('fs');
const path = require('path');

const DIR = path.join(__dirname, '..', 'fixtures');

const VARIANT = process.env.CONTRACT_VARIANT || '';

/**
 * Compares against a reviewed fixture; UPDATE_CONTRACTS=1 rewrites it for review in the diff. A build whose
 * framework cannot produce some of the signals of the original (CONTRACT_VARIANT=micronaut) has its own reviewed
 * `<name>.<variant>.json` for exactly those fixtures, and the diff against `<name>.json` is the list of differences.
 */
function fixtureFile(name) {
  const variantFile = path.join(DIR, `${name}.${VARIANT}.json`);
  return VARIANT && (process.env.UPDATE_CONTRACTS === '1' || fs.existsSync(variantFile)) ? variantFile : path.join(DIR, `${name}.json`);
}

/** `normalize` is applied to both sides, to leave out what depends on timing rather than on the engine. */
function expectFixture(name, actual, normalize = (value) => value) {
  const file = fixtureFile(name);
  const rendered = `${JSON.stringify(actual, null, 2)}\n`;
  if (process.env.UPDATE_CONTRACTS === '1') {
    fs.writeFileSync(file, rendered);
    return;
  }
  expect(normalize(JSON.parse(rendered))).toEqual(normalize(JSON.parse(fs.readFileSync(file, 'utf8'))));
}

module.exports = { expectFixture, fixtureFile };
