requireAuth();
startClock();

const PAGE_SIZE = 20;
let currentPromotion = null;
let claimOffset = 0;
let pendingSave = null;

const promotionCard = document.getElementById('promotionCard');
const promotionForm = document.getElementById('promotionForm');
const promotionLoading = document.getElementById('promotionLoading');
const enabledInput = document.getElementById('promotionEnabled');
const enabledLabel = document.getElementById('promotionEnabledLabel');
const intervalInput = document.getElementById('ordersPerReward');
const urlInput = document.getElementById('googleFormUrl');
const urlError = document.getElementById('urlError');
const notice = document.getElementById('promotionNotice');
const saveButton = document.getElementById('savePromotion');
const confirmDialog = document.getElementById('promotionConfirm');
const claimStatus = document.getElementById('claimStatus');

function showNotice(element, message, type = '') {
  element.textContent = message;
  element.className = `promotion-notice ${type}`.trim();
}

function validateGoogleFormTemplate(enabled, template) {
  if (!enabled) return '';
  let url;
  try {
    url = new URL(template);
  } catch {
    return 'Enter a valid Google Forms prefilled URL.';
  }
  if (url.protocol !== 'https:' || url.hostname !== 'docs.google.com' || !url.pathname.includes('/forms/')) {
    return 'Use an HTTPS prefilled URL from Google Forms.';
  }
  const claimEntries = [...new Set(
    [...url.searchParams.keys()].filter(key => /^entry\.\d+$/.test(key))
  )];
  return claimEntries.length === 1
    ? ''
    : 'Prefill only the Claim Code question, then paste the generated link here.';
}

function buildClaimUrl(template, claimCode) {
  const url = new URL(template);
  const claimEntry = [...url.searchParams.keys()].find(key => /^entry\.\d+$/.test(key));
  url.searchParams.set(claimEntry, claimCode);
  return url.toString();
}

function updateEnabledLabel() {
  enabledLabel.textContent = enabledInput.checked ? 'Enabled' : 'Disabled';
}

function updateUrlValidation() {
  const error = validateGoogleFormTemplate(enabledInput.checked, urlInput.value.trim());
  urlError.textContent = error;
  urlInput.setAttribute('aria-invalid', String(Boolean(error)));
  return error;
}

function renderPromotion(config) {
  currentPromotion = config;
  enabledInput.checked = config.enabled;
  intervalInput.value = String(config.ordersPerReward);
  urlInput.value = config.googleFormUrlTemplate || '';
  document.getElementById('cycleProgress').textContent = `${config.cycleProgress} / ${config.ordersPerReward}`;
  document.getElementById('rewardInterval').textContent = config.ordersPerReward.toLocaleString('en-PH');
  document.getElementById('lifetimeOrders').textContent = config.lifetimeOrderCount.toLocaleString('en-PH');
  const progress = document.getElementById('cycleProgressBar');
  progress.max = Math.max(config.ordersPerReward, 1);
  progress.value = Math.min(config.cycleProgress, progress.max);
  updateEnabledLabel();
  updateUrlValidation();
  promotionLoading.hidden = true;
  promotionForm.hidden = false;
  promotionCard.setAttribute('aria-busy', 'false');
}

async function loadPromotion() {
  promotionCard.setAttribute('aria-busy', 'true');
  promotionLoading.hidden = false;
  promotionForm.hidden = true;
  showNotice(notice, '');
  try {
    const config = await api.getPromotion();
    if (config) renderPromotion(config);
  } catch (error) {
    promotionLoading.innerHTML = '';
    const message = document.createElement('span');
    message.textContent = error.message || 'Unable to load promotion settings.';
    promotionLoading.appendChild(message);
    promotionCard.setAttribute('aria-busy', 'false');
  }
}

function draftPromotion() {
  return {
    enabled: enabledInput.checked,
    ordersPerReward: Number(intervalInput.value),
    googleFormUrlTemplate: urlInput.value.trim(),
    expectedUpdatedAt: currentPromotion.updatedAt
  };
}

function validateDraft(draft) {
  if (!Number.isInteger(draft.ordersPerReward) || draft.ordersPerReward < 1 || draft.ordersPerReward > 100000) {
    intervalInput.focus();
    return 'Orders per QR reward must be from 1 to 100,000.';
  }
  const templateError = validateGoogleFormTemplate(draft.enabled, draft.googleFormUrlTemplate);
  if (templateError) {
    urlInput.focus();
    return templateError;
  }
  return '';
}

function confirmationMessage(draft) {
  const messages = [];
  if (draft.ordersPerReward !== currentPromotion.ordersPerReward) {
    messages.push(`Change the reward interval from ${currentPromotion.ordersPerReward.toLocaleString('en-PH')} to ${draft.ordersPerReward.toLocaleString('en-PH')} orders. Current cycle progress (${currentPromotion.cycleProgress}) will reset to zero.`);
  }
  if (currentPromotion.enabled && !draft.enabled) {
    messages.push('Disable new reward winners. Existing issued claims will remain redeemable.');
  }
  return messages.join(' ');
}

async function savePromotion(draft) {
  saveButton.disabled = true;
  saveButton.textContent = 'Saving…';
  showNotice(notice, '');
  try {
    const saved = await api.updatePromotion(draft);
    renderPromotion(saved);
    showNotice(notice, 'Promotion settings saved. Connected POS devices will refresh when POS or Settings is opened.', 'success');
  } catch (error) {
    const message = error.message || 'Unable to save promotion settings.';
    if (message.includes('another session')) await loadPromotion();
    showNotice(notice, message, 'error');
  } finally {
    saveButton.disabled = false;
    saveButton.textContent = 'Save promotion';
  }
}

