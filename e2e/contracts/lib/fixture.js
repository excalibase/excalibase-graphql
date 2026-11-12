const fs = require('fs');
const path = require('path');

const DIR = path.join(__dirname, '..', 'fixtures');

/** Compares against a reviewed fixture; UPDATE_CONTRACTS=1 rewrites it for review in the diff. */
function expectFixture(name, actual) {
  const file = path.join(DIR, `${name}.json`);
  const rendered = `${JSON.stringify(actual, null, 2)}\n`;
  if (process.env.UPDATE_CONTRACTS === '1') {
    fs.writeFileSync(file, rendered);
    return;
  }
  expect(JSON.parse(rendered)).toEqual(JSON.parse(fs.readFileSync(file, 'utf8')));
}

module.exports = { expectFixture };
