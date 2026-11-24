// Pure-logic checks for the dashboard column prefs in app.js (run: node --test src/test/js/app.test.js). Browser behaviour is proven separately.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/app.js'), 'utf8');

function load(window, document) {
  const ctx = vm.createContext({ window: window || {}, document: document || { addEventListener() {}, getElementById() { return null; } },
    URL, Option: window && window.Option });
  vm.runInContext(source, ctx);
  return ctx;
}

test('column order normalizes corrupt, duplicate, unknown and missing identities', () => {
  const { columnOrder } = load();
  const keys = ['name', 'version', 'date'];
  for (const text of [null, '', 'bad', '{}', 'null', '42']) assert.deepStrictEqual(plain(columnOrder(text, keys)), keys);
  assert.deepStrictEqual(plain(columnOrder('["date","date","unknown","name"]', keys)), ['date', 'name', 'version']);
  assert.deepStrictEqual(plain(columnOrder('["__proto__",0,"version"]', keys)), ['version', 'name', 'date']);
});

const plain = (o) => JSON.parse(JSON.stringify(o));
const DEFAULTS = {
  ecosystem: { visible: true, width: 140 },
  dependency: { visible: true, width: 220 }, version: { visible: true, width: 120 },
  eol: { visible: true, width: 130 }, status: { visible: true, width: 210 },
  repos: { visible: true, width: 120 }, branches: { visible: true, width: 120 },
  purl: { visible: true, width: 280 }, context: { visible: true, width: 240 }, cycle: { visible: true, width: 200 },
  evidence: { visible: true, width: 280 }, source: { visible: true, width: 260 }, sbom: { visible: true, width: 260 },
};
const DEFAULT_ORDER = ['ecosystem', 'dependency', 'version', 'eol', 'status', 'repos', 'branches', 'purl', 'context', 'cycle', 'evidence', 'source', 'sbom'];

test('shared columns default to ecosystem first and Package URL after the branch count; fresh order follows it and complete saved orders are kept', () => {
  const { TABLE_COLUMNS, tablePrefs, columnOrder } = load();
  assert.deepStrictEqual(plain(Object.keys(TABLE_COLUMNS)), DEFAULT_ORDER);
  assert.deepStrictEqual(Object.keys(plain(tablePrefs(null))), DEFAULT_ORDER);
  const keys = Object.keys(TABLE_COLUMNS);
  assert.deepStrictEqual(plain(columnOrder(null, keys)), DEFAULT_ORDER);
  // the server-rendered dependency tables draw the same sequence: shared col/th, branch rows, dashboard rows
  const templates = path.join(__dirname, '../../main/resources/templates');
  const fragments = fs.readFileSync(path.join(templates, 'fragments.html'), 'utf8');
  const dashboard = fs.readFileSync(path.join(templates, 'dashboard.html'), 'utf8');
  const columns = (html, from, to) => { const s = html.indexOf(from); return [...html.slice(s, html.indexOf(to, s)).matchAll(/data-column="([a-z]+)"/g)].map(m => m[1]); };
  assert.deepStrictEqual(columns(fragments, '<colgroup>', '</colgroup>'), DEFAULT_ORDER, 'colgroup');
  assert.deepStrictEqual(columns(fragments, '<thead>', '</thead>'), DEFAULT_ORDER, 'header');
  assert.deepStrictEqual(columns(fragments, 'th:fragment="inventoryRows"', '</tr>'), DEFAULT_ORDER, 'branch rows');
  assert.deepStrictEqual(columns(dashboard, 'th:fragment="dashboardRows"', '</tr>'), DEFAULT_ORDER, 'dashboard rows');
  const old = ['dependency', 'version', 'eol', 'status', 'repos', 'branches', 'context', 'purl', 'cycle', 'evidence', 'source', 'sbom'];
  assert.deepStrictEqual(plain(columnOrder(JSON.stringify(old), keys)), old.concat('ecosystem'), 'saved 12-column order kept, ecosystem appended');
  const custom = ['sbom', 'eol', 'dependency', 'purl', 'version', 'status', 'repos', 'branches', 'context', 'cycle', 'evidence', 'source'];
  assert.deepStrictEqual(plain(columnOrder(JSON.stringify(custom), keys)), custom.concat('ecosystem'));
  const withEco = ['ecosystem'].concat(custom);
  assert.deepStrictEqual(plain(columnOrder(JSON.stringify(withEco), keys)), withEco, 'complete 13-column order kept');
});

test('saved 12-column prefs keep their widths and visibility; ecosystem joins visible at 140px', () => {
  const { tablePrefs } = load();
  const columns = {};
  for (const key of DEFAULT_ORDER.filter((k) => k !== 'ecosystem')) columns[key] = { visible: key !== 'purl', width: 333 };
  const p = plain(tablePrefs(JSON.stringify({ columns })));
  for (const key of Object.keys(columns)) assert.deepStrictEqual(p[key], columns[key]);
  assert.deepStrictEqual(p.ecosystem, { visible: true, width: 140 });
});

