// Pure-logic checks for the dashboard column prefs in app.js (run: node --test src/test/js/app.test.js). Browser behaviour is proven separately.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/app.js'), 'utf8');

function load(window) {
  const ctx = vm.createContext({ window: window || {}, document: { addEventListener() {}, getElementById() { return null; } } });
  vm.runInContext(source, ctx);
  return ctx;
}

const plain = (o) => JSON.parse(JSON.stringify(o));
const DEFAULTS = {
  dependency: { visible: true, width: 220 }, version: { visible: true, width: 120 }, eol: { visible: true, width: 130 },
  status: { visible: true, width: 210 }, repos: { visible: true, width: 120 }, branches: { visible: true, width: 120 },
};

test('missing or malformed storage falls back to all six visible at default widths', () => {
  const { tablePrefs } = load();
  for (const text of [null, '', 'not json', 'null', '[]', '42', '"x"', '{}', '{"columns":null}', '{"columns":[1,2]}', '{"columns":{"dependency":"x"}}']) {
    assert.deepStrictEqual(plain(tablePrefs(text)), DEFAULTS, String(text));
  }
});

test('valid prefs round-trip; widths are clamped to 80..1000 and rounded', () => {
  const { tablePrefs } = load();
  const p = plain(tablePrefs(JSON.stringify({ columns: {
    dependency: { visible: false, width: 5 }, version: { visible: false, width: 5000 }, eol: { width: 200.6 }, status: { visible: true, width: 300 },
  } })));
  assert.deepStrictEqual(p.dependency, { visible: false, width: 80 });
  assert.deepStrictEqual(p.version, { visible: false, width: 1000 });
  assert.deepStrictEqual(p.eol, { visible: true, width: 201 });
  assert.deepStrictEqual(p.status, { visible: true, width: 300 });
  assert.deepStrictEqual(p.repos, DEFAULTS.repos);
});

test('wrong types and non-finite widths use the default for that field only', () => {
  const { tablePrefs } = load();
  const p = plain(tablePrefs('{"columns":{"dependency":{"visible":"no","width":"300"},"version":{"visible":0,"width":1e999},"eol":{"visible":false,"width":null}}}'));
  assert.deepStrictEqual(p.dependency, DEFAULTS.dependency);
  assert.deepStrictEqual(p.version, DEFAULTS.version);
  assert.deepStrictEqual(p.eol, { visible: false, width: 130 });
});

test('all hidden is never accepted: every column comes back visible', () => {
  const { tablePrefs } = load();
  const columns = {};
  for (const key of Object.keys(DEFAULTS)) columns[key] = { visible: false, width: 150 };
  const p = plain(tablePrefs(JSON.stringify({ columns })));
  for (const key of Object.keys(DEFAULTS)) assert.deepStrictEqual(p[key], { visible: true, width: 150 }, key);
});

test('hostile keys are ignored and never reach any prototype', () => {
  const ctx = load();
  const p = ctx.tablePrefs('{"__proto__":{"columns":{"dependency":{"visible":false}}},"columns":{"__proto__":{"visible":false,"width":90},'
    + '"constructor":{"prototype":{"visible":false}},"toString":{"visible":false},"dependency":{"__proto__":{"visible":false}}}}');
  assert.deepStrictEqual(plain(p), DEFAULTS);
  assert.deepStrictEqual(Object.keys(p), Object.keys(DEFAULTS));
  assert.strictEqual(vm.runInContext('({}).visible === undefined && ({}).width === undefined && ({}).columns === undefined', ctx), true);
});

test('blocked or throwing localStorage returns null instead of throwing', () => {
  const blocked = {};
  Object.defineProperty(blocked, 'localStorage', { get() { throw new Error('SecurityError'); } });
  assert.strictEqual(load(blocked).tableStorage('getItem'), null);
  const failing = { localStorage: { getItem() { throw new Error('x'); }, setItem() { throw new Error('quota'); }, removeItem() { throw new Error('x'); } } };
  const ctx = load(failing);
  for (const m of ['getItem', 'setItem', 'removeItem']) assert.strictEqual(ctx.tableStorage(m, '{}'), null, m);
});

test('storage uses only its own key', () => {
  const calls = [];
  const store = { getItem: (k) => { calls.push(['get', k]); return null; }, setItem: (k, v) => calls.push(['set', k, v]), removeItem: (k) => calls.push(['remove', k]) };
  const ctx = load({ localStorage: store });
  ctx.tableStorage('getItem'); ctx.tableStorage('setItem', '{}'); ctx.tableStorage('removeItem');
  assert.deepStrictEqual(calls, [['get', 'eol-ledger.dependencies-table.v1'], ['set', 'eol-ledger.dependencies-table.v1', '{}'], ['remove', 'eol-ledger.dependencies-table.v1']]);
});
