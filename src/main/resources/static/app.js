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

var dependenciesTable = document.getElementById('dependencies-table');
if (dependenciesTable) initColumns(dependenciesTable, document.getElementById('table-settings'));
