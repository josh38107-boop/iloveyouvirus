requireAuth();
startClock();

const CONFIRMATION_PHRASE = 'DELETE ALL ORDERS';
let maintenanceState = null;
let resetting = false;

const countGrid = document.getElementById('countGrid');
const deviceRows = document.getElementById('deviceRows');
const readinessSummary = document.getElementById('readinessSummary');
const phraseInput = document.getElementById('confirmationPhrase');
const salesStopped = document.getElementById('salesStopped');
const openResetDialog = document.getElementById('openResetDialog');
const resetDialog = document.getElementById('resetDialog');
const statusRegion = document.getElementById('maintenanceStatus');

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>'"]/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;'
  }[char]));
}

function canReset(state, phrase, stopped) {
  return Boolean(state?.allActiveDevicesReady && phrase === CONFIRMATION_PHRASE && stopped);
}

function setStatus(message, type = '') {
  statusRegion.textContent = message;
  statusRegion.className = `maintenance-status ${type}`.trim();
}

function updateResetButton() {
  openResetDialog.disabled = resetting ||
    !canReset(maintenanceState, phraseInput.value, salesStopped.checked);
}

function renderCounts(counts) {
  const duplicateSummary = maintenanceState?.duplicateSummary || {};
  const values = [
    ['Orders', counts.orders],
    ['Payments', counts.payments],
    ['Receipts', counts.receipts],
    ['Shifts', counts.shifts],
    ['Reward claims', counts.rewardClaims],
    ['Duplicate payments', duplicateSummary.duplicatePayments],
    ['Duplicate lines', duplicateSummary.duplicateOrderLines],
    ['Duplicate amount', formatPeso(duplicateSummary.duplicatePaymentAmountCents || 0)]
  ];
  countGrid.innerHTML = values.map(([label, value]) =>
    `<div class="count-card"><span class="count-label">${escapeHtml(label)}</span><span class="count-value">${typeof value === 'string' ? escapeHtml(value) : Number(value || 0).toLocaleString()}</span></div>`
  ).join('');
}

function renderDevices(devices, generation, allReady) {
  const active = devices.filter(device => device.status === 'active');
  readinessSummary.innerHTML = allReady
    ? `<strong>Ready.</strong> All ${active.length} active device${active.length === 1 ? '' : 's'} can safely apply generation ${generation + 1}.`
    : '<strong>Not ready.</strong> Install APK v1.9 and run Sync on every active device.';
  if (!devices.length) {
    deviceRows.innerHTML = '<tr><td colspan="5">No enrolled devices.</td></tr>';
    return;
  }
  deviceRows.innerHTML = devices.map(device => {
    const ready = device.ready;
    return `<tr>
      <td><strong>${escapeHtml(device.name)}</strong></td>
      <td>${device.role === 'manager' ? 'Manager Tablet' : 'Counter'}</td>
      <td>${device.status === 'active' ? 'Active' : 'Revoked'}</td>
      <td><span class="device-state ${ready ? 'ready' : ''}">${ready ? 'Ready' : 'Update required'}</span></td>
      <td>${device.acknowledgedResetGeneration} / ${generation}</td>
    </tr>`;
  }).join('');
}

async function loadStatus() {
  document.getElementById('refreshStatus').disabled = true;
  setStatus('Loading current Render status…');
  try {
    const state = await api.getDataMaintenance();
    if (!state) return;
    maintenanceState = state;
    renderCounts(state.counts);
    renderDevices(state.devices, state.generation, state.allActiveDevicesReady);
    setStatus(state.resetAt ? `Last reset: ${formatDate(state.resetAt)} by ${state.resetBy}.` : 'No cloud-wide reset has been performed.');
  } catch (error) {
    setStatus(error.message, 'error');
    readinessSummary.textContent = 'Unable to check device readiness.';
  } finally {
    document.getElementById('refreshStatus').disabled = false;
    updateResetButton();
  }
}

phraseInput.addEventListener('input', updateResetButton);
salesStopped.addEventListener('change', updateResetButton);
document.getElementById('refreshStatus').addEventListener('click', loadStatus);
document.getElementById('resetForm').addEventListener('submit', event => {
  event.preventDefault();
  if (!canReset(maintenanceState, phraseInput.value, salesStopped.checked)) return;
  resetDialog.showModal();
  document.getElementById('cancelReset').focus();
});
document.getElementById('cancelReset').addEventListener('click', () => resetDialog.close());
document.getElementById('confirmReset').addEventListener('click', async () => {
  resetting = true;
  updateResetButton();
  document.getElementById('confirmReset').disabled = true;
  document.getElementById('confirmReset').textContent = 'Resetting…';
  setStatus('Resetting Render and publishing the POS reset generation…');
  try {
    maintenanceState = await api.resetOperationalData({
      confirmation: phraseInput.value,
      salesStopped: salesStopped.checked,
      expectedGeneration: maintenanceState.generation
    });
    resetDialog.close();
    phraseInput.value = '';
    salesStopped.checked = false;
    renderCounts(maintenanceState.counts);
    renderDevices(maintenanceState.devices, maintenanceState.generation, maintenanceState.allActiveDevicesReady);
    setStatus('Order history, reports, and reward claims were reset successfully. Sync Manager Tablet first.', 'success');
  } catch (error) {
    resetDialog.close();
    setStatus(error.message, 'error');
    await loadStatus();
  } finally {
    resetting = false;
    document.getElementById('confirmReset').disabled = false;
    document.getElementById('confirmReset').textContent = 'Delete all order history';
    updateResetButton();
  }
});

loadStatus();
