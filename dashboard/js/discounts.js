requireAuth();
startClock();

let discountSettings = null;
let editingDiscount = null;
let pendingDisabledDraft = null;

const benefitSection = document.getElementById('benefitSection');
const loading = document.getElementById('discountLoading');
const benefitForm = document.getElementById('benefitForm');
const seniorPercent = document.getElementById('seniorPercent');
const pwdPercent = document.getElementById('pwdPercent');
const benefitNotice = document.getElementById('benefitNotice');
const customNotice = document.getElementById('customNotice');
const discountList = document.getElementById('discountList');
const discountDialog = document.getElementById('discountDialog');
const disableDialog = document.getElementById('disableDialog');
const discountForm = document.getElementById('discountForm');

function showNotice(element, message, type = '') {
  element.textContent = message;
  element.className = `settings-notice ${type}`.trim();
}

function validatePercent(input, errorElement, label) {
  const value = Number(input.value);
  const message = !input.value.trim() || !Number.isFinite(value) || value <= 0 || value > 100
    ? `${label} must be greater than 0 and no more than 100.`
    : '';
  errorElement.textContent = message;
  input.setAttribute('aria-invalid', String(Boolean(message)));
  return message;
}

function createMeta(label, value) {
  const container = document.createElement('div');
  const heading = document.createElement('span');
  heading.className = 'discount-label';
  heading.textContent = label;
  const content = document.createElement('span');
  content.textContent = value;
  container.append(heading, content);
  return container;
}

function renderDiscounts() {
  discountList.replaceChildren();
  const rules = discountSettings?.customDiscounts || [];
  if (!rules.length) {
    const empty = document.createElement('div');
    empty.className = 'empty-discounts';
    empty.textContent = 'No custom discounts yet. Add one when you are ready.';
    discountList.append(empty);
    return;
  }
  for (const rule of rules) {
    const row = document.createElement('article');
    row.className = `discount-row${rule.active ? '' : ' inactive'}`;
    const name = document.createElement('div');
    const title = document.createElement('div');
    title.className = 'discount-name';
    title.textContent = rule.name;
    const status = document.createElement('div');
    status.className = 'discount-meta';
    status.textContent = rule.active ? 'Active on POS' : 'Disabled · historical use preserved';
    name.append(title, status);
    const percent = createMeta('Discount', `${Number(rule.percent).toLocaleString('en-PH')}%`);
    percent.lastElementChild.className = 'discount-value';
    const scope = createMeta('Applies to', rule.scope === 'order' ? 'Whole order' : 'One item');
    const reference = createMeta('Proof', rule.requiresReference ? 'ID/reference required' : 'Not required');
    const edit = document.createElement('button');
    edit.type = 'button';
    edit.className = 'btn btn-outline';
    edit.textContent = 'Edit';
    edit.dataset.editDiscount = rule.id;
    edit.setAttribute('aria-label', `Edit ${rule.name}`);
    row.append(name, percent, scope, reference, edit);
    discountList.append(row);
  }
}

function populate(settings) {
  discountSettings = settings;
  seniorPercent.value = settings.seniorPercent;
  pwdPercent.value = settings.pwdPercent;
  renderDiscounts();
  loading.hidden = true;
  benefitForm.hidden = false;
  benefitSection.setAttribute('aria-busy', 'false');
}

async function loadSettings() {
  benefitSection.setAttribute('aria-busy', 'true');
  loading.hidden = false;
  try {
    populate(await api.getDiscountSettings());
  } catch (error) {
    loading.innerHTML = '';
    loading.textContent = `Unable to load discount settings: ${error.message}`;
    showNotice(customNotice, 'Custom discounts could not be loaded. Refresh the page to try again.', 'error');
  }
}

benefitForm.addEventListener('submit', async event => {
  event.preventDefault();
  const seniorError = validatePercent(seniorPercent, document.getElementById('seniorError'), 'Senior Citizen discount');
  const pwdError = validatePercent(pwdPercent, document.getElementById('pwdError'), 'PWD discount');
  if (seniorError || pwdError) return;
  const button = document.getElementById('saveBenefits');
  button.disabled = true;
  button.textContent = 'Saving…';
  showNotice(benefitNotice, '');
  try {
    populate(await api.updateDiscountBenefits({
      seniorPercent: Number(seniorPercent.value),
      pwdPercent: Number(pwdPercent.value),
      expectedUpdatedAt: discountSettings.updatedAt
    }));
    showNotice(benefitNotice, 'Benefit discounts saved. POS devices will refresh when POS or Settings is opened.', 'success');
  } catch (error) {
    showNotice(benefitNotice, error.message, 'error');
  } finally {
    button.disabled = false;
    button.textContent = 'Save benefit discounts';
  }
});

