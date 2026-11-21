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

// ---- header sort and filters ----

const order = (ctx, values, type, dir) => Array.from(ctx.sortOrder(values, type, dir)).map((i) => values[i]);

test('typed sort: natural versions, real numbers, ISO dates; blanks last both ways; stable ties', () => {
  const ctx = load();
  assert.deepStrictEqual(order(ctx, ['3.10', '3.9', '2.7', '3.1'], 'version', 'asc'), ['2.7', '3.1', '3.9', '3.10']);
  assert.deepStrictEqual(order(ctx, ['3.10', '3.9', '2.7', '3.1'], 'version', 'desc'), ['3.10', '3.9', '3.1', '2.7']);
  assert.deepStrictEqual(order(ctx, ['10', '9', '1'], 'number', 'asc'), ['1', '9', '10']);
  assert.deepStrictEqual(order(ctx, ['', '2026-11-20', '2025-01-01', ''], 'date', 'asc'), ['2025-01-01', '2026-11-20', '', '']);
  assert.deepStrictEqual(order(ctx, ['', '2026-11-20', '2025-01-01', ''], 'date', 'desc'), ['2026-11-20', '2025-01-01', '', '']);
  assert.deepStrictEqual(Array.from(ctx.sortOrder(['b', 'a', 'B', 'A'], 'text', 'asc')), [1, 3, 0, 2], 'case-insensitive ties keep original order');
  assert.deepStrictEqual(Array.from(ctx.sortOrder(['x', '', 'x', ''], 'text', 'desc')), [0, 2, 1, 3]);
});

test('every text operator is literal and case-insensitive, never a regex', () => {
  const { textMatches } = load();
  const cases = [
    ['contains', 'Python', 'yth', true], ['contains', 'Python', 'java', false],
    ['not-contains', 'Python', 'yth', false], ['not-contains', 'Python', 'java', true],
    ['exact', 'Python', 'python', true], ['exact', 'Python', 'pyth', false],
    ['not-exact', 'Python', 'python', false], ['not-exact', 'Python', 'pyth', true],
    ['starts-with', 'Python', 'PY', true], ['starts-with', 'Python', 'on', false],
    ['ends-with', 'Python', 'ON', true], ['ends-with', 'Python', 'py', false],
    ['is-blank', '', 'ignored', true], ['is-blank', 'x', '', false], ['not-blank', 'x', '', true], ['not-blank', ' ', '', false],
    ['contains', 'a.b', '.', true], ['contains', 'abc', '.*', false], ['exact', '3.10', '3.1', false],
  ];
  for (const [op, v, q, want] of cases) assert.strictEqual(textMatches(op, v, q), want, `${op} ${v} ${q}`);
  for (const op of ['contains', 'not-contains', 'exact', 'not-exact', 'starts-with', 'ends-with', 'fuzzy']) {
    assert.strictEqual(textMatches(op, 'anything', '  '), true, `${op} with a blank query constrains nothing`);
  }
});

test('fuzzy handles missing letters, transpositions and several words; stays strict on short or unrelated words', () => {
  const { textMatches } = load();
  for (const q of ['pyhton', 'pythno', 'pyton', 'PYTHON', 'pyth']) assert.ok(textMatches('fuzzy', 'Python', q), q);
  assert.ok(textMatches('fuzzy', 'Spring Framework', 'sprng framwork'));
  assert.ok(textMatches('fuzzy', 'Ends in 3 months', 'ends mnths'));
  for (const q of ['java', 'pxx', 'pyhton java']) assert.ok(!textMatches('fuzzy', 'Python', q), q);
  assert.strictEqual(load().editDistance('pyhton', 'python'), 1);
});