test('missing or malformed storage falls back to all shared columns visible at default widths', () => {
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

test('old six-column dashboard preferences survive and new columns default visible', () => {
  const { tablePrefs } = load();
  const columns = {};
  for (const key of ['dependency', 'version', 'eol', 'status', 'repos', 'branches']) columns[key] = { visible: false, width: 333 };
  const p = plain(tablePrefs(JSON.stringify({ columns })));
  for (const key of Object.keys(columns)) assert.deepStrictEqual(p[key], columns[key]);
  for (const key of ['context', 'purl', 'cycle', 'evidence', 'source', 'sbom']) assert.deepStrictEqual(p[key], DEFAULTS[key]);
});

test('branch table preferences use a separate stable key and reset does not remove dashboard preferences', () => {
  const saved = new Map([['eol-ledger.dependencies-table.v1', 'dashboard'], ['other', 'unrelated']]);
  const ctx = load({ localStorage: { getItem: k => saved.get(k), setItem: (k,v) => saved.set(k,v), removeItem: k => saved.delete(k) } });
  ctx.tableStorage('setItem', 'branch', ctx.BRANCH_TABLE_KEY);
  assert.strictEqual(ctx.tableStorage('getItem', null, ctx.BRANCH_TABLE_KEY), 'branch');
  ctx.tableStorage('removeItem', null, ctx.BRANCH_TABLE_KEY);
  assert.deepStrictEqual([...saved], [['eol-ledger.dependencies-table.v1', 'dashboard'], ['other', 'unrelated']]);
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

test('semantic visibility preferences ignore malformed, unknown and unsafe values; all-hidden recovers', () => {
  const { visibilityPrefs } = load();
  const keys = ['Package', 'Project context', 'EoL date'];
  const defaults = { Package: true, 'Project context': true, 'EoL date': true };
  for (const text of [null, '', 'bad', 'null', '[]', 'false', '{"Package":"false"}', '{"unknown":false}']) {
    assert.deepStrictEqual(plain(visibilityPrefs(text, keys)), defaults);
  }
  assert.deepStrictEqual(plain(visibilityPrefs('{"Package":false,"Project context":false,"EoL date":false}', keys)), defaults);
  assert.deepStrictEqual(plain(visibilityPrefs('{"Package":false,"unknown":false,"__proto__":{"visible":false}}', keys)),
    { Package: false, 'Project context': true, 'EoL date': true });
  assert.deepStrictEqual(plain(visibilityPrefs('{"EoL date":false}', ['Package', 'EoL date'])),
    { Package: true, 'EoL date': false }, 'optional context column does not shift a saved preference');
});

test('visibility storage is scoped by stable table role and tolerates blocked storage', () => {
  const table = (id, noun) => ({ id, getAttribute: () => noun });
  const writes = [];
  const ctx = load({ localStorage: {
    getItem: key => key, setItem: (key, value) => writes.push([key, value]), removeItem: key => writes.push([key]),
  } });
  const repos = table('code-repos-table', 'code repo branches');
  const deps = table('', 'dependencies');
  assert.notStrictEqual(ctx.visibilityKey(repos), ctx.visibilityKey(deps));
  assert.strictEqual(ctx.visibilityKey(deps), ctx.visibilityKey(table('', 'dependencies')));
  assert.strictEqual(ctx.visibilityStorage(deps, 'getItem'), ctx.visibilityKey(deps));
  ctx.visibilityStorage(deps, 'setItem', '{}');
  ctx.visibilityStorage(deps, 'removeItem');
  assert.deepStrictEqual(writes, [[ctx.visibilityKey(deps), '{}'], [ctx.visibilityKey(deps)]]);
  const blocked = {};
  Object.defineProperty(blocked, 'localStorage', { get() { throw new Error('SecurityError'); } });
  for (const method of ['getItem', 'setItem', 'removeItem']) {
    assert.strictEqual(load(blocked).visibilityStorage(deps, method, '{}'), null);
  }
});

test('header floor measures the full sort label without a filter on empty tables', () => {
  const sort = { textContent: '# CODE REPO BRANCHES' }, filter = {};
  let start, end, measurements = 0;
  const document = { addEventListener() {}, getElementById() { return null; }, createRange() {
    return {
      setStartBefore(node) { start = node; },
      setEndAfter(node) { end = node; },
      getBoundingClientRect() { measurements++; return { width: end === filter ? 231.5 : 211.5 }; },
    };
  } };
  const { headerFloor } = load({ getComputedStyle: () => ({ paddingLeft: '12px', paddingRight: '14px' }) }, document);
  const cell = { hidden: false, querySelector: selector => selector === '.th-sort' ? sort : null };
  assert.strictEqual(headerFloor(cell), 240, 'full sort label range plus both paddings and safety margin');
  assert.strictEqual(start, sort);
  assert.strictEqual(end, sort, 'range includes the entire sort button');
  cell.querySelector = selector => selector === '.th-sort' ? sort : filter;
  assert.strictEqual(headerFloor(cell), 240, 'the filter sits at the right inside the reserved right padding, so only the sort label is measured');
  assert.strictEqual(end, sort);
  cell.hidden = true;
  assert.strictEqual(headerFloor(cell), 80);
  assert.strictEqual(measurements, 2, 'hidden cells are not measured');
  cell.hidden = false;
  cell.querySelector = () => null;
  assert.strictEqual(headerFloor(cell), 80, 'missing sort button remains guarded');
  cell.querySelector = () => sort;
  delete document.createRange;
  assert.strictEqual(headerFloor(cell), 80, 'missing Range API remains guarded');
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

test('typed sort does not depend on the browser default locale (a Swedish default sorts ä after z)', () => {
  const swedishDefault = { Collator: function (locales, options) { return new Intl.Collator(locales === undefined ? 'sv' : locales, options); } };
  const ctx = vm.createContext({ window: {}, document: { addEventListener() {}, getElementById() { return null; } }, URL, Intl: swedishDefault });
  vm.runInContext(source, ctx);
  assert.deepStrictEqual(order(ctx, ['ä', 'z'], 'text', 'asc'), ['ä', 'z']);
  assert.deepStrictEqual(order(ctx, ['ä', 'z'], 'text', 'desc'), ['z', 'ä']);
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
  for (const k of Object.keys(ctx.COLUMN_TYPES)) assert.deepStrictEqual(plain(all.filters[k]), plain(ctx.emptyFilter()), k);
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

// ---- pagination ----

test('page range: 20/50/100 per page, last partial page, an empty table is one empty page', () => {
  const { pageRange } = load();
  const r = (n, page, size) => plain(pageRange(n, page, size));
  assert.deepStrictEqual(r(0, 1, 20), { page: 1, pages: 1, start: 0, end: 0 });
  assert.deepStrictEqual(r(1, 1, 20), { page: 1, pages: 1, start: 0, end: 1 });
  assert.deepStrictEqual(r(20, 1, 20), { page: 1, pages: 1, start: 0, end: 20 });
  assert.deepStrictEqual(r(21, 1, 20), { page: 1, pages: 2, start: 0, end: 20 });
  assert.deepStrictEqual(r(21, 2, 20), { page: 2, pages: 2, start: 20, end: 21 });
  assert.deepStrictEqual(r(50, 3, 20), { page: 3, pages: 3, start: 40, end: 50 });
  assert.deepStrictEqual(r(50, 1, 50), { page: 1, pages: 1, start: 0, end: 50 });
  assert.deepStrictEqual(r(100, 1, 100), { page: 1, pages: 1, start: 0, end: 100 });
  assert.deepStrictEqual(r(101, 2, 100), { page: 2, pages: 2, start: 100, end: 101 });
  assert.deepStrictEqual(r(101, 3, 50), { page: 3, pages: 3, start: 100, end: 101 });
});

test('page range: any whole number of rows from 1 to 1000 is a page size', () => {
  const { pageRange } = load();
  const r = (n, page, size) => plain(pageRange(n, page, size));
  assert.deepStrictEqual(r(45, 2, 7), { page: 2, pages: 7, start: 7, end: 14 });
  assert.deepStrictEqual(r(45, 9, 37), { page: 2, pages: 2, start: 37, end: 45 }, 'clamped to the last page');
  assert.deepStrictEqual(r(300, 4, 75), { page: 4, pages: 4, start: 225, end: 300 });
  assert.deepStrictEqual(r(1200, 2, 250), { page: 2, pages: 5, start: 250, end: 500 });
  assert.deepStrictEqual(r(1200, 2, 1000), { page: 2, pages: 2, start: 1000, end: 1200 });
  assert.deepStrictEqual(r(3, 1, 1), { page: 1, pages: 3, start: 0, end: 1 });
});

test('page range: out-of-range pages clamp, sizes outside whole 1..1000 fall back to 20', () => {
  const { pageRange } = load();
  const r = (n, page, size) => plain(pageRange(n, page, size));
  assert.deepStrictEqual(r(21, 5, 20), { page: 2, pages: 2, start: 20, end: 21 }, 'past the end after results shrink');
  assert.deepStrictEqual(r(21, 0, 20), r(21, 1, 20));
  assert.deepStrictEqual(r(21, -3, 20), r(21, 1, 20));
  assert.deepStrictEqual(r(21, NaN, 20), r(21, 1, 20));
  assert.deepStrictEqual(r(0, 4, 100), { page: 1, pages: 1, start: 0, end: 0 });
  for (const bad of [0, -20, 7.5, 1001, 1e9, NaN, Infinity, '50', undefined, null]) assert.deepStrictEqual(r(45, 1, bad), r(45, 1, 20), String(bad));
});

test('a page holds the filtered and sorted rows of the whole table, not of the previous page', () => {
  const ctx = load();
  // 101 rows: versions 1..101 in reverse source order; keep only odd versions (51 matches), sort ascending, 20 a page.
  const rows = Array.from({ length: 101 }, (_, i) => ({ version: String(101 - i) }));
  const filters = { version: Object.assign(ctx.emptyFilter(), { selected: rows.map((r) => r.version).filter((v) => v % 2) }) };
  const shown = Array.from(ctx.visibleRows(rows, filters, { version: 'number' }));
  const matching = Array.from(ctx.sortOrder(rows.map((r) => r.version), 'number', 'asc')).filter((i) => shown[i]);
  assert.strictEqual(matching.length, 51);
  const page = (p) => { const g = ctx.pageRange(matching.length, p, 20); return matching.slice(g.start, g.end).map((i) => rows[i].version); };
  assert.deepStrictEqual(page(1).slice(-1).concat(page(2).slice(0, 1)), ['39', '41'], 'page 1 ends and page 2 starts in sorted order');
  assert.deepStrictEqual(page(3), ['81', '83', '85', '87', '89', '91', '93', '95', '97', '99', '101']);
  assert.deepStrictEqual([].concat(page(1), page(2), page(3)), matching.map((i) => rows[i].version), 'pages partition every match once');
});

// ---- copy a cell's value ----

test('popup point stays 8px inside the viewport', () => {
  const { popupAt } = load();
  assert.deepStrictEqual(plain(popupAt(100, 200, 180, 60, 1280, 800)), { left: 100, top: 200 });
  assert.deepStrictEqual(plain(popupAt(1270, 790, 180, 60, 1280, 800)), { left: 1092, top: 732 }, 'bottom-right corner flips inside');
  assert.deepStrictEqual(plain(popupAt(-5, -5, 180, 60, 400, 800)), { left: 8, top: 8 });
  assert.deepStrictEqual(plain(popupAt(50, 50, 500, 60, 400, 800)), { left: 8, top: 50 }, 'wider than the viewport: pinned left');
});

/** A document whose execCommand('copy') returns `copies` (or throws), recording the textarea it was given. */
function fakeDoc(copies) {
  const seen = { appended: [], removed: 0, selected: '' };
  const doc = {
    addEventListener() {}, getElementById() { return null; },
    createElement() {
      return { value: '', style: {}, setAttribute() {}, focus() {}, select() { seen.selected = this.value; }, remove() { seen.removed++; } };
    },
    execCommand(cmd) { seen.cmd = cmd; if (copies === 'throw') throw new Error('nope'); return copies; },
  };
  const host = { appendChild(el) { seen.appended.push(el); } };
  return { doc, host, seen };
}

test('copy: execCommand copy on a temporary textarea, true only when it really copied; nothing left behind', async () => {
  const { doc, host, seen } = fakeDoc(true);
  const ctx = load({ navigator: {} }, doc);
  assert.strictEqual(await ctx.copyText('a <b> & "c"', host), true);
  assert.strictEqual(seen.cmd, 'copy');
  assert.strictEqual(seen.selected, 'a <b> & "c"', 'the exact plain text');
  assert.strictEqual(seen.removed, 1);
});

test('copy: no clipboard API (plain-HTTP LAN page) and execCommand refusing or throwing is reported as a failure', async () => {
  for (const copies of [false, 'throw']) {
    const { doc, host, seen } = fakeDoc(copies);
    assert.strictEqual(await load({ navigator: {} }, doc).copyText('x', host), false, String(copies));
    assert.strictEqual(seen.removed, 1);
  }
});

test('copy: the Clipboard API is the fallback when execCommand fails, and its rejection is a failure too', async () => {
  const writes = [];
  const ok = fakeDoc(false);
  assert.strictEqual(await load({ navigator: { clipboard: { writeText: async (t) => { writes.push(t); } } } }, ok.doc).copyText('v1', ok.host), true);
  assert.deepStrictEqual(writes, ['v1']);
  const denied = fakeDoc(false);
  assert.strictEqual(await load({ navigator: { clipboard: { writeText: async () => { throw new Error('denied'); } } } }, denied.doc).copyText('v2', denied.host), false);
});

test('full header sorts once for padding or native button, never other controls', () => {
  const ctx = load();
  const handlers = [];
  const cell = { addEventListener: (name, fn) => handlers.push(fn) };
  let toggles = 0;
  ctx.headerSort(cell, () => toggles++);
  cell.contains = control => control === cell.control;
  cell.control = {};
  for (const excluded of [false, false, true, true, true, true]) handlers[0]({ target: { closest: selector => selector === 'th' ? cell : excluded ? cell.control : null } });
  assert.strictEqual(toggles, 2);
  assert.strictEqual(handlers.length, 1);
});

function columnFixture(saved = new Map()) {
  function node(key) {
    const events = {}, classes = new Set();
    return { key, hidden: false, style: {}, events, attrs: {}, w: 120,
      classList: { add(...names) { names.forEach(n => classes.add(n)); }, remove(...names) { names.forEach(n => classes.delete(n)); }, toggle(n, on) { if (on) classes.add(n); else classes.delete(n); }, contains(n) { return classes.has(n); } },
      addEventListener(name, fn) { (events[name] ||= []).push(fn); },
      emit(name, e = {}) { for (const fn of events[name] || []) fn(e); },
      getAttribute(name) { return name === 'data-column' || name === 'data-label' ? key : this.attrs[name] || null; },
      setAttribute(name, value) { this.attrs[name] = value; }, hasAttribute() { return true; },
      append(...children) { (this.children ||= []).push(...children); },
      appendChild(child) { (this.children ||= []).push(child); },
      // laid out by current DOM order over visible siblings, ignoring transforms (the untransformed layout)
      getBoundingClientRect() {
        let left = 0;
        for (const c of (this.parentNode && this.parentNode.cells) || []) { if (c === this) break; if (!c.hidden) left += c.w; }
        return { left, right: left + this.w, width: this.w, top: 0, bottom: 30 };
      },
      closest(selector) { return selector === 'th' ? this : null; },
      querySelector(selector) { return selector === '.th-sort' ? this.button : (this.children || []).find(c => c.tagName === selector) || null; },
      get childNodes() { return this.children || []; },
      querySelectorAll() { return []; },
      matches() { return true; }, focus() {},
      setPointerCapture() { this.captured = true; }, hasPointerCapture() { return this.captured; }, releasePointerCapture() { this.captured = false; },
    };
  }
  function group(nodes) {
    const parent = node(''); parent.cells = parent.children = nodes;
    parent.appendChild = child => { const i = nodes.indexOf(child); if (i >= 0) nodes.splice(i, 1); nodes.push(child); child.parentNode = parent; };
    nodes.forEach(n => n.parentNode = parent);
    return parent;
  }
  const heads = ['name', 'version', 'date'].map(node);
  heads.forEach(n => { n.button = node(n.key); n.button.closest = selector => selector === 'th' ? n : null; });
  const head = group(heads.slice()), body = group(['A', '1', '2026'].map(node)), cols = group(['name', 'version', 'date'].map(node));
  const tableClasses = new Set();
  const table = { id: 'fixture', dataset: {}, style: {}, offsetWidth: 360,
    classList: { add(n) { tableClasses.add(n); }, remove(n) { tableClasses.delete(n); }, contains(n) { return tableClasses.has(n); } },
    tHead: { rows: [head] }, tBodies: [{ rows: [body] }], rows: [head, body], before(settings) { this.settings = settings; },
    closest() { return null; }, getAttribute() { return null; }, querySelectorAll: selector => selector === 'colgroup' ? [cols] : [], };
  const win = node('window');
  win.localStorage = { getItem: k => saved.get(k), setItem: (k, v) => saved.set(k, v), removeItem: k => saved.delete(k) };
  const created = [];
  const docEvents = node('document');
  const doc = { events: docEvents.events, addEventListener: docEvents.addEventListener, emit: docEvents.emit,
    // doc.hit simulates whatever paints on top (overlay, or a transformed header); otherwise the visible header under (x, y)
    elementFromPoint(x, y) {
      if ('hit' in this) return this.hit;
      return heads.find(h => { const r = h.getBoundingClientRect(); return !h.hidden && x >= r.left && x < r.right && y >= r.top && y < r.bottom; }) || null;
    }, createElement(tag) { const n = node(''); n.tagName = tag; created.push(n); return n; },
    createElementNS(ns, tag) { return this.createElement(tag); } };
  const ctx = load(win, doc);
  ctx.initColumnOrder(table);
  const pointer = (target, x, y = 0) => ({ target, button: 0, pointerId: 1, clientX: x, clientY: y, preventDefault() {} });
  return { ctx, table, head, body, cols, heads, saved, win, doc, pointer, created };
}

test('outer popup dialog allows header padding and sort button sorting and pointer reorder', () => {
  for (const button of [false, true]) {
    const f = columnFixture(), outerDialog = {};
    f.heads.forEach(cell => {
      cell.contains = control => control === cell || control === cell.button;
      cell.closest = selector => selector === 'th' ? cell : outerDialog;
      cell.button.closest = selector => selector === 'th' ? cell : outerDialog;
    });
    const cell = f.heads[0], target = button ? cell.button : cell;
    let sorts = 0;
    f.ctx.headerSort(cell, () => sorts++);
    cell.emit('click', { target });
    assert.strictEqual(sorts, 1, 'outer dialog must not block sorting');
    f.head.emit('pointerdown', f.pointer(target, 0));
    assert.strictEqual(cell.captured, true, 'outer dialog must not block drag start');
    f.head.emit('pointermove', f.pointer(target, 260));
    assert.ok(cell.classList.contains('column-dragging'));
    f.head.emit('pointerup', f.pointer(target, 260));
    assert.deepStrictEqual(f.head.cells.map(n => n.key), ['version', 'date', 'name'], 'outer dialog must not block drop target');
  }
});

test('inner filter dialog padding and controls still block header sorting and drag start', () => {
  for (const kind of ['dialog padding', 'filter button', 'filter SVG', 'input', 'select', 'textarea', 'link', 'label', 'summary', 'resize']) {
    const f = columnFixture(), cell = f.heads[0], control = {};
    cell.contains = node => node === control;
    const target = { closest: selector => selector === 'th' ? cell : control };
    let sorts = 0;
    f.ctx.headerSort(cell, () => sorts++);
    cell.emit('click', { target });
    assert.strictEqual(sorts, 0, kind);
    f.head.emit('pointerdown', f.pointer(target, 0));
    assert.ok(!cell.captured, kind);
    f.head.emit('pointermove', f.pointer(target, 20));
    f.head.emit('pointerup', f.pointer(target, 20));
    assert.deepStrictEqual(f.head.cells.map(n => n.key), ['name', 'version', 'date'], kind);
  }
});

test('drag moves original header, body and col nodes together without changing values or widths; reset restores', () => {
  const f = columnFixture();
  const original = f.body.cells.slice(); original[0].style.width = '333px'; original[0].hidden = true;
  f.head.emit('pointerdown', f.pointer(f.heads[0], 0));
  f.head.emit('pointermove', f.pointer(f.heads[0], 260));
  f.head.emit('pointerup', f.pointer(f.heads[0], 260));
  assert.deepStrictEqual(f.head.cells.map(n => n.key), ['version', 'date', 'name']);
  assert.deepStrictEqual(f.cols.cells.map(n => n.key), ['version', 'date', 'name']);
  assert.deepStrictEqual(f.body.cells, [original[1], original[2], original[0]]);
  assert.strictEqual(f.body.cells[2].style.width, '333px');
  assert.strictEqual(f.body.cells[2].hidden, true);
  assert.strictEqual(f.body.cells[2].originalColumnIndex, 0);
  let prevented = false;
  f.head.emit('click', { preventDefault() { prevented = true; }, stopImmediatePropagation() {} });
  assert.ok(prevented, 'post-drag click cannot sort');
  f.ctx.resetColumnOrder(f.table);
  assert.deepStrictEqual(f.body.cells, original);
  assert.strictEqual(f.saved.size, 0);
});

test('accessible positions follow moves of other columns and reset', () => {
  const f = columnFixture();
  const right = cell => f.head.emit('keydown', { key: 'ArrowRight', altKey: true, target: cell.button, preventDefault() {} });
  const accurate = () => f.head.cells.forEach((cell, i) => {
    assert.strictEqual(cell.button.attrs['aria-label'], `${cell.key}, column ${i + 1} of 3. Alt+Left or Alt+Right to move`);
    assert.strictEqual(cell.button.attrs['aria-keyshortcuts'], 'Alt+ArrowLeft Alt+ArrowRight');
  });
  right(f.heads[0]);
  right(f.heads[1]);
  accurate();
  right(f.heads[0]);
  f.ctx.resetColumnOrder(f.table);
  accurate();
});

test('accessible positions are accurate on initial and persisted layouts', () => {
  for (const order of [null, '["date","name","version"]']) {
    const f = columnFixture(new Map(order ? [['eol-ledger.table-order.v1.fixture', order]] : []));
    f.head.cells.forEach((cell, i) => assert.strictEqual(cell.button.attrs['aria-label'], `${cell.key}, column ${i + 1} of 3. Alt+Left or Alt+Right to move`));
  }
});

for (const distance of [2, 20]) {
  test(`document Escape cancels a ${distance}px pending drag outside the header and recovers clicks`, () => {
    const f = columnFixture();
    f.head.emit('pointerdown', f.pointer(f.heads[0], 0));
    f.head.emit('pointermove', f.pointer(f.heads[0], distance));
    f.doc.emit('keydown', { key: 'Escape', target: f.doc });
    assert.strictEqual(f.heads[0].captured, false, 'Escape releases pointer capture');
    assert.ok(f.heads.every(h => !h.classList.contains('column-dragging') && !h.classList.contains('column-target')), 'Escape clears drag markers');
    f.head.emit('pointerup', f.pointer(f.heads[0], distance));
    assert.deepStrictEqual(f.head.cells.map(n => n.key), ['name', 'version', 'date']);
    const stopped = detail => {
      let blocked = false;
      f.head.emit('click', { detail, preventDefault() {}, stopImmediatePropagation() { blocked = true; } });
      return blocked;
    };
    assert.strictEqual(stopped(0), false, 'keyboard sort is allowed');
    assert.strictEqual(stopped(1), true, 'trailing pointer click is suppressed');
    f.head.emit('pointerdown', f.pointer(f.heads[0], 0));
    f.head.emit('pointermove', f.pointer(f.heads[0], 2));
    f.head.emit('pointerup', f.pointer(f.heads[0], 2));
    assert.strictEqual(stopped(1), false, 'fresh small gesture remains click-to-sort');
    f.doc.emit('keydown', { key: 'Escape', target: f.doc });
    assert.strictEqual(stopped(1), false, 'idle Escape cannot suppress a click');
  });
}

test('cancelled drags do not suppress keyboard sort clicks', () => {
  for (const cancellation of ['Escape', 'scroll', 'pointercancel', 'lostpointercapture']) {
    const f = columnFixture();
    f.head.emit('pointerdown', f.pointer(f.heads[0], 0));
    f.head.emit('pointermove', f.pointer(f.heads[0], 20));
    if (cancellation === 'Escape') f.doc.emit('keydown', { key: 'Escape' });
    else if (cancellation === 'scroll') f.win.emit('scroll');
    else f.head.emit(cancellation);
    let stopped = false, sorts = 0;
    const click = { detail: 0, target: f.heads[0].button, preventDefault() {}, stopImmediatePropagation() { stopped = true; } };
    f.ctx.headerSort(f.heads[0], () => sorts++);
    f.head.emit('click', click);
    if (!stopped) f.heads[0].emit('click', click);
    assert.strictEqual(sorts, 1, `${cancellation}: keyboard click must sort`);
    let pointerStopped = false;
    f.head.emit('click', { detail: 1, preventDefault() {}, stopImmediatePropagation() { pointerStopped = true; } });
    assert.ok(pointerStopped, `${cancellation}: trailing pointer click must still be suppressed`);
  }
});

test('threshold, cancellation and keyboard reorder preserve independent stored preferences', () => {
  const saved = new Map([['unrelated', 'keep'], ['eol-ledger.table-widths.v1', '{"fixture":{"0":333}}']]);
  const f = columnFixture(saved);
  f.head.emit('pointerdown', f.pointer(f.heads[0], 0));
  f.head.emit('pointermove', f.pointer(f.heads[0], 3));
  f.head.emit('pointerup', f.pointer(f.heads[0], 3));
  assert.strictEqual(f.head.cells[0], f.heads[0]);
  f.head.emit('pointerdown', f.pointer(f.heads[0], 0));
  f.head.emit('pointermove', f.pointer(f.heads[0], 20));
  f.win.emit('scroll');
  f.head.emit('pointerup', f.pointer(f.heads[0], 20));
  assert.strictEqual(f.head.cells[0], f.heads[0]);
  f.head.emit('keydown', { key: 'ArrowRight', altKey: true, target: f.heads[0].button, preventDefault() {} });
  assert.deepStrictEqual(f.head.cells.map(n => n.key), ['version', 'name', 'date']);
  const again = columnFixture(saved);
  assert.deepStrictEqual(again.head.cells.map(n => n.key), ['version', 'name', 'date']);
  assert.strictEqual(saved.get('unrelated'), 'keep');
  assert.strictEqual(saved.get('eol-ledger.table-widths.v1'), '{"fixture":{"0":333}}');
});

test('lazy resize and visibility use original identities after restored order; reset keeps saved widths', () => {
  const saved = new Map([
    ['eol-ledger.table-order.v1.fixture', '["date","name","version"]'],
    ['eol-ledger.table-widths.v1', '{"fixture":{"0":333,"1":222}}'],
    ['eol-ledger.table-columns.v1.fixture', '{"name":false}'],
  ]);
  const f = columnFixture(saved);
  f.ctx.initResize(f.table);
  assert.strictEqual(f.heads[0].style.width, '333px');
  assert.strictEqual(f.heads[1].style.width, '222px');
  assert.strictEqual(f.heads[0].hidden, true);
  assert.strictEqual(f.body.cells.find(c => c.originalColumnIndex === 0).hidden, true);
  assert.strictEqual(f.body.cells[0].key, '2026');
  const toggle = f.created.find(n => n.attrs['data-column-toggle'] === 'version');
  toggle.checked = false; toggle.emit('change');
  assert.strictEqual(f.body.cells.find(c => c.originalColumnIndex === 1).hidden, true);
  assert.strictEqual(f.body.cells.find(c => c.originalColumnIndex === 2).hidden, false);
  const reset = f.created.find(n => Object.hasOwn(n.attrs, 'data-columns-reset'));
  reset.emit('click');
  assert.deepStrictEqual(f.head.cells.map(n => n.key), ['name', 'version', 'date']);
  assert.ok(f.body.cells.every(c => !c.hidden));
  assert.strictEqual(f.heads[0].style.width, '333px');
  assert.strictEqual(saved.get('eol-ledger.table-widths.v1'), '{"fixture":{"0":333,"1":222}}');
});

const shifts = nodes => nodes.map(n => n.style.transform || '');

test('dragging previews whole columns at the landing slot before release; drop commits exactly that layout', () => {
  const f = columnFixture();
  const moved = ['translateX(240px)', 'translateX(-120px)', 'translateX(-120px)'];
  f.head.emit('pointerdown', f.pointer(f.heads[0], 10));
  f.head.emit('pointermove', f.pointer(f.heads[0], 260));
  assert.deepStrictEqual(shifts(f.heads), moved);
  assert.deepStrictEqual(shifts(f.body.cells), moved);
  assert.deepStrictEqual(shifts(f.cols.cells), ['', '', '']);
  assert.deepStrictEqual(f.head.cells.map(n => n.key), ['name', 'version', 'date'], 'no DOM moves during preview');
  assert.deepStrictEqual(f.body.cells.map(n => n.key), ['A', '1', '2026']);
  assert.strictEqual(f.saved.size, 0, 'no writes during preview');
  assert.ok(f.table.classList.contains('column-previewing'));
  f.doc.hit = f.heads[0]; // stationary pointer now over the transformed source header: slot must not change
  f.head.emit('pointermove', f.pointer(f.heads[0], 260));
  assert.deepStrictEqual(shifts(f.heads), moved);
  f.head.emit('pointerup', f.pointer(f.heads[0], 260));
  assert.deepStrictEqual(f.head.cells.map(n => n.key), ['version', 'date', 'name']);
  assert.deepStrictEqual(f.body.cells.map(n => n.key), ['1', '2026', 'A']);
  assert.ok([...f.heads, ...f.body.cells].every(n => !n.style.transform));
  assert.ok(!f.table.classList.contains('column-previewing') && !f.heads[0].classList.contains('column-dragging'));
  assert.strictEqual(f.saved.get('eol-ledger.table-order.v1.fixture'), '["version","date","name"]');
});

test('leftward preview uses unequal widths, hidden columns take no space, invalid release commits nothing', () => {
  const f = columnFixture();
  f.heads[0].w = 100; f.heads[2].w = 200; f.heads[1].hidden = f.body.cells[1].hidden = true; // name 0..100, date 100..300
  f.head.emit('pointerdown', f.pointer(f.heads[2], 150));
  f.head.emit('pointermove', f.pointer(f.heads[2], 50));
  assert.deepStrictEqual(shifts(f.heads), ['translateX(200px)', 'translateX(200px)', 'translateX(-100px)']);
  assert.deepStrictEqual(shifts(f.body.cells), ['translateX(200px)', 'translateX(200px)', 'translateX(-100px)']);
  f.head.emit('pointermove', f.pointer(f.heads[2], 150));
  assert.deepStrictEqual(shifts(f.heads), ['', '', ''], 'own slot previews nothing');
  f.head.emit('pointermove', f.pointer(f.heads[2], 50));
  f.head.emit('pointermove', f.pointer(f.heads[2], 50, 100));
  assert.deepStrictEqual(shifts(f.heads), ['', '', ''], 'outside the header previews nothing');
  f.head.emit('pointermove', f.pointer(f.heads[2], 50));
  f.head.emit('pointerup', f.pointer(f.heads[2], 50, 100));
  assert.deepStrictEqual(f.head.cells.map(n => n.key), ['name', 'version', 'date'], 'release outside never commits the stale preview');
  assert.ok([...f.heads, ...f.body.cells].every(n => !n.style.transform));
  assert.ok(!f.table.classList.contains('column-previewing'));
  assert.strictEqual(f.saved.size, 0);
});

test('overlays and header controls above the landing slot cancel the preview and the drop', () => {
  for (const kind of ['overlay', 'control']) {
    const f = columnFixture(), control = { closest: s => s === 'th' ? f.heads[2] : control };
    f.heads[2].contains = n => n === control;
    f.head.emit('pointerdown', f.pointer(f.heads[0], 10));
    f.head.emit('pointermove', f.pointer(f.heads[0], 260));
    assert.notDeepStrictEqual(shifts(f.heads), ['', '', ''], kind);
    f.doc.hit = kind === 'overlay' ? { closest: () => null } : control;
    f.head.emit('pointermove', f.pointer(f.heads[0], 260));
    assert.deepStrictEqual(shifts(f.heads), ['', '', ''], kind);
    f.head.emit('pointerup', f.pointer(f.heads[0], 260));
    assert.deepStrictEqual(f.head.cells.map(n => n.key), ['name', 'version', 'date'], kind);
    assert.strictEqual(f.saved.size, 0, kind);
  }
});

test('a header control carried by the preview over a valid slot keeps the preview; its original bounds stay invalid', () => {
  // A/B/C = 220/120/200px; A's visible filter button at local x 180..202, content height y 8..26
  function filterFixture() {
    const f = columnFixture(), [a, b, c] = f.heads;
    a.w = 220; b.w = 120; c.w = 200;
    const dx = n => Number((/translateX\((-?[\d.]+)px\)/.exec(n.style.transform || '') || [0, 0])[1]);
    // truthful like a browser: includes the transform its header is painted with
    const filter = { hidden: false, closest: s => s === 'th' ? a : filter,
      getBoundingClientRect() { const left = a.getBoundingClientRect().left + 180 + dx(a); return { left, right: left + 22, width: 22, top: 8, bottom: 26 }; } };
    a.contains = n => n === filter;
    a.querySelectorAll = () => [filter];
    // what is painted on top: the filter where it is drawn now, else the header drawn at x
    f.doc.elementFromPoint = (x, y) => {
      const r = filter.getBoundingClientRect();
      if (x >= r.left && x < r.right && y >= r.top && y < r.bottom) return filter;
      return f.heads.find(h => { const o = h.getBoundingClientRect(); return !h.hidden && x >= o.left + dx(h) && x < o.right + dx(h) && y >= o.top && y < o.bottom; }) || null;
    };
    let writes = 0;
    const set = f.win.localStorage.setItem;
    f.win.localStorage.setItem = (k, v) => { if (k === 'eol-ledger.table-order.v1.fixture') writes++; set(k, v); };
    return { f, filter, writes: () => writes };
  }

  const { f, filter, writes } = filterFixture();
  assert.strictEqual(f.doc.elementFromPoint(190, 15), filter, 'filter is visible at its original place before activation');
  assert.strictEqual(f.doc.elementFromPoint(310, 15), f.heads[1], 'x=310 is plain B padding before activation');
  f.head.emit('pointerdown', f.pointer(f.heads[0], 30, 15));
  f.head.emit('pointermove', f.pointer(f.heads[0], 310, 15));
  const landed = ['translateX(120px)', 'translateX(-220px)', ''];
  assert.deepStrictEqual(shifts(f.heads), landed);
  assert.strictEqual(f.doc.elementFromPoint(310, 15), filter, 'the preview carries A\'s filter over x=310');
  for (let i = 0; i < 3; i++) {
    f.head.emit('pointermove', f.pointer(f.heads[0], 310, 15));
    assert.deepStrictEqual(shifts(f.heads), landed, `repeat move ${i} keeps the preview`);
  }
  assert.deepStrictEqual(f.head.cells.map(n => n.key), ['name', 'version', 'date']);
  assert.strictEqual(writes(), 0);
  f.head.emit('pointerup', f.pointer(f.heads[0], 310, 15));
  assert.deepStrictEqual(f.head.cells.map(n => n.key), ['version', 'name', 'date']);
  assert.strictEqual(f.saved.get('eol-ledger.table-order.v1.fixture'), '["version","name","date"]');
  assert.strictEqual(writes(), 1, 'committed exactly once');
  assert.ok(f.heads.every(n => !n.style.transform));

  // C previews into slot A via A's plain padding, which carries the filter away; its original bounds still reject
  const g = filterFixture();
  g.f.head.emit('pointerdown', g.f.pointer(g.f.heads[2], 440, 15));
  g.f.head.emit('pointermove', g.f.pointer(g.f.heads[2], 30, 15));
  assert.deepStrictEqual(shifts(g.f.heads), ['translateX(200px)', 'translateX(200px)', 'translateX(-340px)']);
  assert.notStrictEqual(g.f.doc.elementFromPoint(190, 15), g.filter, 'filter has moved away from x=190');
  g.f.head.emit('pointermove', g.f.pointer(g.f.heads[2], 190, 15));
  assert.deepStrictEqual(shifts(g.f.heads), ['', '', ''], 'original filter bounds never accept a drop');
  g.f.head.emit('pointerup', g.f.pointer(g.f.heads[2], 190, 15));
  assert.deepStrictEqual(g.f.head.cells.map(n => n.key), ['name', 'version', 'date']);
  assert.strictEqual(g.writes(), 0);
});

test('every cancellation clears the preview with no DOM moves or writes', () => {
  for (const cancellation of ['Escape', 'scroll', 'blur', 'pointercancel', 'lostpointercapture']) {
    const f = columnFixture();
    f.head.emit('pointerdown', f.pointer(f.heads[0], 10));
    f.head.emit('pointermove', f.pointer(f.heads[0], 260));
    if (cancellation === 'Escape') f.doc.emit('keydown', { key: 'Escape' });
    else if (cancellation === 'scroll' || cancellation === 'blur') f.win.emit(cancellation);
    else f.head.emit(cancellation);
    assert.ok([...f.heads, ...f.body.cells].every(n => !n.style.transform), cancellation);
    assert.ok(!f.table.classList.contains('column-previewing'), cancellation);
    f.head.emit('pointerup', f.pointer(f.heads[0], 260));
    assert.deepStrictEqual(f.head.cells.map(n => n.key), ['name', 'version', 'date'], cancellation);
    assert.strictEqual(f.saved.size, 0, cancellation);
  }
});

test('preview cells animate transform, and the global reduced-motion rule turns transitions off', () => {
  const css = fs.readFileSync(path.join(__dirname, '../../main/resources/static/app.css'), 'utf8');
  assert.match(css, /\.column-previewing th, \.column-previewing td \{[^}]*transition: transform/);
  assert.match(css, /prefers-reduced-motion: reduce\)\s*\{\s*\* \{ transition: none !important;/);
});

test('storage uses only its own key', () => {
  const calls = [];
  const store = { getItem: (k) => { calls.push(['get', k]); return null; }, setItem: (k, v) => calls.push(['set', k, v]), removeItem: (k) => calls.push(['remove', k]) };
  const ctx = load({ localStorage: store });
  ctx.tableStorage('getItem'); ctx.tableStorage('setItem', '{}'); ctx.tableStorage('removeItem');
  assert.deepStrictEqual(calls, [['get', 'eol-ledger.dependencies-table.v1'], ['set', 'eol-ledger.dependencies-table.v1', '{}'], ['remove', 'eol-ledger.dependencies-table.v1']]);
});

test('header title is the first own non-blank text node, ignoring elements and whitespace', () => {
  const { headerTitle } = load();
  const text = (t) => ({ nodeType: 3, textContent: t });
  const title = text(' Name ');
  const el = { nodeType: 1, textContent: 'Sort' };
  assert.strictEqual(headerTitle({ childNodes: [text('\n  '), el, title, text('Other')] }), title);
  assert.strictEqual(headerTitle({ childNodes: [text(' '), el] }), undefined);
  assert.strictEqual(headerTitle({ childNodes: [] }), undefined);
});

// ---- Shareable table views: each table's sort/filters/page mirrored into ?view.<table id>=<versioned JSON> ----

const VIEW_KEYS = ['name', 'version', 'count', 'eol'];
const VIEW_TYPES = { name: 'text', version: 'version', count: 'number', eol: 'date' };
const EOL_ASC = { key: 'eol', dir: 'asc' };

test('view state: versioned JSON round-trips every mode, [] apart from null, a null sort, page and size; defaults encode to nothing', () => {
  const { encodeView, decodeView, emptyFilter } = load();
  const base = decodeView(null, VIEW_KEYS, VIEW_TYPES, EOL_ASC);
  assert.deepStrictEqual(plain(base), { filters: Object.fromEntries(VIEW_KEYS.map(k => [k, plain(emptyFilter())])), sort: EOL_ASC, page: 1, size: 20 });
  assert.strictEqual(encodeView(base, VIEW_KEYS, EOL_ASC), null);
  const view = decodeView(null, VIEW_KEYS, VIEW_TYPES, EOL_ASC);
  view.filters.name = Object.assign(emptyFilter(), { selected: [] });
  view.filters.version = Object.assign(emptyFilter(), { mode: 'text', op: 'starts-with', text: '1.' });
  view.filters.count = Object.assign(emptyFilter(), { mode: 'range', startText: '2', endText: '10', start: '2', end: '10' });
  view.filters.eol = Object.assign(emptyFilter(), { selected: ['2026-01-02', ''], search: '01', searchOp: 'exact' });
  Object.assign(view, { sort: null, page: 3, size: 50 });
  const text = encodeView(view, VIEW_KEYS, EOL_ASC);
  assert.deepStrictEqual(JSON.parse(text), { v: 1, filters: { name: { selected: [] }, version: { mode: 'text', op: 'starts-with', text: '1.' },
    count: { mode: 'range', startText: '2', endText: '10' }, eol: { selected: ['2026-01-02', ''], search: '01', searchOp: 'exact' } }, sort: null, page: 3, size: 50 });
  assert.deepStrictEqual(plain(decodeView(text, VIEW_KEYS, VIEW_TYPES, EOL_ASC)), plain(view));
  // selected: null is "every value" (the default), [] is "no value": distinct in both directions
  assert.strictEqual(decodeView('{"v":1,"filters":{"name":{"selected":null}}}', VIEW_KEYS, VIEW_TYPES, EOL_ASC).filters.name.selected, null);
  assert.deepStrictEqual(plain(decodeView('{"v":1,"filters":{"name":{"selected":[]}}}', VIEW_KEYS, VIEW_TYPES, EOL_ASC).filters.name.selected), []);
  // a table whose default is source order writes a real sort, and reads an absent one as source order
  const plainTable = decodeView('{"v":1,"sort":{"key":"name","dir":"desc"}}', VIEW_KEYS, VIEW_TYPES, null);
  assert.deepStrictEqual(plain(plainTable.sort), { key: 'name', dir: 'desc' });
  assert.strictEqual(decodeView(null, VIEW_KEYS, VIEW_TYPES, null).sort, null);
  assert.strictEqual(encodeView(decodeView(null, VIEW_KEYS, VIEW_TYPES, null), VIEW_KEYS, null), null);
});

test('view state: malformed, other-version, unknown and hostile input falls back field by field, never reaching a prototype', () => {
  const ctx = load();
  const defaults = plain(ctx.decodeView(null, VIEW_KEYS, VIEW_TYPES, EOL_ASC));
  for (const text of [undefined, '', 'x', 'null', '[]', '42', '"v"', '{}', '{"v":2,"sort":null,"page":2}', '{"v":"1","page":2}', '{"sort":null}',
    '{"v":1,"filters":[1],"sort":"name","page":"2","size":"50"}', '{"v":1,"page":0,"size":1001}', '{"v":1,"page":2.5,"size":-20}', '{"v":1,"page":-1}',
    '{"v":1,"size":0}', '{"v":1,"size":7.5}', '{"v":1,"size":"37"}', '{"v":1,"size":1e400}', '{"v":1,"size":null}',
    '{"v":1,"sort":{"key":"nope","dir":"asc"}}', '{"v":1,"sort":{"key":"name","dir":"up"}}', '{"v":1,"sort":{"key":"__proto__","dir":"asc"}}',
    '{"v":1,"filters":{"nope":{"mode":"text","text":"a"},"name":{"mode":"regex","op":"matches","searchOp":"near","selected":[1,"a"],"text":5,"search":{}}}}',
    '{"v":1,"filters":{"name":{"op":"__proto__","selected":"a"},"__proto__":{"mode":"text"},"constructor":{"mode":"text"}},"__proto__":{"page":3}}']) {
    assert.deepStrictEqual(plain(ctx.decodeView(text, VIEW_KEYS, VIEW_TYPES, EOL_ASC)), defaults, String(text));
  }
  assert.strictEqual(vm.runInContext('({}).mode === undefined && ({}).page === undefined', ctx), true);
  // valid fields survive next to invalid ones
  const mixed = ctx.decodeView('{"v":1,"filters":{"name":{"mode":"text","op":"bogus","text":"py"}},"page":2,"size":1001}', VIEW_KEYS, VIEW_TYPES, EOL_ASC);
  assert.deepStrictEqual(plain(mixed.filters.name), Object.assign(plain(ctx.emptyFilter()), { mode: 'text', text: 'py' }));
  assert.deepStrictEqual([mixed.page, mixed.size], [2, 20]);
});

test('view state: any whole page size 1..1000 round-trips; 20 is the default and encodes to nothing', () => {
  const ctx = load();
  for (const size of [1, 7, 37, 50, 75, 100, 250, 1000]) {
    const v = ctx.decodeView(JSON.stringify({ v: 1, size }), VIEW_KEYS, VIEW_TYPES, EOL_ASC);
    assert.strictEqual(v.size, size);
    assert.deepStrictEqual(JSON.parse(ctx.encodeView(v, VIEW_KEYS, EOL_ASC)), { v: 1, size });
  }
  assert.strictEqual(ctx.encodeView(ctx.decodeView('{"v":1,"size":20}', VIEW_KEYS, VIEW_TYPES, EOL_ASC), VIEW_KEYS, EOL_ASC), null);
});

test('page size input text: only whole numbers 1..1000 apply, never truncated', () => {
  const { parsePageSize } = load();
  for (const [text, want] of [['7', 7], [' 37 ', 37], ['1000', 1000], ['1', 1], ['007', 7]]) assert.strictEqual(parsePageSize(text), want, text);
  for (const text of ['', ' ', '0', '-5', '7.5', '7.0', '1001', '1e2', 'Infinity', 'NaN', 'abc', '12abc']) assert.strictEqual(parsePageSize(text), null, text);
});

test('view state: range bounds go through the same validators; invalid ones show their error and constrain nothing', () => {
  const { decodeView, filterPredicate } = load();
  const v = decodeView(JSON.stringify({ v: 1, filters: {
    eol: { mode: 'range', startText: '2026-02-30' }, count: { mode: 'range', startText: '9', endText: '2' },
    version: { mode: 'range', startText: 'abc', endText: 'zzz' }, name: { mode: 'range', startText: 'b', endText: '' } } }), VIEW_KEYS, { ...VIEW_TYPES, version: 'number' }, EOL_ASC);
  assert.strictEqual(v.filters.eol.error, 'From: Enter a real date as YYYY-MM-DD.');
  assert.strictEqual(v.filters.count.error, 'From must be on or before To.');
  assert.strictEqual(v.filters.version.error, 'From: Enter a number. To: Enter a number.');
  for (const k of ['eol', 'count', 'version']) {
    assert.deepStrictEqual([v.filters[k].start, v.filters[k].end, filterPredicate(v.filters[k], 'text')], [null, null, null], k);
  }
  assert.deepStrictEqual([v.filters.name.start, v.filters.name.end, v.filters.name.error], ['b', null, '']);
  assert.deepStrictEqual(plain(v.filters.eol).startText, '2026-02-30', 'the invalid draft is kept for the menu');
});

test('view state: an invalid range draft keeps its last valid applied bounds through encode and decode, so the same rows match', () => {
  const { encodeView, decodeView, checkRange, emptyFilter, visibleRows } = load();
  const rows = ['2026-01-01', '2026-01-05', '2026-01-09', ''].map(eol => ({ eol }));
  const view = decodeView(null, ['eol'], { eol: 'date' }, null);
  const f = view.filters.eol = Object.assign(emptyFilter(), { mode: 'range' });
  // what the menu does: a valid bound is applied, then an invalid draft keeps it applied
  for (const text of ['2026-01-05', '2026-01-5x']) {
    const c = checkRange('date', text, '');
    f.startText = text;
    f.error = c.error || '';
    if (!c.error) { f.start = c.start; f.end = c.end; }
  }
  const before = plain(visibleRows(rows, view.filters, { eol: 'date' }));
  assert.deepStrictEqual(before, [false, true, true, false]);
  const text = encodeView(view, ['eol'], null);
  assert.deepStrictEqual(JSON.parse(text).filters.eol, { mode: 'range', startText: '2026-01-5x', start: '2026-01-05', end: null });
  const back = decodeView(text, ['eol'], { eol: 'date' }, null);
  assert.deepStrictEqual(plain(visibleRows(rows, back.filters, { eol: 'date' })), before);
  assert.deepStrictEqual([back.filters.eol.startText, back.filters.eol.error], ['2026-01-5x', 'From: Enter a real date as YYYY-MM-DD.']);
  // applied bounds from the URL are untrusted too: invalid, reversed or non-string ones apply nothing
  for (const bad of [{ start: '2026-02-30' }, { start: '2026-02-01', end: '2026-01-01' }, { start: 5 }, { start: {} }, { end: ['2026-01-01'] }]) {
    const d = decodeView(JSON.stringify({ v: 1, filters: { eol: { mode: 'range', startText: 'x', ...bad } } }), ['eol'], { eol: 'date' }, null);
    assert.deepStrictEqual([d.filters.eol.start, d.filters.eol.end], [null, null], JSON.stringify(bad));
  }
  // without an error the applied bounds always come from the texts
  const valid = decodeView('{"v":1,"filters":{"eol":{"mode":"range","startText":"2026-01-02","start":"2026-01-09"}}}', ['eol'], { eol: 'date' }, null);
  assert.deepStrictEqual([valid.filters.eol.start, valid.filters.eol.end], ['2026-01-02', null]);
});

test('view state: values selections use the shorter of selected and excluded over the table\'s stable distinct values', () => {
  const { encodeView, decodeView, emptyFilter } = load();
  const domain = Array.from({ length: 9440 }, (_, i) => 'product-' + String(i).padStart(5, '0') + ' cycle ' + i);
  const domains = { cycle: domain };
  const types = { cycle: 'text' };
  const view = decodeView(null, ['cycle'], types, null, domains);
  const allButOne = domain.filter(v => v !== domain[17]);
  view.filters.cycle = Object.assign(emptyFilter(), { selected: allButOne });
  const text = encodeView(view, ['cycle'], null, domains);
  assert.deepStrictEqual(JSON.parse(text).filters.cycle, { excluded: [domain[17]] });
  assert.ok(encodeURIComponent(text).length < 200, 'all-but-one of 9440 values is a short URL');
  assert.deepStrictEqual(plain(decodeView(text, ['cycle'], types, null, domains).filters.cycle.selected), allButOne, 'domain order, stable');
  // a small selection stays an include list; [] and null keep their meanings
  const some = [domain[3], domain[1]];
  for (const [selected, expected] of [[some, { selected: some }], [[], { selected: [] }], [null, null]]) {
    view.filters.cycle = Object.assign(emptyFilter(), { selected });
    const t = encodeView(view, ['cycle'], null, domains);
    assert.deepStrictEqual(t && JSON.parse(t).filters.cycle, expected);
    const d = decodeView(t, ['cycle'], types, null, domains).filters.cycle.selected;
    assert.deepStrictEqual(d && plain(d), selected && selected.slice());
  }
  // excluded input is validated against the domain: unknown values drop out, nothing excluded is every value (null)
  const dec = (filter) => decodeView(JSON.stringify({ v: 1, filters: { cycle: filter } }), ['cycle'], types, null, domains).filters.cycle.selected;
  assert.strictEqual(dec({ excluded: [] }), null);
  assert.strictEqual(dec({ excluded: ['not a value'] }), null);
  assert.deepStrictEqual(plain(dec({ excluded: [domain[0], 'not a value'] })), domain.slice(1));
  for (const bad of [{ excluded: 'x' }, { excluded: [1] }, { excluded: null }, { excluded: {} }]) assert.strictEqual(dec(bad), null, JSON.stringify(bad));
  assert.deepStrictEqual(plain(dec({ selected: [domain[2]], excluded: [domain[2]] })), [domain[2]], 'a valid include list wins');
  // without a known domain (no rows) an excluded list cannot be resolved and constrains nothing
  assert.strictEqual(decodeView('{"v":1,"filters":{"cycle":{"excluded":["a"]}}}', ['cycle'], types, null).filters.cycle.selected, null);
});

test('one view param is set or removed; every other param and the fragment stay', () => {
  const { withParam } = load();
  const href = 'https://x.test/dependencies/react?version=18&snapshot=3&asOf=2026-01-01&start=2026-01-01&end=&unused=true&view.other=%7B%7D#frag';
  const set = new URL(withParam(href, 'view.t', '{"v":1,"page":2}'));
  const removed = new URL(withParam(set.href, 'view.t', null));
  for (const u of [set, removed]) {
    assert.deepStrictEqual([...u.searchParams].filter(([k]) => k !== 'view.t'), [...new URL(href).searchParams]);
    assert.strictEqual(u.hash, '#frag');
  }
  assert.strictEqual(set.searchParams.get('view.t'), '{"v":1,"page":2}');
  assert.strictEqual(removed.searchParams.has('view.t'), false);
  assert.strictEqual(withParam('https://x.test/repos?view.t=1#a', 'view.t', null), 'https://x.test/repos#a');
});

// A small DOM: just what initTable touches, so the real shared code runs against real-shaped nodes.
function selectorMatches(n, selector) {
  return n.nodeType === 1 && selector.split(',').some(s => {
    const m = /^([a-z]*)((?:\.[\w-]+)*)((?:\[[\w-]+(?:="[^"]*")?\])*)(?::not\(\.([\w-]+)\))?$/.exec(s.trim());
    if (!m || (m[1] && n.tagName !== m[1])) return false;
    const cls = n.className.split(/\s+/);
    if (!m[2].split('.').filter(Boolean).every(c => cls.includes(c)) || (m[4] && cls.includes(m[4]))) return false;
    return (m[3].match(/\[[^\]]+\]/g) || []).every(a => {
      const [, name, value] = /^\[([\w-]+)(?:="([^"]*)")?\]$/.exec(a);
      return n.hasAttribute(name) && (value === undefined || n.getAttribute(name) === value);
    });
  });
}
class FakeNode {
  remove() { if (this.parentNode) { this.parentNode.childNodes.splice(this.parentNode.childNodes.indexOf(this), 1); this.parentNode = null; } }
  before(...ns) { ns.forEach(n => this.parentNode.insertBefore(n, this)); }
  after(...ns) { const p = this.parentNode, ref = p.childNodes[p.childNodes.indexOf(this) + 1] || null; ns.forEach(n => p.insertBefore(n, ref)); }
  replaceWith(...ns) { this.before(...ns); this.remove(); }
}
class FakeText extends FakeNode { constructor(t) { super(); this.nodeType = 3; this.textContent = String(t); this.parentNode = null; } }
class FakeEl extends FakeNode {
  constructor(tag) {
    super();
    Object.assign(this, { nodeType: 1, tagName: tag, attrs: new Map(), childNodes: [], parentNode: null, hidden: false, style: {}, events: {}, className: '', id: '', offsetHeight: 0, offsetWidth: 0, dataset: {} });
  }
  get classList() {
    const list = () => this.className.split(/\s+/).filter(Boolean);
    return { add: (...n) => { this.className = [...new Set([...list(), ...n])].join(' '); }, remove: (...n) => { this.className = list().filter(c => !n.includes(c)).join(' '); },
      contains: n => list().includes(n) };
  }
  matches(s) { return selectorMatches(this, s); }
  get textContent() { return this.childNodes.map(c => c.textContent).join(''); }
  set textContent(t) { this.childNodes.slice().forEach(c => c.remove()); this.append(String(t)); }
  get lastChild() { return this.childNodes[this.childNodes.length - 1] || null; }
  get cells() { return this.childNodes.filter(c => c.tagName === 'td' || c.tagName === 'th'); }
  get rows() { return this.tagName === 'table' ? this.childNodes.flatMap(c => c.rows || []) : this.childNodes.filter(c => c.tagName === 'tr'); }
  get tHead() { return this.childNodes.find(c => c.tagName === 'thead') || null; }
  get tBodies() { return this.childNodes.filter(c => c.tagName === 'tbody'); }
  insertBefore(n, ref) {
    if (typeof n === 'string') n = new FakeText(n);
    n.remove();
    const i = ref ? this.childNodes.indexOf(ref) : -1;
    this.childNodes.splice(i < 0 ? this.childNodes.length : i, 0, n);
    n.parentNode = this;
    return n;
  }
  appendChild(n) { return this.insertBefore(n, null); }
  append(...ns) { ns.forEach(n => this.appendChild(n)); }
  prepend(...ns) { const first = this.childNodes[0] || null; ns.forEach(n => this.insertBefore(n, first)); }
  add(option) { this.appendChild(option); }
  hasAttribute(n) { return this.attrs.has(n); }
  getAttribute(n) { return this.attrs.has(n) ? this.attrs.get(n) : null; }
  setAttribute(n, v) { this.attrs.set(n, String(v)); }
  removeAttribute(n) { this.attrs.delete(n); }
  contains(n) { for (; n; n = n.parentNode) if (n === this) return true; return false; }
  closest(s) { for (let n = this; n && n.nodeType === 1; n = n.parentNode) if (selectorMatches(n, s)) return n; return null; }
  querySelectorAll(s) {
    const out = [];
    const walk = n => n.childNodes.forEach(c => { if (c.nodeType === 1) { if (selectorMatches(c, s)) out.push(c); walk(c); } });
    walk(this);
    return out;
  }
  querySelector(s) { return this.querySelectorAll(s)[0] || null; }
  addEventListener(t, fn) { (this.events[t] ||= []).push(fn); }
  emit(t, e = {}) { (this.events[t] || []).slice().forEach(fn => fn({ target: this, preventDefault() {}, ...e })); }
  focus() {}
  getBoundingClientRect() { return { top: 0, bottom: 20, left: 0, right: 100, width: 100 }; }
}

function viewDom(href, stored = {}, extra = {}) {
  const win = new FakeEl('window');
  const storage = new Map(Object.entries(stored));
  Object.assign(win, extra, { innerHeight: 800, innerWidth: 1200, storage, writes: [], assigned: null,
    localStorage: { getItem: k => storage.get(k) ?? null, setItem: (k, v) => storage.set(k, v), removeItem: k => storage.delete(k) },
    Option: function (text, value) { const o = new FakeEl('option'); o.textContent = text; o.value = value; return o; } });
  win.location = { href, assign(u) { win.assigned = u; } };
  win.history = { state: { keep: true }, replaceState(state, title, u) { win.writes.push(state); win.location.href = new URL(u, win.location.href).href; },
    pushState() { throw new Error('view changes must not add history entries'); } };
  const docEvents = new FakeEl('document');
  const doc = { addEventListener: docEvents.addEventListener.bind(docEvents), activeElement: null, getElementById() { return null; },
    createElement: t => new FakeEl(t), createElementNS: (ns, t) => new FakeEl(t) };
  return { win, doc, docEvents, ctx: load(win, doc) };
}

/** 25 rows: pkg01..pkg25, version 1.N, count N, eol 2026-01-NN; row 25 has no EoL date. */
function viewTable(id, attrs = {}, { region = null, dataColumns = true } = {}) {
  const main = new FakeEl('main'), wrap = new FakeEl('div'), table = new FakeEl('table'), thead = new FakeEl('thead'), head = new FakeEl('tr'), tbody = new FakeEl('tbody');
  wrap.className = 'table-wrap';
  if (region) wrap.setAttribute('aria-label', region);
  table.id = id;
  Object.entries(attrs).forEach(([k, v]) => table.setAttribute(k, v));
  VIEW_KEYS.forEach(k => {
    const th = new FakeEl('th');
    if (dataColumns) th.setAttribute('data-column', k);
    th.setAttribute('data-type', VIEW_TYPES[k]);
    th.append(k.toUpperCase());
    head.append(th);
  });
  for (let i = 1; i <= 25; i++) {
    const tr = new FakeEl('tr'), n = String(i).padStart(2, '0');
    ['pkg' + n, '1.' + i, String(i), i < 25 ? '2026-01-' + n : ''].forEach(v => { const td = new FakeEl('td'); td.append(v); tr.append(td); });
    tbody.append(tr);
  }
  thead.append(head);
  table.append(thead, tbody);
  wrap.append(table);
  main.append(wrap);
  const q = s => main.querySelector(s);
  return { table, q, th: k => head.cells[VIEW_KEYS.indexOf(k)], status: () => q('[data-page-status]').textContent,
    shown: () => tbody.rows.filter(tr => !tr.hidden && !tr.hasAttribute('data-empty')).map(tr => tr.cells[0].textContent) };
}
const range = (from, to) => Array.from({ length: Math.abs(to - from) + 1 }, (_, i) => 'pkg' + String(from + (to > from ? i : -i)).padStart(2, '0'));
const viewOf = (win, param) => { const t = new URL(win.location.href).searchParams.get(param); return t === null ? null : JSON.parse(t); };

test('a table opens on the rows, order and page its URL param holds, whatever localStorage says; nothing else in the URL changes', () => {
  const state = { v: 1, filters: { count: { mode: 'range', startText: '3', endText: '24' } }, sort: { key: 'count', dir: 'desc' }, page: 2 };
  const href = 'https://x.test/repos/7/history?asOf=2026-01-01&snapshot=4&start=2026-01-01&unused=true&view.other-table=%7B%22v%22%3A1%7D&view.snap='
    + encodeURIComponent(JSON.stringify(state)) + '#deps';
  const { win, ctx } = viewDom(href, { 'eol-ledger.table-columns.v1.snap': '{"NAME":false}', 'view.snap': '{"v":1,"page":1}',
    'eol-ledger.dependencies-table.v1': '{"columns":{}}' });
  const t = viewTable('snap');
  ctx.initTable(t.table);
  assert.deepStrictEqual(t.shown(), ['pkg04', 'pkg03']);
  assert.strictEqual(t.status(), 'Page 2 of 2');
  assert.strictEqual(t.th('count').getAttribute('aria-sort'), 'descending');
  assert.strictEqual(t.th('count').getAttribute('data-filter-active'), 'true');
  assert.strictEqual(t.q('[data-filter-start="count"]').value, '3');
  assert.deepStrictEqual(viewOf(win, 'view.snap'), state);
  const after = new URL(win.location.href);
  assert.deepStrictEqual([...after.searchParams].filter(([k]) => k !== 'view.snap'), [...new URL(href).searchParams].filter(([k]) => k !== 'view.snap'));
  assert.strictEqual(after.hash, '#deps');
  assert.strictEqual(win.storage.get('view.snap'), '{"v":1,"page":1}', 'view state is never stored');
  assert.strictEqual(win.storage.size, 3);
});

test('every sort, filter and page change mirrors the address bar; clearing removes the param; history entries are replaced, not added', () => {
  const href = 'https://x.test/dependencies/react?version=18&end=&view.other=1#top';
  const { win, ctx } = viewDom(href);
  const t = viewTable('release-cycles-table');
  ctx.initTable(t.table);
  assert.strictEqual(win.location.href, href, 'a default view writes nothing');
  assert.deepStrictEqual(t.shown(), range(1, 20));
  const p = 'view.release-cycles-table';

  t.th('count').emit('click');
  t.th('count').emit('click');
  assert.deepStrictEqual(viewOf(win, p), { v: 1, sort: { key: 'count', dir: 'desc' } });
  assert.deepStrictEqual(t.shown(), range(25, 6));

  const mode = t.q('[data-filter-mode="eol"]');
  mode.value = 'range';
  mode.emit('change');
  const start = t.q('[data-filter-start="eol"]');
  start.value = '2026-01-05';
  start.emit('input');
  assert.deepStrictEqual(viewOf(win, p).filters, { eol: { mode: 'range', startText: '2026-01-05' } });
  assert.deepStrictEqual(t.shown(), range(24, 5));
  start.value = '2026-01-5x';
  start.emit('input');
  assert.deepStrictEqual(viewOf(win, p).filters.eol, { mode: 'range', startText: '2026-01-5x', start: '2026-01-05', end: null },
    'the draft is mirrored next to the last valid bounds, which stay applied');
  assert.deepStrictEqual(t.shown(), range(24, 5));

  const op = t.q('[data-filter-operator="name"]'), text = t.q('[data-filter-text="name"]');
  t.q('[data-filter-mode="name"]').value = 'text';
  t.q('[data-filter-mode="name"]').emit('change');
  op.value = 'ends-with';
  op.emit('change');
  text.value = '1';
  text.emit('input');
  assert.deepStrictEqual(viewOf(win, p).filters.name, { mode: 'text', op: 'ends-with', text: '1' });
  assert.deepStrictEqual(t.shown(), ['pkg21', 'pkg11'], 'eol >= 2026-01-05 and name ends with 1, count descending');

  const size = t.q('[data-page-size]');
  size.value = '50';
  size.emit('change');
  assert.strictEqual(viewOf(win, p).size, 50);
  t.q('[data-filters-clear-all]').emit('click');
  assert.deepStrictEqual(viewOf(win, p), { v: 1, size: 50 }, 'clear all keeps the page size, like the pager does');
  t.q('[data-page-next]').emit('click');
  size.value = '20';
  size.emit('change');
  t.q('[data-page-next]').emit('click');
  assert.deepStrictEqual(viewOf(win, p), { v: 1, page: 2 });
  assert.deepStrictEqual(t.shown(), range(21, 25));
  t.q('[data-page-prev]').emit('click');
  assert.strictEqual(win.location.href, href, 'back to defaults: the param is gone, the rest is untouched');
  assert.ok(win.writes.length > 0 && win.writes.every(s => s === win.history.state), 'replaceState keeps the entry\'s own state');
});

test('rows per page: a labelled number field applies whole 1..1000 on change, blur or Enter only, once; invalid input keeps the last size', () => {
  const { win, ctx } = viewDom('https://x.test/repos');
  const t = viewTable('code-repos-table'), other = viewTable('other-table');
  ctx.initTable(t.table);
  ctx.initTable(other.table);
  const size = t.q('[data-page-size]'), p = 'view.code-repos-table';
  assert.strictEqual(size.tagName, 'input');
  assert.deepStrictEqual(['type', 'min', 'max', 'step'].map(a => size.getAttribute(a)), ['number', '1', '1000', '1']);
  assert.strictEqual(size.value, '20');
  assert.strictEqual(size.parentNode.tagName, 'label');
  assert.match(size.parentNode.textContent, /Rows per page/);
  let draws = 0;
  const range = ctx.pageRange;
  ctx.pageRange = function () { draws++; return range.apply(this, arguments); };

  size.value = '7';
  size.emit('input'); // typing a digit applies nothing
  assert.strictEqual(draws, 0);
  assert.strictEqual(t.shown().length, 20);
  size.emit('change');
  assert.deepStrictEqual(t.shown(), ['pkg01', 'pkg02', 'pkg03', 'pkg04', 'pkg05', 'pkg06', 'pkg07']);
  assert.strictEqual(t.status(), 'Page 1 of 4');
  assert.strictEqual(viewOf(win, p).size, 7);
  size.emit('blur'); // the same committed value: no second draw
  size.emit('keydown', { key: 'Enter' });
  assert.strictEqual(draws, 1);

  t.q('[data-page-next]').emit('click');
  const error = t.q('[data-page-size-error]');
  for (const bad of ['', '0', '-3', '7.5', '1001', '1e2']) {
    const before = draws;
    size.value = bad;
    size.emit('change');
    assert.strictEqual(draws, before, bad + ' must not redraw');
    assert.strictEqual(size.getAttribute('aria-invalid'), 'true', bad);
    assert.match(error.textContent, /whole number from 1 to 1000/, bad);
    assert.strictEqual(viewOf(win, p).size, 7, bad);
    assert.strictEqual(t.status(), 'Page 2 of 4', bad);
  }
  assert.strictEqual(size.getAttribute('aria-describedby'), error.id);
  size.value = '12';
  size.emit('keydown', { key: 'Enter' });
  assert.strictEqual(size.getAttribute('aria-invalid'), 'false');
  assert.strictEqual(error.textContent, '');
  assert.strictEqual(t.status(), 'Page 1 of 3', 'a new size starts on page 1');
  assert.strictEqual(viewOf(win, p).size, 12);
  // the other table on the page keeps its own size
  assert.strictEqual(other.q('[data-page-size]').value, '20');
  assert.strictEqual(other.shown().length, 20);
  assert.strictEqual(viewOf(win, 'view.other-table'), null);
});

test('rows per page: a committed size is remembered per table role and restored when the URL has no view param; a URL param always wins', () => {
  const first = viewDom('https://x.test/repos');
  const t = viewTable('code-repos-table');
  first.ctx.initTable(t.table);
  t.q('[data-page-size]').value = '37';
  t.q('[data-page-size]').emit('change');
  assert.strictEqual(first.win.storage.get('eol-ledger.page-size.v1.code-repos-table'), '37');
  const saved = Object.fromEntries(first.win.storage);

  // navigating back to the plain page restores 37 from storage; the address bar then mirrors the shown view
  const back = viewDom('https://x.test/repos', saved);
  const u = viewTable('code-repos-table');
  back.ctx.initTable(u.table);
  assert.deepStrictEqual([u.q('[data-page-size]').value, u.shown().length, u.status()], ['37', 25, 'Page 1 of 1']);
  assert.deepStrictEqual(viewOf(back.win, 'view.code-repos-table'), { v: 1, size: 37 });

  // an explicit param wins, even one without a size or with an invalid one: those mean the default 20
  for (const view of ['{"v":1}', '{"v":1,"size":7.5}', '{"v":1,"size":0}', '%7Bbroken']) {
    const linked = viewDom('https://x.test/repos?view.code-repos-table=' + (view.startsWith('%') ? view : encodeURIComponent(view)), saved);
    const w = viewTable('code-repos-table');
    linked.ctx.initTable(w.table);
    assert.strictEqual(w.q('[data-page-size]').value, '20', view);
    assert.strictEqual(w.shown().length, 20, view);
  }
  const linked = viewDom('https://x.test/repos?view.code-repos-table=' + encodeURIComponent('{"v":1,"size":7}'), saved);
  const x = viewTable('code-repos-table');
  linked.ctx.initTable(x.table);
  assert.strictEqual(x.q('[data-page-size]').value, '7');
});

test('rows per page: saved sizes are per role, invalid or unreadable ones mean 20, failing storage never breaks paging, empty tables restore too', () => {
  for (const bad of ['0', '7.5', '1001', 'abc', '']) {
    const { ctx } = viewDom('https://x.test/repos', { 'eol-ledger.page-size.v1.code-repos-table': bad });
    const t = viewTable('code-repos-table');
    ctx.initTable(t.table);
    assert.strictEqual(t.q('[data-page-size]').value, '20', JSON.stringify(bad));
  }
  // another role keeps its own size
  const roles = viewDom('https://x.test/repos', { 'eol-ledger.page-size.v1.code-repos-table': '37' });
  const other = viewTable('other-table');
  roles.ctx.initTable(other.table);
  assert.strictEqual(other.q('[data-page-size]').value, '20');
  // throwing storage: defaults, and a committed size still applies
  const broken = viewDom('https://x.test/repos');
  broken.win.localStorage = { getItem() { throw new Error('blocked'); }, setItem() { throw new Error('blocked'); }, removeItem() { throw new Error('blocked'); } };
  const b = viewTable('code-repos-table');
  broken.ctx.initTable(b.table);
  assert.strictEqual(b.q('[data-page-size]').value, '20');
  b.q('[data-page-size]').value = '7';
  b.q('[data-page-size]').emit('change');
  assert.strictEqual(b.shown().length, 7);
  // a table with no rows still shows and keeps its role's size
  const empty = viewDom('https://x.test/repos', { 'eol-ledger.page-size.v1.empty-table': '7' });
  const e = viewTable('empty-table');
  e.table.tBodies[0].childNodes.length = 0;
  empty.ctx.initTable(e.table);
  assert.strictEqual(e.q('[data-page-size]').value, '7');
  e.q('[data-page-size]').value = '12';
  e.q('[data-page-size]').emit('change');
  assert.strictEqual(empty.win.storage.get('eol-ledger.page-size.v1.empty-table'), '12');
  assert.strictEqual(e.table.hasAttribute('data-table-ready'), true);
});

test('table settings: an icon-only gear named "Table settings" sits right after Clear all in the shared toolbar, for generated and server-rendered settings', () => {
  const { ctx } = viewDom('https://x.test/repos');
  const t = viewTable('code-repos-table');
  ctx.initTable(t.table);
  ctx.initVisibility(t.table, () => {});
  const bar = t.q('.filter-bar'), settings = t.q('details.table-settings');
  assert.strictEqual(settings.parentNode, bar, 'docked in the toolbar, not above the table');
  assert.deepStrictEqual(bar.childNodes.map(n => n.hasAttribute('data-filter-count') ? 'count' : n.hasAttribute('data-filters-clear-all') ? 'clear' : n.tagName),
    ['count', 'clear', 'details']);
  const summary = settings.childNodes[0];
  assert.strictEqual(summary.tagName, 'summary');
  assert.strictEqual(summary.getAttribute('aria-label'), 'Table settings');
  assert.strictEqual(summary.textContent.trim(), '', 'icon only');
  assert.ok(summary.querySelector('svg'));
  assert.strictEqual(settings.querySelector('[data-columns-reset]').textContent, 'Reset columns');
  // still the one working control set: hiding a column from the docked panel hides it
  const box = t.q('[data-column-toggle="COUNT"]');
  box.checked = false;
  box.emit('change');
  assert.strictEqual(t.th('count').hidden, true);
  assert.strictEqual(t.q('.filter-bar').querySelectorAll('details').length, 1, 'never duplicated');

  // the dashboard's server-rendered settings move the same way
  const u = viewTable('dependencies-table');
  const rendered = new FakeEl('details'), s = new FakeEl('summary');
  rendered.id = 'table-settings';
  s.append('Table settings');
  rendered.append(s);
  u.table.parentNode.before(rendered);
  ctx.initTable(u.table);
  ctx.placeSettings(u.table, rendered);
  assert.strictEqual(rendered.parentNode, u.q('.filter-bar'));
  assert.strictEqual(s.getAttribute('aria-label'), 'Table settings');
  assert.ok(s.querySelector('svg'));
});

test('the count says "matching" while a filter is active; zero matches too; clearing restores the plain wording', () => {
  const { ctx } = viewDom('https://x.test/repos');
  const t = viewTable('code-repos-table');
  ctx.initTable(t.table);
  const count = () => t.q('[data-filter-count]').textContent;
  assert.strictEqual(count(), '25 of 25 rows');
  // a filter that keeps all 25 rows: still a filtered result, of which page 1 shows 20
  t.q('[data-filter-mode="count"]').value = 'range';
  t.q('[data-filter-mode="count"]').emit('change');
  t.q('[data-filter-start="count"]').value = '1';
  t.q('[data-filter-start="count"]').emit('input');
  assert.strictEqual(count(), '25 matching rows of 25');
  assert.strictEqual(t.shown().length, 20);
  t.q('[data-filter-select-none="name"]').emit('click');
  assert.strictEqual(count(), '0 matching rows of 25');
  assert.strictEqual(t.shown().length, 0);
  t.q('[data-filters-clear-all]').emit('click');
  assert.strictEqual(count(), '25 of 25 rows');
});

test('/repos nine columns: Version, SBOM Format and Repository name inserted, yet a saved c3 filter, Provider:3 order and width slot 3 stay on Provider', () => {
  // exactly the /repos header: DOM order with its data-preference-index (old six kept 0-5, Version 6, SBOM Format 7, Repository name 8)
  const HEADERS = ['App ID', 'Repository name', 'Code repo', 'Branch', 'Version', 'Provider', 'SBOM Format', 'Packages', 'Latest snapshot'];
  const INDEXES = [0, 8, 1, 2, 6, 3, 7, 4, 5];
  const ROWS = [['A1', 'Alpha', 'https://g/a', 'main', '1.0', 'gitlab', 'Inventory JSON', '3', '2026-01-01'],
    ['A2', '—', 'https://g/b', 'main', '2.0', 'github', 'CycloneDX 1.6 JSON', '5', '2026-01-02'],
    ['A3', 'Gamma', '—', '—', '—', '—', '—', '1', '2026-01-03']];
  const make = (href, stored) => {
    const dom = viewDom(href, stored);
    const main = new FakeEl('main'), wrap = new FakeEl('div'), table = new FakeEl('table'), thead = new FakeEl('thead'), head = new FakeEl('tr'), tbody = new FakeEl('tbody');
    wrap.className = 'table-wrap';
    table.id = 'code-repos-table';
    HEADERS.forEach((h, i) => { const th = new FakeEl('th'); th.setAttribute('data-preference-index', String(INDEXES[i])); th.append(h); head.append(th); });
    ROWS.forEach(r => { const tr = new FakeEl('tr'); r.forEach(v => { const td = new FakeEl('td'); td.append(v); tr.append(td); }); tbody.append(tr); });
    thead.append(head);
    table.append(thead, tbody);
    wrap.append(table);
    main.append(wrap);
    table.offsetWidth = 900;
    dom.ctx.initTable(table);
    const th = name => head.cells.find(c => c.getAttribute('data-label') === name);
    const shown = () => tbody.rows.filter(tr => !tr.hidden && !tr.hasAttribute('data-empty')).map(tr => tr.cells[0].textContent);
    return { ...dom, table, head, th, shown };
  };
  // an old copied URL filtering c3 still filters Provider, not Version
  const a = make('https://x.test/repos?view.code-repos-table=' + encodeURIComponent('{"v":1,"filters":{"c3":{"selected":["github"]}}}'));
  assert.deepStrictEqual(a.shown(), ['A2']);
  assert.strictEqual(a.th('Provider').getAttribute('data-filter-active'), 'true');
  assert.strictEqual(a.th('Version').getAttribute('data-filter-active'), 'false');

  // an old custom order Provider:3 first keeps Provider; the new columns join in DOM order; reset restores the eight default
  const b = make('https://x.test/repos', { 'eol-ledger.table-order.v1.code-repos-table': '["Provider:3","App ID:0","Code repo:1","Branch:2","Packages:4","Latest snapshot:5"]' });
  b.ctx.initColumnOrder(b.table);
  const labels = () => b.head.cells.map(c => c.getAttribute('data-label'));
  assert.deepStrictEqual(labels(), ['Provider', 'App ID', 'Code repo', 'Branch', 'Packages', 'Latest snapshot', 'Repository name', 'Version', 'SBOM Format']);
  b.table.columnLayout.reset();
  assert.deepStrictEqual(labels(), HEADERS);
  // a saved order from the eight-column release keeps every column it knew; Repository name joins without a reset
  const e = make('https://x.test/repos', { 'eol-ledger.table-order.v1.code-repos-table': '["Version:6","App ID:0","Code repo:1","Branch:2","Provider:3","SBOM Format:7","Packages:4","Latest snapshot:5"]' });
  e.ctx.initColumnOrder(e.table);
  assert.deepStrictEqual(e.head.cells.map(c => c.getAttribute('data-label')),
    ['Version', 'App ID', 'Code repo', 'Branch', 'Provider', 'SBOM Format', 'Packages', 'Latest snapshot', 'Repository name']);
  // a filter on the new column 8 reaches the address bar as c8 and leaves c0..c7 alone
  const f = make('https://x.test/repos?view.code-repos-table=' + encodeURIComponent('{"v":1,"filters":{"c8":{"selected":["Gamma"]}}}'));
  assert.deepStrictEqual(f.shown(), ['A3']);
  assert.strictEqual(f.th('Repository name').getAttribute('data-filter-active'), 'true');

  // a numeric width saved in slot 3 is read for Provider, resized and written back to slot 3, and reset deletes slot 3
  const c = make('https://x.test/repos', { 'eol-ledger.table-widths.v1': '{"code-repos-table":{"3":222}}' });
  c.ctx.initResize(c.table);
  const provider = c.th('Provider'), handle = provider.querySelector('[data-column-resize]');
  assert.strictEqual(provider.style.width, '222px');
  assert.strictEqual(handle.getAttribute('data-column-resize'), '3');
  handle.emit('keydown', { key: 'ArrowRight' });
  assert.deepStrictEqual(JSON.parse(c.win.storage.get('eol-ledger.table-widths.v1')), { 'code-repos-table': { 3: 232 } });
  handle.emit('dblclick');
  assert.deepStrictEqual(JSON.parse(c.win.storage.get('eol-ledger.table-widths.v1')), { 'code-repos-table': {} });
});

test('the global Settings panel takes over the same server-rendered Imports link, keeping its URL and current-page state', () => {
  const { win, ctx } = viewDom('https://x.test/imports');
  const topbar = new FakeEl('header'), nav = new FakeEl('nav'), imports = new FakeEl('a');
  imports.setAttribute('href', '/ctx/imports');
  imports.setAttribute('data-settings-imports', '');
  imports.setAttribute('aria-current', 'page');
  imports.className = 'button-quiet';
  imports.append('Imports');
  topbar.append(nav, imports);
  ctx.initSettings(topbar, { get() { return { mode: 'dark', theme: 'ledger' }; }, set() {} });
  const panel = topbar.querySelector('#settings-panel') || topbar.querySelector('.settings-panel');
  assert.strictEqual(panel.getAttribute('aria-label'), 'Settings');
  assert.ok(panel.contains(imports), 'the same anchor, moved into the panel');
  assert.strictEqual(topbar.querySelectorAll('[data-settings-imports]').length, 1, 'never duplicated');
  assert.strictEqual(imports.getAttribute('href'), '/ctx/imports');
  assert.strictEqual(imports.getAttribute('aria-current'), 'page');
  assert.ok(panel.querySelector('[data-theme]'), 'appearance controls stay');
  const toggle = topbar.querySelector('.settings-toggle');
  // a 320x568 phone with the panel starting at 177.781px: it may only be as tall as the room below it, less 16px
  win.innerHeight = 568;
  panel.getBoundingClientRect = () => ({ top: 177.781, bottom: 588.234, left: 0, right: 300, width: 300 });
  toggle.emit('click');
  assert.strictEqual(panel.hidden, false);
  assert.strictEqual(panel.style.maxHeight, (568 - 177.781 - 16) + 'px');
  win.innerHeight = 150; // a resize while open refits; never below 0
  win.emit('resize');
  assert.strictEqual(panel.style.maxHeight, '0px');
  toggle.emit('click');
  assert.strictEqual(panel.hidden, true);
});

test('dependency table settings list only show/hide checkboxes (no width fields); header resizers still resize and save widths', () => {
  const { win, ctx } = viewDom('https://x.test/');
  const keys = vm.runInContext('Object.keys(TABLE_COLUMNS)', ctx);
  const table = new FakeEl('table'), group = new FakeEl('colgroup'), thead = new FakeEl('thead'), head = new FakeEl('tr'), tbody = new FakeEl('tbody');
  table.dataset.dependencyTable = 'dashboard';
  keys.forEach(k => {
    const c = new FakeEl('col'), th = new FakeEl('th');
    c.setAttribute('data-column', k);
    th.setAttribute('data-column', k);
    th.append(k.toUpperCase());
    group.append(c);
    head.append(th);
  });
  thead.append(head);
  table.append(group, thead, tbody);
  const settings = new FakeEl('details'), list = new FakeEl('div'), reset = new FakeEl('button');
  list.className = 'col-settings';
  reset.setAttribute('data-columns-reset', '');
  settings.append(list, reset);
  ctx.initColumns(table, settings);

  const inputs = settings.querySelectorAll('input');
  assert.strictEqual(inputs.length, keys.length, 'one control per column');
  assert.ok(inputs.every(i => i.type === 'checkbox' && i.hasAttribute('data-column-toggle')), 'only show/hide checkboxes');
  assert.strictEqual(settings.querySelectorAll('[data-column-width]').length, 0, 'no numeric width fields');
  assert.doesNotMatch(settings.textContent, /width \(px\)/);

  const th = head.cells[0], handle = th.querySelector('[data-column-resize]');
  assert.ok(handle, 'every header keeps its resize handle');
  const before = parseInt(th.style.width, 10);
  handle.emit('keydown', { key: 'ArrowRight' });
  assert.strictEqual(parseInt(th.style.width, 10), before + 10);
  assert.strictEqual(handle.getAttribute('aria-valuenow'), String(before + 10));
  assert.strictEqual(JSON.parse(win.storage.get('eol-ledger.dependencies-table.v1')).columns[keys[0]].width, before + 10, 'the dragged width is saved');
});

test('rows per page: a custom size restores from a copied URL, and malformed sizes there fall back to 20', () => {
  for (const [size, shown, status] of [[37, 25, 'Page 1 of 1'], [7, 7, 'Page 1 of 4'], [7.5, 20, 'Page 1 of 2'], [0, 20, 'Page 1 of 2'], [1001, 20, 'Page 1 of 2']]) {
    const { ctx } = viewDom('https://x.test/repos?view.code-repos-table=' + encodeURIComponent(JSON.stringify({ v: 1, size })));
    const t = viewTable('code-repos-table');
    ctx.initTable(t.table);
    assert.deepStrictEqual([t.shown().length, t.status(), t.q('[data-page-size]').value], [shown, status, String(shown === 25 ? 37 : shown)], String(size));
  }
});

test('values mode: none selected ([]) and a partial selection round-trip through the URL; select all is null again', () => {
  const { win, ctx } = viewDom('https://x.test/repos');
  const t = viewTable('code-repos-table');
  ctx.initTable(t.table);
  t.q('[data-filter-select-none="version"]').emit('click');
  assert.deepStrictEqual(viewOf(win, 'view.code-repos-table'), { v: 1, filters: { version: { selected: [] } } });
  assert.deepStrictEqual(t.shown(), []);
  const boxes = t.th('version').querySelectorAll('[data-filter-value="version"]');
  const three = boxes.find(b => b.value === '1.3');
  three.checked = true;
  three.emit('change');
  assert.deepStrictEqual(viewOf(win, 'view.code-repos-table').filters.version, { selected: ['1.3'] });
  assert.deepStrictEqual(t.shown(), ['pkg03']);

  // reopening the shared URL in a fresh page restores the same selection and checkboxes
  const again = viewDom(win.location.href), u = viewTable('code-repos-table');
  again.ctx.initTable(u.table);
  assert.deepStrictEqual(u.shown(), ['pkg03']);
  assert.deepStrictEqual(u.th('version').querySelectorAll('[data-filter-value="version"]').filter(b => b.checked).map(b => b.value), ['1.3']);
  t.q('[data-filter-select-all="version"]').emit('click');
  assert.strictEqual(viewOf(win, 'view.code-repos-table'), null);

  // all but one value: the table's own distinct values make it a short exclusion, and it reads back the same
  const seven = boxes.find(b => b.value === '1.7');
  seven.checked = false;
  seven.emit('change');
  assert.deepStrictEqual(viewOf(win, 'view.code-repos-table').filters.version, { excluded: ['1.7'] });
  const third = viewDom(win.location.href), w = viewTable('code-repos-table');
  third.ctx.initTable(w.table);
  assert.deepStrictEqual(w.shown(), range(1, 21).filter(n => n !== 'pkg07'));
  assert.deepStrictEqual(w.th('version').querySelectorAll('[data-filter-value="version"]').filter(b => !b.checked).map(b => b.value), ['1.7']);
});

test('values-list search and its match mode reach the address bar at once and hydrate, without changing rows or page', () => {
  const { win, ctx } = viewDom('https://x.test/repos?x=1#h');
  const t = viewTable('code-repos-table');
  ctx.initTable(t.table);
  t.q('[data-page-next]').emit('click');
  const search = t.q('[data-filter-search="name"]'), searchOp = t.q('[data-filter-search-op="name"]');
  search.value = 'pkg1';
  search.emit('input');
  assert.deepStrictEqual(viewOf(win, 'view.code-repos-table'), { v: 1, filters: { name: { search: 'pkg1' } }, page: 2 });
  searchOp.value = 'exact';
  searchOp.emit('change');
  assert.deepStrictEqual(viewOf(win, 'view.code-repos-table'), { v: 1, filters: { name: { search: 'pkg1', searchOp: 'exact' } }, page: 2 });
  assert.deepStrictEqual(t.shown(), range(21, 25), 'search only narrows the checklist');
  assert.strictEqual(new URL(win.location.href).hash, '#h');

  const again = viewDom(win.location.href), u = viewTable('code-repos-table');
  again.ctx.initTable(u.table);
  assert.deepStrictEqual([u.q('[data-filter-search="name"]').value, u.q('[data-filter-search-op="name"]').value], ['pkg1', 'exact']);
  assert.deepStrictEqual(u.shown(), range(21, 25));
  assert.strictEqual(u.status(), 'Page 2 of 2');
});

// The four tables that gained ids keep their former browser-local preference identity (their old tableNoun), so saved
// column order, visibility and numeric-index widths survive; the ids only name their URL view params.
const FORMER_ROLES = [
  { id: 'release-cycles-table', role: 'release cycles', attrs: {}, region: 'Release cycles' },
  { id: 'affected-branches-table', role: 'affected code repo branches', attrs: {}, region: 'Affected code repo branches' },
  { id: 'import-history-table', role: 'import runs', attrs: { 'data-noun': 'import runs' }, region: 'Import history' },
  { id: 'snapshot-timeline-table', role: 'snapshots', attrs: { 'data-noun': 'snapshots' }, region: 'Snapshot timeline' },
];

test('newly id\'d tables keep reading, saving and resetting their old role-keyed order, visibility and widths; ids stay URL-only', () => {
  for (const { id, role, attrs, region } of FORMER_ROLES) {
    const orderKey = 'eol-ledger.table-order.v1.' + encodeURIComponent(role), visibilityKey = 'eol-ledger.table-columns.v1.' + encodeURIComponent(role);
    const otherWidths = { 'code-repos-table': { 0: 333 } };
    const { win, ctx } = viewDom('https://x.test/page?keep=1#h', {
      [orderKey]: JSON.stringify(['EOL:3', 'NAME:0', 'VERSION:1', 'COUNT:2']),
      [visibilityKey]: JSON.stringify({ VERSION: false }),
      'eol-ledger.table-widths.v1': JSON.stringify({ ...otherWidths, [role]: { 0: 150, 3: 222 } }),
    });
    // the template's explicit preference key: exactly the former noun
    const t = viewTable(id, { ...attrs, 'data-preference-key': role }, { region, dataColumns: false });
    t.table.offsetWidth = 500;
    assert.strictEqual(ctx.tableNoun(t.table), role, id + ' former noun');
    // production wiring order: header controls, then column order, then widths + visibility
    ctx.initTable(t.table, { noun: ctx.tableNoun(t.table) });
    ctx.initColumnOrder(t.table);
    ctx.initResize(t.table);
    const head = t.table.tHead.rows[0], cell = label => head.cells.find(c => c.getAttribute('data-label') === label);
    assert.deepStrictEqual(head.cells.map(c => c.getAttribute('data-label')), ['EOL', 'NAME', 'VERSION', 'COUNT'], id + ' stored order');
    assert.deepStrictEqual(head.cells.map(c => c.hidden), [false, false, true, false], id + ' stored visibility');
    assert.deepStrictEqual([cell('NAME').style.width, cell('EOL').style.width, cell('COUNT').style.width], ['150px', '222px', '100px'], id + ' stored widths');

    // later saves go to the same old keys
    head.emit('keydown', { target: cell('NAME').querySelector('.th-sort'), altKey: true, key: 'ArrowRight' });
    assert.deepStrictEqual(JSON.parse(win.storage.get(orderKey)), ['EOL:3', 'VERSION:1', 'COUNT:2', 'NAME:0'], id);
    const count = t.q('[data-column-toggle="COUNT"]');
    count.checked = false;
    count.emit('change');
    assert.deepStrictEqual(JSON.parse(win.storage.get(visibilityKey)), { NAME: true, VERSION: false, COUNT: false, EOL: true }, id);
    t.q('[data-column-resize="0"]').emit('keydown', { key: 'ArrowRight' });
    assert.deepStrictEqual(JSON.parse(win.storage.get('eol-ledger.table-widths.v1')), { ...otherWidths, [role]: { 0: 160, 3: 222 } }, id);

    // reset clears those same keys; a width reset rewrites the same role entry
    t.q('[data-columns-reset]').emit('click');
    t.q('[data-column-resize="3"]').emit('dblclick');
    assert.deepStrictEqual([win.storage.has(orderKey), win.storage.has(visibilityKey)], [false, false], id);
    assert.deepStrictEqual(JSON.parse(win.storage.get('eol-ledger.table-widths.v1')), { ...otherWidths, [role]: { 0: 160 } }, id);
    assert.deepStrictEqual([...win.storage.keys()].filter(k => k.includes(id) || k.includes(encodeURIComponent(id))), [], id + ' no id-keyed preference');
    assert.ok(!Object.prototype.hasOwnProperty.call(JSON.parse(win.storage.get('eol-ledger.table-widths.v1')), id), id);

    // the id still names the URL view, and preferences never enter it
    cell('COUNT').emit('click');
    assert.deepStrictEqual(viewOf(win, 'view.' + id), { v: 1, sort: { key: 'c2', dir: 'asc' } }, id);
    assert.deepStrictEqual([...new URL(win.location.href).searchParams.keys()], ['keep', 'view.' + id]);
    assert.strictEqual(new URL(win.location.href).hash, '#h');
  }
});

test('tables keep separate stable namespaces: a later (lazy) table never clobbers another, and an id-less table writes nothing', () => {
  const first = { v: 1, sort: { key: 'name', dir: 'desc' } };
  const { win, ctx } = viewDom('https://x.test/imports?view.import-history-table=' + encodeURIComponent(JSON.stringify(first)) + '#history');
  const a = viewTable('import-history-table');
  ctx.initTable(a.table);
  a.q('[data-page-next]').emit('click');
  const b = viewTable('snapshot-timeline-table');
  ctx.initTable(b.table);
  assert.deepStrictEqual(viewOf(win, 'view.import-history-table'), { ...first, page: 2 });
  b.th('count').emit('click');
  assert.deepStrictEqual(viewOf(win, 'view.import-history-table'), { ...first, page: 2 });
  assert.deepStrictEqual(viewOf(win, 'view.snapshot-timeline-table'), { v: 1, sort: { key: 'count', dir: 'asc' } });
  assert.deepStrictEqual(a.shown(), range(5, 1));
  assert.strictEqual(new URL(win.location.href).hash, '#history');

  const before = win.location.href;
  const anonymous = viewTable('');
  ctx.initTable(anonymous.table);
  anonymous.th('name').emit('click');
  assert.strictEqual(win.location.href, before);
  assert.ok(![...new URL(win.location.href).searchParams.keys()].some(k => /^view\.t\d/.test(k)), 'never a DOM counter');
});

test('popstate reapplies the URL: a removed param restores the defaults, a present one its state', () => {
  const { win, ctx } = viewDom('https://x.test/repos?x=1');
  const t = viewTable('code-repos-table');
  ctx.initTable(t.table);
  t.th('count').emit('click');
  t.th('count').emit('click');
  t.q('[data-filter-select-none="name"]').emit('click');
  assert.deepStrictEqual(t.shown(), []);

  win.location.href = 'https://x.test/repos?x=1';
  win.emit('popstate');
  assert.deepStrictEqual(t.shown(), range(1, 20));
  assert.strictEqual(t.th('count').getAttribute('aria-sort'), null);
  assert.strictEqual(t.th('name').getAttribute('data-filter-active'), 'false');
  assert.strictEqual(win.location.href, 'https://x.test/repos?x=1');

  win.location.href = 'https://x.test/repos?x=1&view.code-repos-table=' + encodeURIComponent('{"v":1,"sort":{"key":"count","dir":"desc"},"page":2}');
  win.emit('popstate');
  assert.deepStrictEqual(t.shown(), range(5, 1));
  assert.strictEqual(t.status(), 'Page 2 of 2');

  win.location.href = 'https://x.test/repos?x=1&view.code-repos-table=%7Bbroken';
  win.emit('popstate');
  assert.deepStrictEqual(t.shown(), range(1, 20));
  assert.strictEqual(win.location.href, 'https://x.test/repos?x=1', 'malformed state is dropped from the address bar');
});

test('dashboard: legacy start/end/sort seed the view when it has no param; an explicit param wins; clear all reloads without them', () => {
  const legacy = { 'data-start': '2026-01-03', 'data-end': '2026-01-04', 'data-sort': 'desc', 'data-legacy': 'true' };
  const opts = { noun: 'dependencies', defaultSort: EOL_ASC, legacy: true };
  const base = 'https://x.test/?start=2026-01-03&end=2026-01-04&sort=desc&unused=false&keep=1';
  const { win, ctx } = viewDom(base + '#main');
  const t = viewTable('dependencies-table', legacy);
  ctx.initTable(t.table, opts);
  assert.deepStrictEqual(t.shown(), ['pkg04', 'pkg03']);
  assert.deepStrictEqual(viewOf(win, 'view.dependencies-table'),
    { v: 1, filters: { eol: { mode: 'range', startText: '2026-01-03', endText: '2026-01-04' } }, sort: { key: 'eol', dir: 'desc' } });
  t.q('[data-filter-clear="eol"]').emit('click');
  t.th('eol').emit('click');
  assert.deepStrictEqual(viewOf(win, 'view.dependencies-table'), { v: 1 }, 'back to defaults still overrides the legacy bounds on reload');

  const explicit = viewDom(base + '&view.dependencies-table=' + encodeURIComponent('{"v":1}'));
  const u = viewTable('dependencies-table', legacy);
  explicit.ctx.initTable(u.table, opts);
  assert.deepStrictEqual(u.shown(), range(1, 20));
  assert.strictEqual(u.th('eol').getAttribute('aria-sort'), 'ascending');

  u.q('[data-filters-clear-all]').emit('click');
  const reload = new URL(explicit.win.assigned);
  assert.deepStrictEqual([...reload.searchParams], [['keep', '1']]);
  assert.strictEqual(reload.pathname, '/');
});

const css = fs.readFileSync(path.join(__dirname, '../../main/resources/static/app.css'), 'utf8');
const themeSource = fs.readFileSync(path.join(__dirname, '../../main/resources/static/theme.js'), 'utf8');

test('pre-paint gate: theme.js (synchronous, JS only) marks tables pending; app.css shows at most the first page until a table is ready', () => {
  const root = new FakeEl('html'), win = new FakeEl('window');
  win.localStorage = { getItem() { return null; }, setItem() {} };
  vm.runInContext(themeSource, vm.createContext({ window: win, document: { documentElement: root } }));
  assert.strictEqual(root.hasAttribute('data-tables-pending'), true, 'set while the streamed HTML is still parsing');
  win.emit('load');
  assert.strictEqual(root.hasAttribute('data-tables-pending'), false, 'a page whose app.js never ran shows every row after load');
  const ctx = load();
  assert.strictEqual(vm.runInContext('DEFAULT_PAGE_SIZE', ctx), 20);
  assert.match(css, /html\[data-tables-pending\] main table:not\(\[data-table-ready\]\) > tbody > tr:nth-child\(n \+ 21\) \{ display: none; \}/);
});

test('a table is marked ready only after its restored URL view (20/50/100 per page) is drawn; empty tables too', () => {
  for (const [size, shown, status] of [[null, 20, 'Page 1 of 2'], [20, 20, 'Page 1 of 2'], [50, 25, 'Page 1 of 1'], [100, 25, 'Page 1 of 1']]) {
    const href = 'https://x.test/repos' + (size ? '?view.code-repos-table=' + encodeURIComponent(JSON.stringify({ v: 1, size })) : '');
    const { ctx } = viewDom(href);
    const t = viewTable('code-repos-table');
    let atReady = null;
    const set = t.table.setAttribute.bind(t.table);
    t.table.setAttribute = (n, v) => { if (n === 'data-table-ready') atReady = [t.shown().length, t.status(), t.q('[data-page-size]').value]; set(n, v); };
    ctx.initTable(t.table);
    assert.deepStrictEqual(atReady, [shown, status, String(size || 20)], 'size ' + size + ': the gate lifts on the restored page, never before');
  }
  const { ctx } = viewDom('https://x.test/repos');
  const e = viewTable('empty-table');
  e.table.tBodies[0].childNodes.length = 0;
  ctx.initTable(e.table);
  assert.strictEqual(e.table.hasAttribute('data-table-ready'), true);
});

// ---- per-tab JSON navigation cache of server tables, through the real initTable ----
const SERVER_KEYS = ['purl', 'ecosystem', 'dependency', 'version', 'eol', 'status', 'repos', 'branches', 'context', 'cycle', 'evidence', 'source', 'sbom'];
const ROW = { product: 'django', cycle: '3.2', repos: 1, branches: 1, href: '/products/django', purl: 'pkg:pypi/django', ecosystem: 'pypi',
  dependency: 'django', version: '3.2.25', eol: '2024-04-01', status: 'Ended', statusCss: 'ended', context: '', cycleLabel: '3.2',
  matching: 'purl', evidence: [], sources: [], sboms: [] };
const PAGE = { page: 1, pages: 1, total: 1, matching: 1, rows: [ROW] };
const okJson = body => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(JSON.parse(JSON.stringify(body))) });
const flush = async () => { for (let i = 0; i < 8; i++) await new Promise(r => setImmediate(r)); };
/** A tab's sessionStorage over a Map; documents of one tab share the Map. */
const tabStorage = session => ({ get length() { return session.size; }, key: i => [...session.keys()][i] ?? null,
  getItem: k => (session.has(k) ? session.get(k) : null), setItem: (k, v) => { session.set(k, String(v)); }, removeItem: k => { session.delete(k); } });

/** One document load of the dashboard in a tab: `answer(url)` is the network; nav is the Navigation Timing type. */
function serverDoc(session, { nav = 'navigate', href = 'https://x.test/', answer = () => okJson(PAGE), sessionStorage = tabStorage(session), local = {}, attrs = {} } = {}) {
  const calls = [];
  const timers = [];
  const dom = viewDom(href, local, { sessionStorage, performance: { getEntriesByType: t => (t === 'navigation' ? [{ type: nav }] : []) } });
  dom.ctx.fetch = (url, init) => { calls.push(String(url)); return answer(String(url), init); };
  dom.ctx.setTimeout = fn => timers.push(fn);
  const main = new FakeEl('main'), wrap = new FakeEl('div'), table = new FakeEl('table'), thead = new FakeEl('thead'), head = new FakeEl('tr'), tbody = new FakeEl('tbody');
  wrap.className = 'table-wrap';
  table.id = 'dashboard-table';
  Object.entries({ 'data-server': '/api/dashboard', 'data-total': '1', ...attrs }).forEach(([k, v]) => table.setAttribute(k, v));
  SERVER_KEYS.forEach(k => { const th = new FakeEl('th'); th.setAttribute('data-column', k); th.append(k); head.append(th); });
  thead.append(head);
  table.append(thead, tbody);
  wrap.append(table);
  main.append(wrap);
  table.offsetWidth = 900;
  dom.ctx.initTable(table);
  const shown = () => tbody.rows.filter(tr => tr.hasAttribute('data-row') && !tr.hidden).map(tr => tr.getAttribute('data-row'));
  return { ...dom, calls, timers, table, head, shown, main };
}

test('tab cache: a dashboard view costs one request, then later documents of the tab draw it with no API call', async () => {
  const session = new Map();
  const a = serverDoc(session);
  await flush();
  assert.strictEqual(a.calls.length, 1, 'an unseen view costs one request');
  assert.deepStrictEqual(a.shown(), ['django 3.2']);
  const b = serverDoc(session);
  await flush();
  assert.deepStrictEqual(b.calls, [], 'a later document of the same tab repeats no request');
  assert.deepStrictEqual(b.shown(), ['django 3.2']);
});

test('tab cache: a normal or hard reload of either page drops every cached answer once, and only the cache', async () => {
  const session = new Map([['other.app.key', 'kept']]);
  const view = v => 'https://x.test/?view.dashboard-table=' + encodeURIComponent(JSON.stringify(v));
  serverDoc(session);
  serverDoc(session, { href: view({ v: 1, page: 1, size: 50 }) });
  await flush();
  assert.strictEqual([...session.keys()].filter(k => k.startsWith('eol-ledger.json-cache.v1.')).length, 2, 'distinct views are distinct entries');
  // a reload of another page of the tab (no server table) still invalidates both dashboard entries
  const other = viewDom('https://x.test/repos', { 'eol-ledger.page-size.v1.dashboard-table': '50' },
    { sessionStorage: tabStorage(session), performance: { getEntriesByType: () => [{ type: 'reload' }] } });
  assert.deepStrictEqual([...session.keys()], ['other.app.key'], 'owned keys gone, unrelated tab storage kept');
  assert.strictEqual(other.win.storage.get('eol-ledger.page-size.v1.dashboard-table'), '50', 'local preferences untouched');
  const again = serverDoc(session);
  await flush();
  assert.strictEqual(again.calls.length, 1, 'after a reload the view is fetched again, once');
  assert.deepStrictEqual(again.shown(), ['django 3.2']);
  // the reloaded document reads only after clearing: its own fresh answer is cached for the next navigation
  const next = serverDoc(session);
  await flush();
  assert.deepStrictEqual(next.calls, []);
});

const VALUES = [{ value: 'pypi', label: 'pypi' }, { value: '', label: '' }];
const answerAll = url => okJson(url.includes('/values') ? VALUES : PAGE);
const openMenu = async (d, k) => { d.main.querySelector('[data-filter-open="' + k + '"]').emit('click'); await flush(); };
const boxes = (d, k) => d.main.querySelectorAll('[data-filter-value="' + k + '"]').map(b => b.value);

test('tab cache: a column value list is fetched once per column, then reused by later documents of the tab', async () => {
  const session = new Map();
  const a = serverDoc(session, { answer: answerAll });
  await flush();
  await openMenu(a, 'ecosystem');
  assert.deepStrictEqual(a.calls.filter(u => u.includes('/values')), ['https://x.test/api/dashboard/values?column=ecosystem']);
  assert.deepStrictEqual(boxes(a, 'ecosystem'), ['pypi', '']);
  const b = serverDoc(session, { answer: answerAll });
  await flush();
  await openMenu(b, 'ecosystem');
  assert.deepStrictEqual(b.calls, [], 'the page and the value list both come from the tab cache');
  assert.deepStrictEqual(boxes(b, 'ecosystem'), ['pypi', '']);
  await openMenu(b, 'status');
  assert.deepStrictEqual(b.calls, ['https://x.test/api/dashboard/values?column=status'], 'another column is its own entry');
});

test('tab cache: a running catalogue sync never passively refetches or replaces the cached dashboard', async () => {
  const session = new Map();
  serverDoc(session);
  await flush();
  const status = { running: true, phase: 'products', productsDone: 3, productsTotal: 9 };
  const d = serverDoc(session, { attrs: { 'data-sync-running': 'true' },
    answer: url => okJson(url.includes('/catalog/sync/status') ? status : { ...PAGE, rows: [{ ...ROW, cycle: '4.2' }] }) });
  await flush();
  for (let i = 0; i < 3; i++) { d.timers.splice(0).forEach(fn => fn()); status.productsDone++; await flush(); }
  assert.deepStrictEqual(d.calls, [], 'no status polling and no page refetch on the dashboard');
  assert.deepStrictEqual(d.shown(), ['django 3.2'], 'the cached snapshot stays until a reload');
});

test('tab cache: submitting a local import, SBOM import, sync or clear form drops the cache; other submits do not', async () => {
  const session = new Map([['other.app.key', 'kept']]);
  serverDoc(session);
  await flush();
  const owned = () => [...session.keys()].filter(k => k.startsWith('eol-ledger.json-cache.v1.')).length;
  const imports = viewDom('https://x.test/imports', {}, { sessionStorage: tabStorage(session) });
  const get = new FakeEl('form');
  get.setAttribute('method', 'get');
  imports.docEvents.emit('submit', { target: get });
  assert.strictEqual(owned(), 1, 'a GET form changes nothing on the server');
  const post = new FakeEl('form');
  post.setAttribute('method', 'post');
  imports.docEvents.emit('submit', { target: post });
  assert.deepStrictEqual([...session.keys()], ['other.app.key']);
  const after = serverDoc(session);
  await flush();
  assert.strictEqual(after.calls.length, 1, 'the next dashboard visit fetches the changed data once');
});

test('tab cache: errors, rejections and malformed answers are never cached or drawn; corrupt or blocked storage is just a miss', async () => {
  const owned = s => [...s.keys()].filter(k => k.startsWith('eol-ledger.json-cache.v1.'));
  const bad = [() => Promise.resolve({ ok: false, status: 500, json: () => Promise.resolve(PAGE) }), () => Promise.reject(new Error('offline')),
    () => okJson({ ...PAGE, rows: [{ ...ROW, evidence: null }] }), () => okJson({ ...PAGE, page: '1' }), () => okJson([PAGE]), () => okJson(null)];
  for (const answer of bad) {
    const session = new Map();
    const d = serverDoc(session, { answer });
    await flush();
    assert.deepStrictEqual(owned(session), [], 'nothing cached for ' + answer);
    assert.deepStrictEqual(d.shown(), []);
    const retry = serverDoc(session);
    await flush();
    assert.strictEqual(retry.calls.length, 1, 'the next document asks the network again');
    assert.deepStrictEqual(retry.shown(), ['django 3.2']);
  }
  // corrupt or wrong-shaped cached entries are removed and refetched once
  for (const stored of ['{not json', '{"page":1}', 'null', '[]']) {
    const session = new Map();
    serverDoc(session);
    await flush();
    session.set(owned(session)[0], stored);
    const d = serverDoc(session);
    await flush();
    assert.strictEqual(d.calls.length, 1, stored);
    assert.deepStrictEqual(d.shown(), ['django 3.2']);
    assert.ok(validJson(session.get(owned(session)[0])), 'replaced by the fresh answer');
  }
  // blocked storage (private mode, policy) never breaks the table: every document just uses the network
  const throwing = { get length() { throw new Error('blocked'); }, key() { throw new Error('blocked'); }, getItem() { throw new Error('blocked'); },
    setItem() { throw new Error('blocked'); }, removeItem() { throw new Error('blocked'); } };
  for (let i = 0; i < 2; i++) {
    const d = serverDoc(new Map(), { sessionStorage: throwing, nav: 'reload' });
    await flush();
    assert.strictEqual(d.calls.length, 1);
    assert.deepStrictEqual(d.shown(), ['django 3.2']);
  }
});
const validJson = t => { try { return JSON.parse(t).rows.length === 1; } catch (e) { return false; } };

test('tab cache: an answer missing any field serverRow or a filter menu reads is rejected, from storage or the network', async () => {
  const hollow = { ...PAGE, rows: [{ evidence: [{}], sources: [{}], sboms: [{}] }] };
  const broken = [hollow, { ...PAGE, rows: [{ ...ROW, href: null }] }, { ...PAGE, rows: [{ ...ROW, repos: '1' }] },
    { ...PAGE, rows: [{ ...ROW, statusCss: 7 }] }, { ...PAGE, rows: [{ ...ROW, evidence: [{ href: '/x' }] }] },
    { ...PAGE, rows: [{ ...ROW, sources: [{ text: null, link: null }] }] }, { ...PAGE, rows: [{ ...ROW, sboms: [{ text: 'x', link: 5 }] }] }];
  const key = 'eol-ledger.json-cache.v1.https://x.test/api/dashboard?view=%7B%22v%22%3A1%7D';
  for (const bad of broken) {
    // stored: evicted and fetched again, never drawn
    const session = new Map([[key, JSON.stringify(bad)]]);
    const d = serverDoc(session);
    await flush();
    assert.strictEqual(d.calls.length, 1, 'stored ' + JSON.stringify(bad.rows[0]));
    assert.deepStrictEqual(d.shown(), ['django 3.2']);
    // network: not cached and not drawn; the next document retries
    const net = new Map();
    const n = serverDoc(net, { answer: () => okJson(bad) });
    await flush();
    assert.deepStrictEqual([...net.keys()], [], 'network ' + JSON.stringify(bad.rows[0]));
    assert.deepStrictEqual(n.shown(), []);
    assert.strictEqual(serverDoc(net).calls.length, 1);
  }
  // a null link (a source that is not a safe URL) is a real API value and stays valid
  const textOnly = { ...PAGE, rows: [{ ...ROW, sources: [{ text: 'Inline upload', link: null }], evidence: [{ href: '/repos/1/deps/2', text: 'Alpha / main: Details' }] }] };
  const ok = new Map();
  serverDoc(ok, { answer: () => okJson(textOnly) });
  await flush();
  assert.strictEqual([...ok.keys()].length, 1);
  // value lists need both value and label as strings
  const vals = new Map();
  const v = serverDoc(vals, { answer: url => okJson(url.includes('/values') ? [{ value: 'pypi' }] : PAGE) });
  await flush();
  await openMenu(v, 'ecosystem');
  assert.deepStrictEqual([...vals.keys()].filter(k => k.includes('/values')), [], 'a value without a label is not cached');
  assert.deepStrictEqual(boxes(v, 'ecosystem'), []);
});

// ---- /repos: the server-rendered Code repos rows are frozen per tab like the dashboard JSON ----
const REPO_HEAD = [['appid', 0, 'App ID'], ['name', 8, 'Repository name'], ['repo', 1, 'Code repo'], ['branch', 2, 'Branch'], ['version', 6, 'Version'],
  ['provider', 3, 'Provider'], ['format', 7, 'SBOM Format'], ['packages', 4, 'Packages'], ['snapshot', 5, 'Latest snapshot']];
const td = (column, ...parts) => { const c = new FakeEl('td'); if (column) c.setAttribute('data-column', column); parts.forEach(p => c.append(p)); return c; };
const el = (tag, text, attrs = {}) => { const e = new FakeEl(tag); Object.entries(attrs).forEach(([k, v]) => e.setAttribute(k, v)); e.append(text); return e; };
/** One /repos row exactly as repos.html renders it. */
function repoRow({ id, name, url = null, version = '—', branch = 'main' }) {
  const tr = new FakeEl('tr');
  tr.setAttribute('data-href', '/repos/' + id);
  const noscript = new FakeEl('noscript');
  noscript.append(el('a', 'Details', { href: '/repos/' + id }));
  const seen = td('snapshot', '2026-11-20 13:00');
  seen.className = 'date small';
  seen.setAttribute('data-value', '2026-11-20 13:00');
  const repo = td('repo', url ? el('a', url, { href: url, rel: 'noopener noreferrer' }) : el('span', '—'));
  repo.className = 'col-source';
  const packages = td('packages', '3');
  packages.className = 'num';
  tr.append(td('appid', '—'), td('name', name), repo, td('branch', el('span', branch), '\n    ', noscript), td('version', version), td('provider', 'gitlab'),
    td('format', 'CycloneDX 1.6 JSON'), packages, seen);
  return tr;
}
function emptyRepoRow() {
  const tr = new FakeEl('tr'), c = td(null, 'No code repos yet. ', el('a', 'Upload a dependency-inventory JSON', { href: '/imports' }), '.');
  c.setAttribute('colspan', '9');
  c.className = 'empty';
  tr.append(c);
  return tr;
}
/** One document load of /repos in a tab, in the wire-up order: the tab snapshot, then initTable. */
function reposDoc(session, repos, { nav = 'navigate', sessionStorage = tabStorage(session) } = {}) {
  const dom = viewDom('https://x.test/repos', {}, { sessionStorage, performance: { getEntriesByType: t => (t === 'navigation' ? [{ type: nav }] : []) } });
  const main = new FakeEl('main'), wrap = new FakeEl('div'), table = new FakeEl('table'), thead = new FakeEl('thead'), head = new FakeEl('tr'), tbody = new FakeEl('tbody');
  wrap.className = 'table-wrap';
  wrap.setAttribute('aria-label', 'Code repo branches');
  table.id = 'code-repos-table';
  REPO_HEAD.forEach(([, i, label]) => { const th = new FakeEl('th'); th.setAttribute('scope', 'col'); th.setAttribute('data-preference-index', String(i)); th.append(label); head.append(th); });
  (repos.length ? repos.map(repoRow) : [emptyRepoRow()]).forEach(tr => tbody.append(tr));
  thead.append(head);
  table.append(thead, tbody);
  wrap.append(table);
  main.append(wrap);
  table.offsetWidth = 900;
  if (dom.ctx.tabSnapshot) dom.ctx.tabSnapshot(table); // absent before the feature: the page then behaves as it always did
  dom.ctx.initTable(table);
  const rows = () => tbody.rows.filter(tr => !tr.hasAttribute('data-empty')).map(tr => tr.hasAttribute('data-href') ? tr.getAttribute('data-href') + ' ' + tr.cells[1].textContent : tr.textContent);
  return { ...dom, table, tbody, rows };
}

test('tab snapshot: /repos keeps the rows of its first visit in the tab, even after the data changed on the server', () => {
  const session = new Map();
  const first = reposDoc(session, [{ id: 1, name: 'Alpha', url: 'https://gitlab.com/acme/alpha' }]);
  assert.deepStrictEqual(first.rows(), ['/repos/1 Alpha']);
  // an import elsewhere (another tab, a scheduler) added a branch; navigating back in this tab shows the frozen rows
  const again = reposDoc(session, [{ id: 1, name: 'Alpha', url: 'https://gitlab.com/acme/alpha', version: '1.1' }, { id: 2, name: 'Lua' }]);
  assert.deepStrictEqual(again.rows(), ['/repos/1 Alpha']);
  const tr = again.tbody.rows[0];
  assert.strictEqual(tr.cells[4].textContent, '—', 'cell text of the first visit, not the changed version');
  assert.deepStrictEqual(tr.cells.map(c => c.getAttribute('data-column')), REPO_HEAD.map(h => h[0]));
  const link = tr.cells[2].querySelector('a');
  assert.deepStrictEqual([link.getAttribute('href'), link.getAttribute('rel'), link.textContent], ['https://gitlab.com/acme/alpha', 'noopener noreferrer', 'https://gitlab.com/acme/alpha']);
  assert.deepStrictEqual([tr.cells[8].className, tr.cells[8].getAttribute('data-value')], ['date small', '2026-11-20 13:00']);
});

test('tab snapshot: every row (also later pages) and an empty table are kept; a reload of / or /repos, or a local POST, drops both datasets', async () => {
  const many = Array.from({ length: 25 }, (_, i) => ({ id: i + 1, name: 'R' + (i + 1) }));
  const session = new Map();
  reposDoc(session, many);
  const back = reposDoc(session, []);
  assert.strictEqual(back.rows().length, 25, 'rows hidden on page 2 are part of the snapshot');
  serverDoc(session);
  await flush();
  assert.strictEqual([...session.keys()].length, 2, 'one dashboard answer and one /repos snapshot');
  serverDoc(session, { nav: 'reload' }); // a reload of the dashboard
  await flush();
  assert.deepStrictEqual(reposDoc(session, [{ id: 9, name: 'Fresh' }]).rows(), ['/repos/9 Fresh'], '/repos shows fresh rows after a dashboard reload');
  // an empty first visit stays empty, even after branches were imported elsewhere; a /repos reload then shows them
  const empty = new Map();
  assert.deepStrictEqual(reposDoc(empty, []).rows(), ['No code repos yet. Upload a dependency-inventory JSON.']);
  assert.deepStrictEqual(reposDoc(empty, [{ id: 1, name: 'Alpha' }]).rows(), ['No code repos yet. Upload a dependency-inventory JSON.']);
  assert.strictEqual(reposDoc(empty, [{ id: 1, name: 'Alpha' }]).tbody.querySelector('td').getAttribute('colspan'), '9');
  serverDoc(empty);
  await flush();
  assert.deepStrictEqual(reposDoc(empty, [{ id: 1, name: 'Alpha' }], { nav: 'reload' }).rows(), ['/repos/1 Alpha']);
  assert.strictEqual([...empty.keys()].filter(k => k.includes('/api/dashboard')).length, 0, 'the /repos reload dropped the dashboard answer too');
  // a local POST drops the snapshot as well
  const imports = viewDom('https://x.test/imports', {}, { sessionStorage: tabStorage(empty) });
  const post = new FakeEl('form');
  post.setAttribute('method', 'post');
  imports.docEvents.emit('submit', { target: post });
  assert.deepStrictEqual(reposDoc(empty, [{ id: 2, name: 'Beta' }]).rows(), ['/repos/2 Beta']);
});

test('tab snapshot: corrupt, unsafe or incomplete stored rows are never inserted; blocked storage shows the fresh rows', () => {
  const key = 'eol-ledger.json-cache.v1.rows./repos#code-repos-table';
  const cell = (column, parts = ['x']) => ({ attrs: { 'data-column': column }, parts });
  const good = cells => [{ attrs: { 'data-href': '/repos/7' }, cells }];
  const full = REPO_HEAD.map(h => cell(h[0]));
  for (const stored of ['{not json', '{}', '[1]', JSON.stringify(good(full.slice(1))), // a missing cell
    JSON.stringify([{ attrs: { 'data-href': 'javascript:alert(1)' }, cells: full }]),
    JSON.stringify([{ attrs: { 'data-href': '//evil.test/x' }, cells: full }]),
    JSON.stringify([{ attrs: { 'data-href': '/repos/7', onclick: 'x' }, cells: full }]),
    JSON.stringify(good([cell('appid', [{ tag: 'script', text: 'alert(1)' }]), ...full.slice(1)])),
    JSON.stringify(good([cell('appid', [{ tag: 'a', text: 'x', href: 'javascript:alert(1)' }]), ...full.slice(1)])),
    JSON.stringify(good([cell('appid', [{ tag: 'a', text: 'x', href: 'https://u:p@x.test/' }]), ...full.slice(1)])),
    JSON.stringify(good([{ attrs: { 'data-column': 'appid', style: 'x' }, parts: [] }, ...full.slice(1)])),
    JSON.stringify(good([cell('appid', ['<b>x</b>']), ...full.slice(1)])) /* text, so it would be shown literally; still valid */]) {
    const session = new Map([[key, stored]]);
    const d = reposDoc(session, [{ id: 1, name: 'Alpha' }]);
    if (stored.includes('<b>')) {
      assert.strictEqual(d.tbody.rows[0].cells[0].textContent, '<b>x</b>', 'stored text is inserted as text, never as markup');
      assert.strictEqual(d.tbody.rows[0].cells[0].querySelector('b'), null);
      continue;
    }
    assert.deepStrictEqual(d.rows(), ['/repos/1 Alpha'], stored);
    assert.ok(JSON.parse(session.get(key))[0].attrs['data-href'] === '/repos/1', 'replaced by the fresh rows');
  }
  const throwing = { getItem() { throw new Error('blocked'); }, setItem() { throw new Error('blocked'); }, removeItem() { throw new Error('blocked'); },
    get length() { throw new Error('blocked'); }, key() { throw new Error('blocked'); } };
  assert.deepStrictEqual(reposDoc(new Map(), [{ id: 1, name: 'Alpha' }], { sessionStorage: throwing, nav: 'reload' }).rows(), ['/repos/1 Alpha']);
});
