// Whole-row navigation for tables; every row also carries a real link for keyboard and assistive tech.
document.addEventListener('click', function (e) {
  var row = e.target.closest('tr[data-href]');
  if (!row || e.target.closest('a, button, input, select, label, summary, [data-column-resize]')) return;
  if (window.getSelection && String(window.getSelection())) return;
  if (e.ctrlKey || e.metaKey || e.button === 1) { window.open(row.dataset.href, '_blank', 'noopener'); return; }
  window.location.assign(row.dataset.href);
});

// Rows made focusable (dashboard with its link column hidden) open on Enter.
document.addEventListener('keydown', function (e) {
  if (e.key !== 'Enter' || !e.target.matches || !e.target.matches('tr[data-href][tabindex]')) return;
  if (e.ctrlKey || e.metaKey) { window.open(e.target.dataset.href, '_blank', 'noopener'); return; }
  window.location.assign(e.target.dataset.href);
});

// Dashboard column visibility and widths, kept in this browser only.
var TABLE_KEY = 'eol-ledger.dependencies-table.v1';
var MIN_WIDTH = 80;
var MAX_WIDTH = 1000;
var TABLE_COLUMNS = { dependency: 220, version: 120, eol: 130, status: 210, repos: 120, branches: 120 };

function clampWidth(n, fallback) {
  return typeof n === 'number' && isFinite(n) ? Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, Math.round(n))) : fallback;
}

function ownObject(o, key) {
  var v = o && typeof o === 'object' && Object.prototype.hasOwnProperty.call(o, key) ? o[key] : null;
  return v && typeof v === 'object' ? v : null;
}

/** Stored JSON text to fresh {key: {visible, width}} for the known columns; anything malformed falls back to the default. */
function tablePrefs(text) {
  var saved = null;
  try { saved = JSON.parse(text); } catch (e) { /* default */ }
  var cols = ownObject(saved, 'columns');
  var prefs = {};
  var shown = 0;
  Object.keys(TABLE_COLUMNS).forEach(function (key) {
    var c = ownObject(cols, key);
    var visible = c && Object.prototype.hasOwnProperty.call(c, 'visible') && typeof c.visible === 'boolean' ? c.visible : true;
    var width = c && Object.prototype.hasOwnProperty.call(c, 'width') ? c.width : null;
    prefs[key] = { visible: visible, width: clampWidth(width, TABLE_COLUMNS[key]) };
    if (visible) shown++;
  });
  if (!shown) Object.keys(prefs).forEach(function (key) { prefs[key].visible = true; });
  return prefs;
}

/** localStorage call on our own key; blocked or failing storage just means no persistence. */
function tableStorage(method, value) {
  try { return window.localStorage[method](TABLE_KEY, value); } catch (e) { return null; }
}