test('range bounds: real ISO dates and numbers only, reversed ranges rejected, blanks open', () => {
  const { checkRange, parseBound } = load();
  assert.deepStrictEqual(plain(checkRange('date', '2025-01-01', '')), { start: '2025-01-01', end: null });
  assert.deepStrictEqual(plain(checkRange('date', ' ', '')), { start: null, end: null });
  for (const bad of ['2026-02-30', '2026-13-01', '26-01-01', '2026/01/01', 'today']) assert.ok(parseBound('date', bad).error, bad);
  assert.ok(parseBound('date', '2024-02-29').value);
  for (const bad of ['abc', '1e3', '1,5', 'NaN']) assert.ok(parseBound('number', bad).error, bad);
  assert.match(checkRange('date', '2026-12-01', '2026-01-01').error, /on or before/);
  assert.match(checkRange('number', '10', '9').error, /on or before/);
  assert.match(checkRange('version', '3.10', '3.9').error, /on or before/);
  assert.deepStrictEqual(plain(checkRange('version', '3.9', '3.10')), { start: '3.9', end: '3.10' });
});

test('filters: values OR within a column, AND across columns, open/inclusive ranges, unknown dates only via values/blank', () => {
  const ctx = load();
  const rows = [
    { dependency: 'Python', version: '3.9', eol: '2025-10-31', status: 'Ended', repos: '1', branches: '1' },
    { dependency: 'Python', version: '3.10', eol: '2026-10-31', status: 'Ends soon', repos: '2', branches: '3' },
    { dependency: 'React', version: '18', eol: '', status: 'Unknown', repos: '10', branches: '10' },
  ];
  const f = () => { const o = {}; for (const k of Object.keys(DEFAULTS)) o[k] = ctx.emptyFilter(); return o; };
  const shown = (filters) => Array.from(ctx.visibleRows(rows, filters));
  assert.deepStrictEqual(shown(f()), [true, true, true]);

  let x = f(); x.version.selected = ['3.9', '18'];
  assert.deepStrictEqual(shown(x), [true, false, true]);
  x.dependency.selected = ['Python'];
  assert.deepStrictEqual(shown(x), [true, false, false]);
  x = f(); x.eol.selected = [];
  assert.deepStrictEqual(shown(x), [false, false, false], 'an empty selection is an active filter showing 0 rows');
  x = f(); x.eol.selected = [''];
  assert.deepStrictEqual(shown(x), [false, false, true], 'blank dates are selectable values');

  x = f(); Object.assign(x.eol, { mode: 'range', start: '2025-10-31', end: null });
  assert.deepStrictEqual(shown(x), [true, true, false], 'inclusive start, open end, unknown excluded');
  Object.assign(x.eol, { start: null, end: '2025-10-31' });
  assert.deepStrictEqual(shown(x), [true, false, false]);
  x = f(); Object.assign(x.repos, { mode: 'range', start: '2', end: '10' });
  assert.deepStrictEqual(shown(x), [false, true, true], 'numbers compare as numbers, not text');
  x = f(); Object.assign(x.version, { mode: 'range', start: '3.9', end: '3.10' });
  assert.deepStrictEqual(shown(x), [true, true, false], 'versions compare naturally');
  x = f(); Object.assign(x.eol, { mode: 'text', op: 'is-blank' });
  assert.deepStrictEqual(shown(x), [false, false, true]);
  x = f(); Object.assign(x.dependency, { mode: 'text', op: 'fuzzy', text: 'pyhton' });
  assert.deepStrictEqual(shown(x), [true, true, false]);
});

test('only the selected mode applies; switching mode drops the invisible constraint', () => {
  const ctx = load();
  const col = Object.assign(ctx.emptyFilter(), { selected: [], text: 'zzz', start: '9' });
  assert.ok(ctx.filterPredicate(col, 'number'), 'values mode with nothing selected is active');
  col.mode = 'text'; col.op = 'contains';
  assert.strictEqual(ctx.filterPredicate(col, 'number')('5'), false);
  col.text = ''; assert.strictEqual(ctx.filterPredicate(col, 'number'), null, 'blank text is inactive even with a values selection behind it');
  col.mode = 'range'; assert.strictEqual(ctx.filterPredicate(col, 'number')('5'), false);
  col.start = null; assert.strictEqual(ctx.filterPredicate(col, 'number'), null);
  assert.strictEqual(ctx.filterPredicate(ctx.emptyFilter(), 'text'), null, 'a fresh (cleared) filter is inactive');
});

