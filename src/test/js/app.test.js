// Pure-logic checks for the dashboard column prefs in app.js (run: node --test src/test/js/app.test.js). Browser behaviour is proven separately.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/app.js'), 'utf8');

function load(window, document) {
  const ctx = vm.createContext({ window: window || {}, document: document || { addEventListener() {}, getElementById() { return null; } } });
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
  dependency: { visible: true, width: 220 }, version: { visible: true, width: 120 },
  context: { visible: true, width: 240 }, purl: { visible: true, width: 280 }, cycle: { visible: true, width: 200 }, eol: { visible: true, width: 130 },
  status: { visible: true, width: 210 }, repos: { visible: true, width: 120 }, branches: { visible: true, width: 120 },
  evidence: { visible: true, width: 280 }, source: { visible: true, width: 260 }, sbom: { visible: true, width: 260 },
};

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

test('page range: out-of-range pages clamp, sizes other than 20/50/100 fall back to 20', () => {
  const { pageRange } = load();
  const r = (n, page, size) => plain(pageRange(n, page, size));
  assert.deepStrictEqual(r(21, 5, 20), { page: 2, pages: 2, start: 20, end: 21 }, 'past the end after results shrink');
  assert.deepStrictEqual(r(21, 0, 20), r(21, 1, 20));
  assert.deepStrictEqual(r(21, -3, 20), r(21, 1, 20));
  assert.deepStrictEqual(r(21, NaN, 20), r(21, 1, 20));
  assert.deepStrictEqual(r(0, 4, 100), { page: 1, pages: 1, start: 0, end: 0 });
  for (const bad of [0, -20, 10, 25, 1e9, NaN, '50', undefined]) assert.deepStrictEqual(r(45, 1, bad), r(45, 1, 20), String(bad));
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
  for (const excluded of [false, false, true, true, true, true]) handlers[0]({ target: { closest: () => excluded ? {} : null } });
  assert.strictEqual(toggles, 2);
  assert.strictEqual(handlers.length, 1);
});

function columnFixture(saved = new Map()) {
  function node(key) {
    const events = {};
    return { key, hidden: false, style: {}, events, attrs: {},
      classList: { add() {}, remove() {}, toggle() {} },
      addEventListener(name, fn) { (events[name] ||= []).push(fn); },
      emit(name, e = {}) { for (const fn of events[name] || []) fn(e); },
      getAttribute(name) { return name === 'data-column' || name === 'data-label' ? key : this.attrs[name] || null; },
      setAttribute(name, value) { this.attrs[name] = value; }, hasAttribute() { return true; },
      append(...children) { (this.children ||= []).push(...children); },
      appendChild(child) { (this.children ||= []).push(child); },
      getBoundingClientRect() { return { width: 120 }; },
      closest(selector) { return selector === 'th' ? this : null; },
      querySelector(selector) { return selector === '.th-sort' ? this.button : null; },
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
  const table = { id: 'fixture', dataset: {}, style: {}, classList: { add() {} }, offsetWidth: 360,
    tHead: { rows: [head] }, tBodies: [{ rows: [body] }], rows: [head, body], before(settings) { this.settings = settings; },
    closest() { return null; }, getAttribute() { return null; }, querySelectorAll: selector => selector === 'colgroup' ? [cols] : [], };
  const win = node('window');
  win.localStorage = { getItem: k => saved.get(k), setItem: (k, v) => saved.set(k, v), removeItem: k => saved.delete(k) };
  const created = [];
  const doc = { addEventListener() {}, elementFromPoint: () => heads[2], createElement() { const n = node(''); created.push(n); return n; } };
  const ctx = load(win, doc);
  ctx.initColumnOrder(table);
  const pointer = (target, x, y = 0) => ({ target, button: 0, pointerId: 1, clientX: x, clientY: y, preventDefault() {} });
  return { ctx, table, head, body, cols, heads, saved, win, pointer, created };
}

test('drag moves original header, body and col nodes together without changing values or widths; reset restores', () => {
  const f = columnFixture();
  const original = f.body.cells.slice(); original[0].style.width = '333px'; original[0].hidden = true;
  f.head.emit('pointerdown', f.pointer(f.heads[0], 0));
  f.head.emit('pointermove', f.pointer(f.heads[0], 20));
  f.head.emit('pointerup', f.pointer(f.heads[0], 20));
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

test('cancelled drags do not suppress keyboard sort clicks', () => {
  for (const cancellation of ['Escape', 'scroll', 'pointercancel', 'lostpointercapture']) {
    const f = columnFixture();
    f.head.emit('pointerdown', f.pointer(f.heads[0], 0));
    f.head.emit('pointermove', f.pointer(f.heads[0], 20));
    if (cancellation === 'Escape') f.head.emit('keydown', { key: 'Escape' });
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

test('storage uses only its own key', () => {
  const calls = [];
  const store = { getItem: (k) => { calls.push(['get', k]); return null; }, setItem: (k, v) => calls.push(['set', k, v]), removeItem: (k) => calls.push(['remove', k]) };
  const ctx = load({ localStorage: store });
  ctx.tableStorage('getItem'); ctx.tableStorage('setItem', '{}'); ctx.tableStorage('removeItem');
  assert.deepStrictEqual(calls, [['get', 'eol-ledger.dependencies-table.v1'], ['set', 'eol-ledger.dependencies-table.v1', '{}'], ['remove', 'eol-ledger.dependencies-table.v1']]);
});