function initColumns(table, settings) {
  var keys = Object.keys(TABLE_COLUMNS);
  var prefs = tablePrefs(tableStorage('getItem'));
  var list = settings.querySelector('.col-settings');
  var empty = table.querySelector('td.empty');
  var col = {}, th = {}, toggle = {}, width = {}, handle = {};

  function apply() {
    var shown = keys.filter(function (k) { return prefs[k].visible; });
    var total = 0;
    keys.forEach(function (key) {
      var p = prefs[key];
      table.querySelectorAll('[data-column="' + key + '"]').forEach(function (el) { el.hidden = !p.visible; });
      col[key].style.width = th[key].style.width = p.width + 'px';
      toggle[key].checked = p.visible;
      toggle[key].disabled = p.visible && shown.length === 1; // at least one column stays visible
      width[key].value = p.width;
      handle[key].setAttribute('aria-valuenow', p.width);
      if (p.visible) total += p.width;
    });
    table.style.width = total + 'px';
    if (empty) empty.colSpan = shown.length;
    // The row link lives in the version column; without it the row itself takes focus and Enter.
    table.querySelectorAll('tbody tr[data-href]').forEach(function (row) {
      if (prefs.version.visible) row.removeAttribute('tabindex'); else row.tabIndex = 0;
    });
  }

  function save() { tableStorage('setItem', JSON.stringify({ columns: prefs })); }

  function resize(key, w) {
    prefs[key].width = clampWidth(w, prefs[key].width);
    apply();
  }

  keys.forEach(function (key) {
    col[key] = table.querySelector('col[data-column="' + key + '"]');
    th[key] = table.querySelector('th[data-column="' + key + '"]');
    var label = th[key].textContent.trim();

    var h = handle[key] = document.createElement('span');
    h.className = 'col-resize';
    h.tabIndex = 0;
    h.setAttribute('role', 'separator');
    h.setAttribute('aria-orientation', 'vertical');
    h.setAttribute('aria-label', 'Resize ' + label + ' column');
    h.setAttribute('aria-valuemin', MIN_WIDTH);
    h.setAttribute('aria-valuemax', MAX_WIDTH);
    h.setAttribute('data-column-resize', key);
    h.addEventListener('keydown', function (e) {
      var step = e.key === 'ArrowLeft' ? -10 : e.key === 'ArrowRight' ? 10 : 0;
      if (!step) return;
      e.preventDefault();
      resize(key, prefs[key].width + (e.shiftKey ? step * 5 : step));
      save();
    });
    h.addEventListener('pointerdown', function (e) {
      e.preventDefault();
      h.setPointerCapture(e.pointerId);
      var startX = e.clientX, startW = prefs[key].width;
      function move(ev) { resize(key, startW + ev.clientX - startX); }
      function up() {
        h.removeEventListener('pointermove', move);
        h.removeEventListener('pointerup', up);
        h.removeEventListener('pointercancel', up);
        save();
      }
      h.addEventListener('pointermove', move);
      h.addEventListener('pointerup', up);
      h.addEventListener('pointercancel', up);
    });
    th[key].appendChild(h);

    var row = document.createElement('div');
    row.className = 'col-setting';
    var show = document.createElement('label');
    var box = toggle[key] = document.createElement('input');
    box.type = 'checkbox';
    box.setAttribute('data-column-toggle', key);
    box.addEventListener('change', function () { prefs[key].visible = box.checked; apply(); save(); });
    show.append(box, ' ' + label);
    var size = document.createElement('label');
    var hint = document.createElement('span');
    hint.className = 'sr-only';
    hint.textContent = label + ' ';
    var num = width[key] = document.createElement('input');
    num.type = 'number';
    num.min = MIN_WIDTH;
    num.max = MAX_WIDTH;
    num.step = 1;
    num.setAttribute('data-column-width', key);
    num.addEventListener('change', function () { resize(key, num.valueAsNumber); save(); });
    size.append(hint, 'width (px) ', num);
    row.append(show, size);
    list.appendChild(row);
  });

  settings.querySelector('[data-columns-reset]').addEventListener('click', function () {
    tableStorage('removeItem');
    prefs = tablePrefs(null);
    apply();
  });

  table.classList.add('cols-sized');
  apply();
  settings.hidden = false;
}

// ---- Dashboard header sort and filters: page-local state, nothing stored. Pure helpers first (tested in Node). ----

var COLUMN_TYPES = { dependency: 'text', version: 'version', eol: 'date', status: 'text', repos: 'number', branches: 'number' };
var TEXT_OPS = { 'contains': 'Contains', 'not-contains': 'Does not contain', 'exact': 'Is exactly', 'not-exact': 'Is not exactly',
  'starts-with': 'Starts with', 'ends-with': 'Ends with', 'fuzzy': 'Fuzzy match', 'is-blank': 'Is blank', 'not-blank': 'Is not blank' };
var natural = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });

/** Typed comparison of two non-blank cell values: ISO dates, real numbers, else natural text (3.9 before 3.10). */
function compareTyped(type, a, b) {
  if (type === 'number') return Number(a) - Number(b);
  if (type === 'date') return a < b ? -1 : a > b ? 1 : 0;
  return natural.compare(a, b);
}