promotionForm.addEventListener('submit', async event => {
  event.preventDefault();
  const draft = draftPromotion();
  const error = validateDraft(draft);
  if (error) {
    showNotice(notice, error, 'error');
    updateUrlValidation();
    return;
  }
  const message = confirmationMessage(draft);
  if (message) {
    pendingSave = draft;
    document.getElementById('confirmMessage').textContent = message;
    confirmDialog.showModal();
    return;
  }
  await savePromotion(draft);
});

enabledInput.addEventListener('change', () => {
  updateEnabledLabel();
  updateUrlValidation();
});
urlInput.addEventListener('blur', updateUrlValidation);
document.getElementById('reloadPromotion').addEventListener('click', loadPromotion);
document.getElementById('testPromotionUrl').addEventListener('click', () => {
  const error = updateUrlValidation();
  if (error) {
    urlInput.focus();
    showNotice(notice, error, 'error');
    return;
  }
  window.open(buildClaimUrl(urlInput.value.trim(), 'SAMPLE-FREE-DRINK'), '_blank', 'noopener,noreferrer');
});
document.getElementById('cancelPromotionChange').addEventListener('click', () => {
  pendingSave = null;
  confirmDialog.close();
});
document.getElementById('confirmPromotionChange').addEventListener('click', async () => {
  const draft = pendingSave;
  pendingSave = null;
  confirmDialog.close();
  if (draft) await savePromotion(draft);
});
confirmDialog.addEventListener('cancel', () => { pendingSave = null; });

function statusBadge(status) {
  const badge = document.createElement('span');
  const classes = {
    issued: 'badge-info',
    reserved: 'badge-warning',
    claimed: 'badge-success',
    expired: 'badge-muted',
    cancelled: 'badge-danger'
  };
  badge.className = `badge ${classes[status] || 'badge-muted'}`;
  badge.textContent = status.charAt(0).toUpperCase() + status.slice(1);
  return badge;
}

function textCell(value, className = '') {
  const cell = document.createElement('td');
  if (className) cell.className = className;
  cell.textContent = value;
  return cell;
}

function detailCell(primary, secondary) {
  const cell = document.createElement('td');
  const first = document.createElement('div');
  first.textContent = primary;
  cell.appendChild(first);
  if (secondary) {
    const detail = document.createElement('div');
    detail.className = 'claim-detail';
    detail.textContent = secondary;
    cell.appendChild(detail);
  }
  return cell;
}

function renderClaims(data) {
  const rows = document.getElementById('claimRows');
  rows.replaceChildren();
  if (!data.items.length) {
    const row = document.createElement('tr');
    const cell = document.createElement('td');
    cell.colSpan = 7;
    cell.innerHTML = '<div class="empty-state"><p>No promotion claims match this status.</p></div>';
    row.appendChild(cell);
    rows.appendChild(row);
  } else {
    for (const claim of data.items) {
      const row = document.createElement('tr');
      const code = textCell(claim.claimCode, 'claim-code');
      row.appendChild(code);
      const status = document.createElement('td');
      status.appendChild(statusBadge(claim.status));
      row.appendChild(status);
      row.appendChild(detailCell(`#${claim.sequenceNumber}`, claim.sourceOrderId));
      row.appendChild(detailCell(formatDate(claim.issuedAt), `Expires ${formatDate(claim.expiresAt)}`));
      row.appendChild(detailCell(claim.printCount ? `${claim.printCount} print${claim.printCount === 1 ? '' : 's'}` : 'Not printed', claim.printedAt ? formatDate(claim.printedAt) : ''));
      row.appendChild(textCell(claim.formSubmitted ? 'Submitted' : 'Not submitted'));
      row.appendChild(detailCell(claim.redemptionOrderId || '—', claim.claimedAt ? formatDate(claim.claimedAt) : ''));
      rows.appendChild(row);
    }
  }
  const start = data.total ? data.offset + 1 : 0;
  const end = Math.min(data.offset + data.items.length, data.total);
  document.getElementById('claimsSummary').textContent = `Showing ${start}–${end} of ${data.total} claims`;
  document.getElementById('previousClaims').disabled = data.offset === 0;
  document.getElementById('nextClaims').disabled = data.offset + data.items.length >= data.total;
  const counts = data.statusCounts;
  for (const option of claimStatus.options) {
    if (option.value === 'all') option.textContent = `All (${Object.values(counts).reduce((sum, value) => sum + value, 0)})`;
    else option.textContent = `${option.value.charAt(0).toUpperCase() + option.value.slice(1)} (${counts[option.value] || 0})`;
  }
}

async function loadClaims() {
  const rows = document.getElementById('claimRows');
  rows.innerHTML = '<tr><td colspan="7"><div class="loading"><span class="spinner"></span>Loading claims…</div></td></tr>';
  showNotice(document.getElementById('claimsNotice'), '');
  try {
    const data = await api.getPromotionClaims(claimStatus.value, PAGE_SIZE, claimOffset);
    if (data) renderClaims(data);
  } catch (error) {
    rows.replaceChildren();
    const row = document.createElement('tr');
    const cell = textCell(error.message || 'Unable to load promotion claims.');
    cell.colSpan = 7;
    row.appendChild(cell);
    rows.appendChild(row);
    showNotice(document.getElementById('claimsNotice'), 'Claims could not be refreshed. Try again.', 'error');
  }
}

claimStatus.addEventListener('change', () => { claimOffset = 0; loadClaims(); });
document.getElementById('refreshClaims').addEventListener('click', loadClaims);
document.getElementById('previousClaims').addEventListener('click', () => {
  claimOffset = Math.max(0, claimOffset - PAGE_SIZE);
  loadClaims();
});
document.getElementById('nextClaims').addEventListener('click', () => {
  claimOffset += PAGE_SIZE;
  loadClaims();
});

loadPromotion();
loadClaims();
