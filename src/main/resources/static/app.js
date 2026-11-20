// Whole-row navigation for tables; every row also carries a real link for keyboard and assistive tech.
document.addEventListener('click', function (e) {
  var row = e.target.closest('tr[data-href]');
  if (!row || e.target.closest('a, button, input, select, label, summary')) return;
  if (window.getSelection && String(window.getSelection())) return;
  if (e.ctrlKey || e.metaKey || e.button === 1) { window.open(row.dataset.href, '_blank', 'noopener'); return; }
  window.location.assign(row.dataset.href);
});