/** Row indexes in display order: blanks last in both directions, ties keep the original order. */
function sortOrder(values, type, dir) {
  var sign = dir === 'desc' ? -1 : 1;
  return values.map(function (v, i) { return i; }).sort(function (i, j) {
    var a = values[i], b = values[j];
    if (!a !== !b) return a ? -1 : 1;
    return (a && b && sign * compareTyped(type, a, b)) || i - j;
  });
}

/** One range bound typed by the user: {value} (null when blank) or {error}. */
function parseBound(type, text) {
  var t = String(text == null ? '' : text).trim();
  if (!t) return { value: null };
  if (type === 'number') return /^-?\d+(\.\d+)?$/.test(t) ? { value: t } : { error: 'Enter a number.' };
  if (type === 'date') {
    var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(t);
    var d = m && new Date(Date.UTC(+m[1], m[2] - 1, +m[3]));
    return d && d.getUTCMonth() === m[2] - 1 && d.getUTCDate() === +m[3] ? { value: t } : { error: 'Enter a real date as YYYY-MM-DD.' };
  }
  return { value: t };
}

/** Both bounds checked together: {start, end} or {error}; a reversed range is an error, never silently applied. */
function checkRange(type, startText, endText) {
  var s = parseBound(type, startText), e = parseBound(type, endText);
  if (s.error || e.error) return { error: (s.error ? 'From: ' + s.error : '') + (s.error && e.error ? ' ' : '') + (e.error ? 'To: ' + e.error : '') };
  if (s.value !== null && e.value !== null && compareTyped(type, s.value, e.value) > 0) return { error: 'From must be on or before To.' };
  return { start: s.value, end: e.value };
}

/** Optimal string alignment distance: edits plus adjacent transpositions (pyhton -> python is 1). */
function editDistance(a, b) {
  var d = [];
  for (var i = 0; i <= a.length; i++) {
    d[i] = [i];
    for (var j = 1; j <= b.length; j++) {
      d[i][j] = i === 0 ? j : Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
    }
  }
  return d[a.length][b.length];
}

/** Every query word must appear in the value, or be within a small typo distance of a value word (or of its start). */
function fuzzyMatches(value, query) {
  var v = value.toLowerCase();
  var words = v.split(/[^\p{L}\p{N}.]+/u).filter(Boolean);
  return query.toLowerCase().split(/\s+/).filter(Boolean).every(function (q) {
    if (v.indexOf(q) >= 0) return true;
    var allowed = q.length <= 3 ? 0 : q.length <= 6 ? 1 : 2;
    return words.some(function (w) { return editDistance(q, w) <= allowed || editDistance(q, w.slice(0, q.length)) <= allowed; });
  });
}

/** Literal, case-insensitive text operators (never a regex). A blank query leaves the value-taking operators inactive. */
function textMatches(op, value, query) {
  var v = String(value).toLowerCase(), q = String(query == null ? '' : query).trim().toLowerCase();
  if (op === 'is-blank') return !v.trim();
  if (op === 'not-blank') return !!v.trim();
  if (!q) return true;
  switch (op) {
    case 'not-contains': return v.indexOf(q) < 0;
    case 'exact': return v === q;
    case 'not-exact': return v !== q;
    case 'starts-with': return v.lastIndexOf(q, 0) === 0;
    case 'ends-with': return v.length >= q.length && v.indexOf(q, v.length - q.length) >= 0;
    case 'fuzzy': return fuzzyMatches(v, q);
    default: return v.indexOf(q) >= 0;
  }
}

/** A column's fresh filter: values mode with every value selected, i.e. inactive. */
function emptyFilter() {
  return { mode: 'values', selected: null, search: '', searchOp: 'contains', op: 'contains', text: '', start: null, end: null, startText: '', endText: '', error: '' };
}