function openEditor(rule = null) {
  editingDiscount = rule;
  pendingDisabledDraft = null;
  document.getElementById('discountDialogTitle').textContent = rule ? 'Edit discount' : 'Add discount';
  document.getElementById('discountName').value = rule?.name || '';
  document.getElementById('discountPercent').value = rule?.percent || '';
  document.getElementById('discountScope').value = rule?.scope || 'item';
  document.getElementById('discountReference').checked = rule?.requiresReference || false;
  document.getElementById('discountActive').checked = rule?.active ?? true;
  document.getElementById('discountSortOrder').value = rule?.sortOrder ?? 0;
  for (const id of ['discountNameError', 'discountPercentError', 'dialogError']) document.getElementById(id).textContent = '';
  discountDialog.showModal();
  requestAnimationFrame(() => document.getElementById('discountName').focus());
}

document.getElementById('addDiscount').addEventListener('click', () => openEditor());
document.getElementById('cancelDiscount').addEventListener('click', () => discountDialog.close());
discountList.addEventListener('click', event => {
  const button = event.target.closest('[data-edit-discount]');
  if (!button) return;
  openEditor(discountSettings.customDiscounts.find(rule => rule.id === button.dataset.editDiscount));
});

function readDraft() {
  return {
    name: document.getElementById('discountName').value.trim().replace(/\s+/g, ' '),
    percent: Number(document.getElementById('discountPercent').value),
    scope: document.getElementById('discountScope').value,
    requiresReference: document.getElementById('discountReference').checked,
    active: document.getElementById('discountActive').checked,
    sortOrder: Number(document.getElementById('discountSortOrder').value || 0)
  };
}

function validateDraft(draft) {
  let firstInvalid = null;
  const reserved = ['senior', 'senior citizen', 'pwd', 'free drink reward'];
  const nameError = !draft.name
    ? 'Enter a discount name.'
    : reserved.includes(draft.name.toLowerCase()) ? 'That discount name is reserved.' : '';
  document.getElementById('discountNameError').textContent = nameError;
  if (nameError) firstInvalid = document.getElementById('discountName');
  const percentError = validatePercent(
    document.getElementById('discountPercent'),
    document.getElementById('discountPercentError'),
    'Discount percentage'
  );
  if (!firstInvalid && percentError) firstInvalid = document.getElementById('discountPercent');
  if (firstInvalid) firstInvalid.focus();
  return !firstInvalid;
}

async function saveRule(draft) {
  const save = document.getElementById('saveDiscount');
  save.disabled = true;
  save.textContent = 'Saving…';
  document.getElementById('dialogError').textContent = '';
  try {
    if (editingDiscount) {
      const updated = await api.updateCustomDiscount(editingDiscount.id, {
        ...draft,
        expectedUpdatedAt: editingDiscount.updatedAt
      });
      discountSettings.customDiscounts = discountSettings.customDiscounts.map(rule => rule.id === updated.id ? updated : rule);
    } else {
      discountSettings.customDiscounts.push(await api.createCustomDiscount(draft));
    }
    discountSettings.customDiscounts.sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name));
    renderDiscounts();
    discountDialog.close();
    showNotice(customNotice, 'Custom discount saved. POS devices will receive it during the next refresh.', 'success');
  } catch (error) {
    document.getElementById('dialogError').textContent = error.message;
  } finally {
    save.disabled = false;
    save.textContent = 'Save discount';
  }
}

discountForm.addEventListener('submit', event => {
  event.preventDefault();
  const draft = readDraft();
  if (!validateDraft(draft)) return;
  if (editingDiscount?.active && !draft.active) {
    pendingDisabledDraft = draft;
    document.getElementById('disableMessage').textContent =
      `${editingDiscount.name} will disappear from new POS transactions. Historical orders will not change.`;
    disableDialog.showModal();
    return;
  }
  saveRule(draft);
});

document.getElementById('cancelDisable').addEventListener('click', () => {
  pendingDisabledDraft = null;
  disableDialog.close();
});
document.getElementById('confirmDisable').addEventListener('click', () => {
  const draft = pendingDisabledDraft;
  pendingDisabledDraft = null;
  disableDialog.close();
  if (draft) saveRule(draft);
});

for (const dialog of [discountDialog, disableDialog]) {
  dialog.addEventListener('click', event => {
    if (event.target === dialog) dialog.close();
  });
}

loadSettings();