test('values toggling: unchecking and rechecking every value returns to "no constraint"', () => {
  const { toggleValue } = load();
  const all = ['a', 'b', ''];
  let s = toggleValue(null, all, 'b', false);
  assert.deepStrictEqual(Array.from(s), ['a', '']);
  s = toggleValue(s, all, '', false);
  assert.deepStrictEqual(Array.from(s), ['a']);
  s = toggleValue(s, all, '', true);
  s = toggleValue(s, all, 'b', true);
  assert.strictEqual(s, null);
});

test('clear column keeps other filters and the sort; clear all resets every filter and restores EoL date ascending', () => {
  const ctx = load();
  const start = ctx.clearedState(null);
  assert.deepStrictEqual(plain(start.sort), { key: 'eol', dir: 'asc' });
  start.filters.version.selected = ['3.9'];
  Object.assign(start.filters.eol, { mode: 'range', start: '2025-01-01', startText: '2025-01-01', search: 'x' });
  start.sort = { key: 'repos', dir: 'desc' };
  const one = ctx.clearedState(start, 'eol');
  assert.deepStrictEqual(plain(one.filters.eol), plain(ctx.emptyFilter()), 'constraint, drafts and list search all cleared');
  assert.deepStrictEqual(Array.from(one.filters.version.selected), ['3.9']);
  assert.deepStrictEqual(plain(one.sort), { key: 'repos', dir: 'desc' });
  assert.strictEqual(start.filters.eol.startText, '2025-01-01', 'the previous state is not mutated');
  const all = ctx.clearedState(one);
  for (const k of Object.keys(DEFAULTS)) assert.deepStrictEqual(plain(all.filters[k]), plain(ctx.emptyFilter()), k);
  assert.deepStrictEqual(plain(all.sort), { key: 'eol', dir: 'asc' });
});

test('stale stored keys (old dates, filters) never leak into column prefs; filters are never stored', () => {
  const calls = [];
  const ctx = load({ localStorage: { getItem: () => null, setItem: (...a) => calls.push(a), removeItem: (...a) => calls.push(a) } });
  const p = plain(ctx.tablePrefs(JSON.stringify({ start: '2026-11-20', end: '2026-11-20', filters: { eol: { start: '2026-11-20' } },
    columns: { eol: { visible: false, width: 150, start: '2026-11-20' } } })));
  assert.deepStrictEqual(p.eol, { visible: false, width: 150 });
  assert.deepStrictEqual(p.version, DEFAULTS.version);
  ctx.clearedState(null);
  assert.deepStrictEqual(calls, []);
});

test('menu box stays inside the viewport: flips above a trigger near the bottom, scrolls itself when taller than the room', () => {
  const { menuBox } = load();
  const inside = (b, h, vh) => b.top >= 8 && b.top + Math.min(h, b.maxHeight) <= vh - 8;
  // RED case: 400x400 viewport, trigger at y 364.8..384.5
  for (const h of [100, 300, 500]) {
    const b = menuBox(364.8, 384.5, h, 400);
    assert.ok(inside(b, h, 400), `height ${h}: ${JSON.stringify(b)}`);
    assert.ok(b.top + Math.min(h, b.maxHeight) <= 364.8, 'above the trigger, not covering it');
  }
  const low = menuBox(40, 60, 200, 800);
  assert.deepStrictEqual(plain(low), { top: 64, maxHeight: 728 }, 'enough room below: opens below');
  for (const [t, b] of [[-200, -180], [900, 920], [390, 410]]) {
    for (const h of [50, 1000]) assert.ok(inside(menuBox(t, b, h, 400), h, 400), `trigger ${t}..${b} height ${h}`);
  }
});

test('storage uses only its own key', () => {
  const calls = [];
  const store = { getItem: (k) => { calls.push(['get', k]); return null; }, setItem: (k, v) => calls.push(['set', k, v]), removeItem: (k) => calls.push(['remove', k]) };
  const ctx = load({ localStorage: store });
  ctx.tableStorage('getItem'); ctx.tableStorage('setItem', '{}'); ctx.tableStorage('removeItem');
  assert.deepStrictEqual(calls, [['get', 'eol-ledger.dependencies-table.v1'], ['set', 'eol-ledger.dependencies-table.v1', '{}'], ['remove', 'eol-ledger.dependencies-table.v1']]);
});
