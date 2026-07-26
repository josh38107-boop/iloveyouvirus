requireAuth();
startClock();

const loadingEl = document.getElementById('settingsLoading');
const formEl = document.getElementById('businessDayForm');
const cutoffInput = document.getElementById('cutoffTime');
const cutoffError = document.getElementById('cutoffError');
const noticeEl = document.getElementById('settingsNotice');
const exampleEl = document.getElementById('exampleWindow');
const timezoneEl = document.getElementById('timezoneValue');
const businessDateEl = document.getElementById('businessDateValue');
const openShiftCountEl = document.getElementById('openShiftCount');
const openShiftListEl = document.getElementById('openShiftList');
const saveButton = document.getElementById('saveBusinessDay');
const cardEl = document.getElementById('businessDayCard');

let currentSettings = null;

function escapeHtml(val) {
  return String(val ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  })[c]);
}

function minutesToTime(minutes) {
  const safe = Number.isInteger(minutes) ? Math.max(0, Math.min(1439, minutes)) : 120;
  return `${String(Math.floor(safe / 60)).padStart(2, '0')}:${String(safe % 60).padStart(2, '0')}`;
}

function timeToMinutes(value) {
  const match = String(value || '').match(/^(\d{2}):(\d{2})$/);
  if (!match) return null;
  const hours = Number(match[1]);
  const minutes = Number(match[2]);
  if (!Number.isInteger(hours) || !Number.isInteger(minutes) || hours > 23 || minutes > 59) return null;
  return hours * 60 + minutes;
}

function formatWindow(startMs, endMs) {
  return `${formatDate(startMs)} through ${formatDate(endMs - 1)}`;
}

function updateExample() {
  const minutes = timeToMinutes(cutoffInput.value);
  if (minutes == null) {
    exampleEl.textContent = 'Enter a valid time.';
    return;
  }
  const time = minutesToTime(minutes);
  exampleEl.textContent = `Example: a July 26 business day runs from ${time} on July 26 through one minute before ${time} on July 27.`;
}

function render(settings) {
  currentSettings = settings;
  const openShifts = Array.isArray(settings.openShifts) ? settings.openShifts : [];
  cutoffInput.value = minutesToTime(Number(settings.cutoffMinutes));
  timezoneEl.textContent = settings.timezone || 'Asia/Manila';
  businessDateEl.textContent = settings.currentBusinessDate || '-';
  openShiftCountEl.textContent = String(openShifts.length);
  if (settings.currentWindow) {
    exampleEl.textContent = `Current window: ${formatWindow(settings.currentWindow.startMs, settings.currentWindow.endMs)}`;
  } else {
    updateExample();
  }
  openShiftListEl.innerHTML = openShifts.map(shift => `
    <div class="open-shift-row">
      Shift ${escapeHtml(shift.id)} on ${escapeHtml(shift.device_id || 'unknown device')} opened ${escapeHtml(formatDate(shift.opened_at))}
    </div>
  `).join('');
  const hasOpenShifts = openShifts.length > 0;
  saveButton.disabled = hasOpenShifts;
  noticeEl.className = hasOpenShifts ? 'settings-notice error' : 'settings-notice';
  noticeEl.textContent = hasOpenShifts
    ? 'Close and sync every open shift before changing this cutoff.'
    : '';
  loadingEl.hidden = true;
  formEl.hidden = false;
  cardEl.setAttribute('aria-busy', 'false');
}

async function loadSettings() {
  loadingEl.hidden = false;
  formEl.hidden = true;
  cardEl.setAttribute('aria-busy', 'true');
  try {
    render(await api.getBusinessDaySettings());
  } catch (err) {
    loadingEl.hidden = true;
    formEl.hidden = false;
    noticeEl.className = 'settings-notice error';
    noticeEl.textContent = `Could not load settings: ${err.message}`;
    cardEl.setAttribute('aria-busy', 'false');
  }
}

cutoffInput.addEventListener('input', () => {
  cutoffError.textContent = '';
  noticeEl.textContent = '';
  noticeEl.className = 'settings-notice';
  updateExample();
});

formEl.addEventListener('submit', async event => {
  event.preventDefault();
  cutoffError.textContent = '';
  noticeEl.textContent = '';
  const cutoffMinutes = timeToMinutes(cutoffInput.value);
  if (cutoffMinutes == null) {
    cutoffError.textContent = 'Enter a valid cutoff time.';
    cutoffInput.focus();
    return;
  }
  saveButton.disabled = true;
  saveButton.textContent = 'Saving...';
  try {
    const saved = await api.updateBusinessDaySettings({ cutoffMinutes });
    render(saved);
    noticeEl.className = 'settings-notice success';
    noticeEl.textContent = 'Business-day cutoff saved. Sync every tablet before opening the next shift.';
  } catch (err) {
    if (currentSettings) render(currentSettings);
    noticeEl.className = 'settings-notice error';
    noticeEl.textContent = err.message || 'Save failed. Reload and try again.';
  } finally {
    saveButton.textContent = 'Save cutoff';
    if (!currentSettings?.openShifts?.length) saveButton.disabled = false;
  }
});

loadSettings();