/** The applied constraint of the selected mode only, as value -> boolean; null when it constrains nothing. */
function filterPredicate(f, type) {
  if (f.mode === 'values') {
    if (!f.selected) return null;
    var set = {};
    f.selected.forEach(function (v) { set['=' + v] = true; });
    return function (v) { return set['=' + v] === true; };
  }
  if (f.mode === 'text') {
    if (f.op !== 'is-blank' && f.op !== 'not-blank' && !String(f.text).trim()) return null;
    return function (v) { return textMatches(f.op, v, f.text); };
  }
  if (f.start === null && f.end === null) return null;
  return function (v) {
    return !!v && (f.start === null || compareTyped(type, v, f.start) >= 0) && (f.end === null || compareTyped(type, v, f.end) <= 0);
  };
}

/** Rows (arrays of {key: value}) that pass every active column filter: AND across columns. */
function visibleRows(rows, filters) {
  var preds = Object.keys(filters).map(function (k) { return [k, filterPredicate(filters[k], COLUMN_TYPES[k])]; })
    .filter(function (p) { return p[1]; });
  return rows.map(function (r) { return preds.every(function (p) { return p[1](r[p[0]]); }); });
}

/** Clears one column's filter (other filters and the sort kept) or, without a key, every filter plus the default EoL date ascending sort. */
function clearedState(state, key) {
  var next = { filters: Object.assign({}, state && state.filters), sort: key ? state.sort : { key: 'eol', dir: 'asc' } };
  (key ? [key] : Object.keys(COLUMN_TYPES)).forEach(function (k) { next.filters[k] = emptyFilter(); });
  return next;
}

/**
 * Header menu box for a trigger at top..bottom in a viewport vh tall: below it, or above when below is short and above
 * has more room; always inside the viewport, with the menu scrolling itself when taller than the room.
 */
function menuBox(top, bottom, height, vh) {
  var below = vh - bottom - 12, above = top - 12;
  var up = height > below && above > below;
  var room = Math.min(vh - 16, Math.max(up ? above : below, Math.min(height, 120)));
  var h = Math.min(height, room);
  var y = up ? top - 4 - h : bottom + 4;
  return { top: Math.max(8, Math.min(y, vh - 8 - h)), maxHeight: room };
}

/** Values selection after a checkbox change; selecting every distinct value means "no constraint" again. */
function toggleValue(selected, all, value, on) {
  var next = (selected || all).filter(function (v) { return v !== value; });
  if (on) next.push(value);
  return next.length === all.length ? null : next;
}

