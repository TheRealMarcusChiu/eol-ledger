// Server-paged dashboard helpers in app.js (run: node --test src/test/js/server-paging.test.js).
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/app.js'), 'utf8');

function load() {
  const ctx = vm.createContext({ window: {}, document: { addEventListener() {}, getElementById() { return null; } }, URL });
  vm.runInContext(source, ctx);
  return ctx;
}

test('an excluded list without the column values survives decode and encode unchanged, so the server applies it', () => {
  const { decodeView, encodeView } = load();
  const keys = ['ecosystem', 'cycle'];
  const v = decodeView('{"v":1,"filters":{"ecosystem":{"excluded":["Unknown"]}}}', keys, {}, { key: 'eol', dir: 'asc' }, {});
  assert.strictEqual(v.filters.ecosystem.selected, null);
  assert.deepStrictEqual(Array.from(v.filters.ecosystem.excluded), ['Unknown']);
  assert.deepStrictEqual(JSON.parse(encodeView(v, keys, v.sort, {})).filters, { ecosystem: { excluded: ['Unknown'] } });
  // with the values known it resolves to a selection exactly as before
  const known = decodeView('{"v":1,"filters":{"ecosystem":{"excluded":["Unknown"]}}}', keys, {}, null, { ecosystem: ['npm', 'Unknown'] });
  assert.deepStrictEqual(Array.from(known.filters.ecosystem.selected), ['npm']);
  assert.strictEqual(known.filters.ecosystem.excluded, undefined);
  // an empty excluded list constrains nothing and is not carried
  assert.strictEqual(decodeView('{"v":1,"filters":{"ecosystem":{"excluded":[]}}}', keys, {}, null, {}).filters.ecosystem.excluded, undefined);
});

test('sync status text reports real phases and counts, then the terminal result', () => {
  const { syncText } = load();
  assert.strictEqual(syncText({ running: true, phase: 'fetching' }), 'Fetching the catalogue from endoflife.date…');
  assert.strictEqual(syncText({ running: true, phase: 'saving', productsDone: 2, productsTotal: 3, cyclesDone: 5, cyclesTotal: 7, current: 'python' }),
    'Saving product 3 of 3 (python) · 5 of 7 release cycles saved');
  assert.strictEqual(syncText({ running: false, ok: true, message: 'Synced 3 product(s)' }), 'Done: Synced 3 product(s)');
  assert.strictEqual(syncText({ running: false, ok: false, message: 'Synced 0 product(s); failed: catalogue fetch failed: 404' }),
    'Failed: Synced 0 product(s); failed: catalogue fetch failed: 404');
  assert.strictEqual(syncText({ running: false, phase: 'idle' }), '');
});
