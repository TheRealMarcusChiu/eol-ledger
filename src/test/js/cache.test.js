// Storage-level checks of the per-tab JSON cache in app.js, with no DOM; the initTable behaviour is in app.test.js.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/app.js'), 'utf8');
const load = window => vm.runInContext(source, vm.createContext({ window, URL,
  document: { addEventListener() {}, getElementById() { return null; } } }));
const reload = { getEntriesByType: t => (t === 'navigation' ? [{ type: 'reload' }] : []) };

test('loading app.js on a reload with blocked or missing storage and timing never throws', () => {
  const blocked = { get length() { throw new Error('blocked'); }, key() { throw new Error('blocked'); }, removeItem() { throw new Error('blocked'); } };
  for (const window of [{}, { performance: reload }, { performance: reload, sessionStorage: blocked }, { performance: { getEntriesByType() { throw new Error('x'); } } }]) {
    assert.doesNotThrow(() => load(window));
  }
});

test('a reload clears only keys under the cache prefix, whatever else the tab stores', () => {
  const session = new Map([['eol-ledger.json-cache.v1.https://x.test/api/dashboard?view=1', '{}'], ['eol-ledger.json-cache.v1.x', '[]'],
    ['eol-ledger.json-cache.v2.future', 'kept'], ['eol-ledger.page-size.v1.dashboard-table', '50'], ['someone-else', 'kept']]);
  const sessionStorage = { get length() { return session.size; }, key: i => [...session.keys()][i] ?? null, removeItem: k => { session.delete(k); } };
  load({ performance: reload, sessionStorage });
  assert.deepStrictEqual([...session.keys()], ['eol-ledger.json-cache.v2.future', 'eol-ledger.page-size.v1.dashboard-table', 'someone-else']);
  // a plain navigation keeps the cache
  session.set('eol-ledger.json-cache.v1.y', '{}');
  load({ performance: { getEntriesByType: () => [{ type: 'navigate' }] }, sessionStorage });
  assert.ok(session.has('eol-ledger.json-cache.v1.y'));
});