function initFilters(table) {
  var keys = Object.keys(COLUMN_TYPES);
  var trs = Array.prototype.slice.call(table.querySelectorAll('tbody tr[data-row]'));
  var emptyRow = table.querySelector('tbody tr[data-empty]');
  var tbody = trs.length ? trs[0].parentNode : null;
  var data = trs.map(function (tr) {
    var r = {};
    keys.forEach(function (k) { r[k] = tr.querySelector('td[data-column="' + k + '"]').getAttribute('data-value') || ''; });
    return r;
  });
  var labels = {}, th = {}, menus = {}, sortButtons = {}, openButtons = {}, distinct = {};
  var filters = {}, sort;
  var openKey = null;

  var bar = document.createElement('div');
  bar.className = 'filter-bar';
  var count = document.createElement('p');
  count.setAttribute('data-filter-count', '');
  count.setAttribute('role', 'status');
  var clearAll = document.createElement('button');
  clearAll.type = 'button';
  clearAll.className = 'button-quiet';
  clearAll.setAttribute('data-filters-clear-all', '');
  clearAll.textContent = 'Clear all filters';
  bar.append(count, clearAll);
  table.closest('.table-wrap').before(bar);

  function reset(k) {
    var s = clearedState({ filters: filters, sort: sort }, k);
    filters = s.filters;
    sort = s.sort;
  }

  function render() {
    var shown = visibleRows(data, filters);
    var n = 0;
    trs.forEach(function (tr, i) { tr.hidden = !shown[i]; if (shown[i]) n++; });
    sortOrder(data.map(function (r) { return r[sort.key]; }), COLUMN_TYPES[sort.key], sort.dir)
      .forEach(function (i) { tbody.insertBefore(trs[i], emptyRow); });
    if (emptyRow) emptyRow.hidden = n > 0;
    var active = keys.filter(function (k) { return filterPredicate(filters[k], COLUMN_TYPES[k]); });
    keys.forEach(function (k) {
      var on = active.indexOf(k) >= 0;
      th[k].setAttribute('data-filter-active', String(on));
      openButtons[k].setAttribute('aria-label', 'Filter ' + labels[k] + (on ? ' (filter active)' : ''));
      if (sort.key === k) th[k].setAttribute('aria-sort', sort.dir === 'asc' ? 'ascending' : 'descending');
      else th[k].removeAttribute('aria-sort');
      sortButtons[k].lastChild.textContent = sort.key === k ? (sort.dir === 'asc' ? ' ▲' : ' ▼') : '';
    });
    count.textContent = n + ' of ' + trs.length + ' dependency versions'
      + (active.length ? '; filtered by ' + active.map(function (k) { return labels[k]; }).join(', ') : '');
    place(); // a range error line can change the open menu's height
  }

  // Writes one column's state into its menu controls (after a clear, or on first build).
  function sync(k) {
    var f = filters[k], m = menus[k];
    m.mode.value = f.mode;
    m.search.value = f.search;
    m.searchOp.value = f.searchOp;
    m.op.value = f.op;
    m.text.value = f.text;
    m.text.disabled = f.op === 'is-blank' || f.op === 'not-blank';
    m.start.value = f.startText;
    m.end.value = f.endText;
    m.error.textContent = f.mode === 'range' ? f.error : '';
    [m.start, m.end].forEach(function (el) { el.setAttribute('aria-invalid', String(!!f.error)); });
    m.boxes.forEach(function (box) {
      box.checked = !f.selected || f.selected.indexOf(box.value) >= 0;
      // The search only hides list entries; checked values outside it stay selected.
      box.parentNode.hidden = !textMatches(f.searchOp, box.parentNode.textContent.trim(), f.search);
    });
    m.sections.forEach(function (s) { s.hidden = s.getAttribute('data-mode') !== f.mode; });
    place(); // a mode or search change alters the open menu's height
  }

  function control(tag, attr, k, label) {
    var el = document.createElement(tag);
    el.setAttribute(attr, k);
    if (label) el.setAttribute('aria-label', label);
    return el;
  }

  function options(select, map) {
    Object.keys(map).forEach(function (v) { select.add(new Option(map[v], v)); });
  }

  function field(text, el) {
    var l = document.createElement('label');
    l.append(text + ' ', el);
    return l;
  }

  function button(attr, k, text) {
    var b = control('button', attr, k);
    b.type = 'button';
    b.className = 'button-quiet';
    b.textContent = text;
    return b;
  }

  function place() {
    if (!openKey) return;
    var m = menus[openKey].root, r = openButtons[openKey].getBoundingClientRect();
    var scrolled = m.scrollTop;
    m.style.maxHeight = 'none'; // measure the content's own height for the current mode
    var box = menuBox(r.top, r.bottom, m.offsetHeight, window.innerHeight);
    m.style.top = box.top + 'px';
    m.style.maxHeight = box.maxHeight + 'px';
    m.scrollTop = scrolled;
    m.style.left = Math.max(8, Math.min(r.left, window.innerWidth - m.offsetWidth - 8)) + 'px';
  }

  function close(focus) {
    if (!openKey) return;
    var k = openKey;
    openKey = null;
    menus[k].root.hidden = true;
    openButtons[k].setAttribute('aria-expanded', 'false');
    if (focus) openButtons[k].focus();
  }

  function open(k) {
    close(false);
    openKey = k;
    menus[k].root.hidden = false;
    openButtons[k].setAttribute('aria-expanded', 'true');
    place();
    menus[k].mode.focus();
  }

  function build(k) {
    var f = function () { return filters[k]; };
    var type = COLUMN_TYPES[k];
    var root = control('div', 'data-column-filter', k);
    root.className = 'filter-menu';
    root.id = 'filter-menu-' + k;
    root.setAttribute('role', 'dialog');
    root.setAttribute('aria-label', 'Filter ' + labels[k]);
    root.hidden = true;

    var mode = control('select', 'data-filter-mode', k);
    options(mode, { values: 'Values', text: 'Text', range: 'Range' });
    mode.addEventListener('change', function () { f().mode = mode.value; sync(k); render(); });

    var vals = document.createElement('div');
    vals.setAttribute('data-mode', 'values');
    var searchOp = control('select', 'data-filter-search-op', k, 'Match values by');
    options(searchOp, { contains: 'Contains', exact: 'Is exactly', fuzzy: 'Fuzzy match' });
    var search = control('input', 'data-filter-search', k, 'Search ' + labels[k] + ' values');
    search.type = 'search';
    search.placeholder = 'Search values';
    // Searching only narrows the list; rows change when a checkbox does.
    function narrow() { f().search = search.value; f().searchOp = searchOp.value; sync(k); }
    search.addEventListener('input', narrow);
    searchOp.addEventListener('change', narrow);
    var all = button('data-filter-select-all', k, 'Select all values');
    var none = button('data-filter-select-none', k, 'Select no values');
    all.addEventListener('click', function () { f().selected = null; sync(k); render(); });
    none.addEventListener('click', function () { f().selected = []; sync(k); render(); });
    var list = document.createElement('div');
    list.className = 'filter-values';
    var seen = {};
    var boxes = [];
    sortOrder(data.map(function (r) { return r[k]; }), type, 'asc').forEach(function (i) {
      var v = data[i][k];
      if (seen['=' + v]) return;
      seen['=' + v] = true;
      var box = control('input', 'data-filter-value', k);
      box.type = 'checkbox';
      box.value = v;
      box.addEventListener('change', function () { f().selected = toggleValue(f().selected, distinct[k], v, box.checked); render(); });
      var l = document.createElement('label');
      // Cell text, e.g. "Not published" for a blank EoL date; "(blank)" when the cell has none.
      l.append(box, ' ' + (trs[i].querySelector('td[data-column="' + k + '"]').textContent.trim() || '(blank)'));
      list.appendChild(l);
      boxes.push(box);
    });
    distinct[k] = boxes.map(function (b) { return b.value; });
    var picks = document.createElement('div');
    picks.className = 'actions';
    picks.append(all, none);
    vals.append(field('Search', searchOp), search, picks, list);

    var txt = document.createElement('div');
    txt.setAttribute('data-mode', 'text');
    var op = control('select', 'data-filter-operator', k);
    options(op, TEXT_OPS);
    var text = control('input', 'data-filter-text', k, labels[k] + ' text');
    text.type = 'text';
    text.placeholder = 'Text';
    function textChanged() { f().op = op.value; f().text = text.value; text.disabled = op.value === 'is-blank' || op.value === 'not-blank'; render(); }
    op.addEventListener('change', textChanged);
    text.addEventListener('input', textChanged);
    txt.append(field('Operator', op), text);

    var rng = document.createElement('div');
    rng.setAttribute('data-mode', 'range');
    var hint = type === 'date' ? 'YYYY-MM-DD' : type === 'number' ? 'Number' : 'Text';
    var start = control('input', 'data-filter-start', k);
    var end = control('input', 'data-filter-end', k);
    var error = document.createElement('p');
    error.className = 'filter-error';
    error.setAttribute('role', 'alert');
    error.id = 'filter-error-' + k;
    [start, end].forEach(function (el) {
      el.type = 'text';
      el.placeholder = hint;
      if (type === 'number') el.inputMode = 'decimal';
      el.setAttribute('aria-describedby', error.id);
      el.addEventListener('input', function () {
        var c = checkRange(type, start.value, end.value);
        f().startText = start.value;
        f().endText = end.value;
        f().error = c.error || '';
        // An invalid draft keeps the last valid bounds applied.
        if (!c.error) { f().start = c.start; f().end = c.end; }
        error.textContent = f().error;
        [start, end].forEach(function (x) { x.setAttribute('aria-invalid', String(!!c.error)); });
        render();
      });
    });
    rng.append(field('From', start), field('To', end), document.createTextNode('Both inclusive; blank is open.'));

    var clear = button('data-filter-clear', k, 'Clear filter');
    clear.addEventListener('click', function () { reset(k); sync(k); render(); mode.focus(); });

    root.append(field('Filter by', mode), vals, txt, rng, error, clear);
    // Tabbing out of the menu closes it; clicks on its own blank areas do not.
    root.addEventListener('focusout', function (e) {
      if (openKey === k && e.relatedTarget && !root.contains(e.relatedTarget) && e.relatedTarget !== openButtons[k]) close(false);
    });
    menus[k] = { root: root, mode: mode, search: search, searchOp: searchOp, op: op, text: text, start: start, end: end, error: error,
      boxes: boxes, sections: [vals, txt, rng] };
    return root;
  }

  keys.forEach(function (k) {
    var cell = th[k] = table.querySelector('th[data-column="' + k + '"]');
    var title = cell.firstChild;
    labels[k] = title.textContent.trim();
    var s = sortButtons[k] = control('button', 'data-column-sort', k);
    s.type = 'button';
    s.className = 'th-sort';
    var ind = document.createElement('span');
    ind.setAttribute('aria-hidden', 'true');
    s.append(labels[k], ind);
    s.addEventListener('click', function () {
      sort = { key: k, dir: sort.key === k && sort.dir === 'asc' ? 'desc' : 'asc' };
      render();
    });
    var o = openButtons[k] = control('button', 'data-filter-open', k);
    o.type = 'button';
    o.className = 'th-filter';
    o.textContent = '▾';
    o.setAttribute('aria-haspopup', 'dialog');
    o.setAttribute('aria-expanded', 'false');
    o.setAttribute('aria-controls', 'filter-menu-' + k);
    o.addEventListener('click', function () { if (openKey === k) close(true); else open(k); });
    title.replaceWith(s, o);
    // In the header for natural tab order; position: fixed keeps it clear of the table's scroll clipping.
    cell.appendChild(build(k));
  });

  document.addEventListener('pointerdown', function (e) {
    if (openKey && !menus[openKey].root.contains(e.target) && !openButtons[openKey].contains(e.target)) {
      var inMenu = menus[openKey].root.contains(document.activeElement);
      close(inMenu);
    }
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && openKey) { e.preventDefault(); close(true); }
  });
  window.addEventListener('resize', place);
  window.addEventListener('scroll', function (e) {
    if (openKey && !menus[openKey].root.contains(e.target)) place(); // the menu's own scrolling does not move it
  }, true);

  clearAll.addEventListener('click', function () {
    // A legacy ?unused=true page holds rows the default omits: reload the default view (column prefs are stored, so they stay).
    if (table.getAttribute('data-legacy') === 'true') { window.location.assign(window.location.pathname); return; }
    close(false);
    reset();
    keys.forEach(sync);
    render();
  });

  reset();
  // Legacy ?start=&end=&sort= links (validated by the server) become the initial header state.
  var legacy = checkRange('date', table.getAttribute('data-start'), table.getAttribute('data-end'));
  if (!legacy.error && (legacy.start || legacy.end)) {
    filters.eol = Object.assign(emptyFilter(), { mode: 'range', start: legacy.start, end: legacy.end, startText: legacy.start || '', endText: legacy.end || '' });
  }
  if (table.getAttribute('data-sort') === 'desc') sort.dir = 'desc';
  keys.forEach(sync);
  render();
}

var dependenciesTable = document.getElementById('dependencies-table');
if (dependenciesTable) {
  initColumns(dependenciesTable, document.getElementById('table-settings'));
  initFilters(dependenciesTable);
}

// Code repos rows open their branch on Enter too; the repo URL inside stays an ordinary external link.
var reposTable = document.getElementById('code-repos-table');
if (reposTable) reposTable.querySelectorAll('tbody tr[data-href]').forEach(function (row) { row.tabIndex = 0; });
