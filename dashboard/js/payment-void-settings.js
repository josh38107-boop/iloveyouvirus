requireAuth();
startClock();

let settings = null;
let editingMethod = null;
let deletingMethod = null;

const loading = document.getElementById('settingsLoading');
const pinSection = document.getElementById('pinSection');
const pinForm = document.getElementById('pinForm');
const pinInput = document.getElementById('voidPin');
const pinError = document.getElementById('pinError');
const pinNotice = document.getElementById('pinNotice');
const methodNotice = document.getElementById('methodNotice');
const methodList = document.getElementById('methodList');
const methodDialog = document.getElementById('methodDialog');
const deleteDialog = document.getElementById('deleteDialog');

function showNotice(element, message, type = '') {
  element.textContent = message;
  element.className = `settings-notice ${type}`.trim();
}

function methodDisplayName(method) {
  return method.id === 'gcash' ? 'GCash / Online' : method.name;
}

function renderMethods() {
  methodList.replaceChildren();
  const methods = settings?.paymentMethods || [];
  if (!methods.length) {
    const empty = document.createElement('div');
    empty.className = 'empty-methods';
    empty.textContent = 'No payment methods are configured.';
    methodList.append(empty);
    return;
  }
  for (const method of methods) {
    const row = document.createElement('article');
    row.className = `method-row${method.enabled ? '' : ' inactive'}`;

    const identity = document.createElement('div');
    const name = document.createElement('div');
    name.className = 'method-name';
    name.textContent = methodDisplayName(method);
    const sub = document.createElement('div');
    sub.className = 'method-sub';
    sub.textContent = method.isSystem ? 'Protected system method' : 'Custom payment method';
    identity.append(name, sub);

    const category = document.createElement('div');
    const categoryLabel = document.createElement('span');
    categoryLabel.className = 'method-label';
    categoryLabel.textContent = 'Category';
    const categoryValue = document.createElement('span');
    categoryValue.textContent = method.paymentCategory === 'CASH' ? 'Cash' : method.paymentCategory === 'ONLINE' ? 'Online' : 'System';
    category.append(categoryLabel, categoryValue);

    const status = document.createElement('div');
    const statusLabel = document.createElement('span');
    statusLabel.className = 'method-label';
    statusLabel.textContent = 'POS status';
    const badge = document.createElement('span');
    badge.className = `badge ${method.enabled ? 'badge-success' : 'badge-muted'}`;
    badge.textContent = method.enabled ? 'Active' : 'Hidden';
    status.append(statusLabel, badge);

    const actions = document.createElement('div');
    actions.className = 'method-actions';
    if (method.isSystem) {
      const protectedText = document.createElement('span');
      protectedText.className = 'method-sub';
      protectedText.textContent = 'Cannot be changed';
      actions.append(protectedText);
    } else {
      const toggle = document.createElement('button');
      toggle.type = 'button';
      toggle.className = 'btn btn-outline';
      toggle.textContent = method.enabled ? 'Hide' : 'Show';
      toggle.dataset.toggleMethod = method.id;
      toggle.setAttribute('aria-label', `${method.enabled ? 'Hide' : 'Show'} ${method.name} on POS`);
      const edit = document.createElement('button');
      edit.type = 'button';
      edit.className = 'btn btn-outline';
      edit.textContent = 'Edit';
      edit.dataset.editMethod = method.id;
      edit.setAttribute('aria-label', `Edit ${method.name}`);
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'btn btn-outline';
      remove.textContent = 'Delete';
      remove.dataset.deleteMethod = method.id;
      remove.setAttribute('aria-label', `Delete ${method.name}`);
      actions.append(toggle, edit, remove);
    }
    row.append(identity, category, status, actions);
    methodList.append(row);
  }
}

function populate(data) {
  settings = data;
  pinInput.value = data.voidRefundPin;
  renderMethods();
  loading.hidden = true;
  pinForm.hidden = false;
  pinSection.setAttribute('aria-busy', 'false');
}

async function loadSettings() {
  pinSection.setAttribute('aria-busy', 'true');
  try {
    populate(await api.getPaymentVoidSettings());
  } catch (error) {
    loading.replaceChildren();
    loading.textContent = `Unable to load Payment & Void Settings: ${error.message}`;
    showNotice(methodNotice, 'Payment methods could not be loaded. Refresh the page to try again.', 'error');
  }
}

pinInput.addEventListener('input', () => {
  pinInput.value = pinInput.value.replace(/\D/g, '').slice(0, 4);
  pinError.textContent = '';
  pinInput.setAttribute('aria-invalid', 'false');
});

document.getElementById('togglePin').addEventListener('click', event => {
  const showing = pinInput.type === 'text';
  pinInput.type = showing ? 'password' : 'text';
  event.currentTarget.textContent = showing ? 'Show' : 'Hide';
  event.currentTarget.setAttribute('aria-pressed', String(!showing));
  pinInput.focus();
});

pinForm.addEventListener('submit', async event => {
  event.preventDefault();
  if (!/^\d{4}$/.test(pinInput.value)) {
    pinError.textContent = 'Authorization PIN must be exactly 4 digits.';
    pinInput.setAttribute('aria-invalid', 'true');
    pinInput.focus();
    return;
  }
  const button = document.getElementById('savePin');
  button.disabled = true;
  button.textContent = 'Saving…';
  showNotice(pinNotice, '');
  try {
    const saved = await api.updateVoidRefundPin({
      voidRefundPin: pinInput.value,
      expectedUpdatedAt: settings.pinUpdatedAt
    });
    settings.voidRefundPin = saved.voidRefundPin;
    settings.pinUpdatedAt = saved.pinUpdatedAt;
    showNotice(pinNotice, 'Authorization PIN saved. POS devices will receive it during the next refresh.', 'success');
  } catch (error) {
    showNotice(pinNotice, error.message, 'error');
  } finally {
    button.disabled = false;
    button.textContent = 'Save authorization PIN';
  }
});

function openMethodEditor(method = null) {
  editingMethod = method;
  document.getElementById('methodDialogTitle').textContent = method ? 'Edit payment method' : 'Add payment method';
  document.getElementById('methodName').value = method?.name || '';
  document.getElementById('methodCategory').value = method?.paymentCategory || 'CASH';
  document.getElementById('methodNameError').textContent = '';
  document.getElementById('methodDialogError').textContent = '';
  methodDialog.showModal();
  requestAnimationFrame(() => document.getElementById('methodName').focus());
}

document.getElementById('addMethod').addEventListener('click', () => openMethodEditor());
document.getElementById('cancelMethod').addEventListener('click', () => methodDialog.close());

document.getElementById('methodForm').addEventListener('submit', async event => {
  event.preventDefault();
  const nameInput = document.getElementById('methodName');
  const name = nameInput.value.trim().replace(/\s+/g, ' ');
  if (!name) {
    document.getElementById('methodNameError').textContent = 'Enter a payment method name.';
    nameInput.focus();
    return;
  }
  const button = document.getElementById('saveMethod');
  button.disabled = true;
  button.textContent = 'Saving…';
  document.getElementById('methodDialogError').textContent = '';
  const payload = {
    name,
    paymentCategory: document.getElementById('methodCategory').value,
    enabled: editingMethod?.enabled ?? true
  };
  try {
    if (editingMethod) {
      const saved = await api.updatePaymentMethod(editingMethod.id, {
        ...payload,
        expectedUpdatedAt: editingMethod.updatedAt
      });
      settings.paymentMethods = settings.paymentMethods.map(method => method.id === saved.id ? saved : method);
    } else {
      settings.paymentMethods.push(await api.createPaymentMethod(payload));
    }
    settings.paymentMethods.sort((a, b) => Number(b.isSystem) - Number(a.isSystem) || a.name.localeCompare(b.name));
    renderMethods();
    methodDialog.close();
    showNotice(methodNotice, 'Payment method saved. POS devices will receive it during the next refresh.', 'success');
  } catch (error) {
    document.getElementById('methodDialogError').textContent = error.message;
  } finally {
    button.disabled = false;
    button.textContent = 'Save payment method';
  }
});

methodList.addEventListener('click', async event => {
  const editButton = event.target.closest('[data-edit-method]');
  if (editButton) {
    openMethodEditor(settings.paymentMethods.find(method => method.id === editButton.dataset.editMethod));
    return;
  }
  const deleteButton = event.target.closest('[data-delete-method]');
  if (deleteButton) {
    deletingMethod = settings.paymentMethods.find(method => method.id === deleteButton.dataset.deleteMethod);
    document.getElementById('deleteError').textContent = '';
    document.getElementById('deleteMessage').textContent =
      `${deletingMethod.name} will be removed from new POS transactions. Historical payments will remain unchanged.`;
    deleteDialog.showModal();
    return;
  }
  const toggleButton = event.target.closest('[data-toggle-method]');
  if (!toggleButton) return;
  const method = settings.paymentMethods.find(item => item.id === toggleButton.dataset.toggleMethod);
  toggleButton.disabled = true;
  try {
    const saved = await api.updatePaymentMethod(method.id, {
      name: method.name,
      paymentCategory: method.paymentCategory,
      enabled: !method.enabled,
      expectedUpdatedAt: method.updatedAt
    });
    settings.paymentMethods = settings.paymentMethods.map(item => item.id === saved.id ? saved : item);
    renderMethods();
    showNotice(methodNotice, `${saved.name} is now ${saved.enabled ? 'active' : 'hidden'} on POS.`, 'success');
  } catch (error) {
    showNotice(methodNotice, error.message, 'error');
    toggleButton.disabled = false;
  }
});

document.getElementById('cancelDelete').addEventListener('click', () => {
  deletingMethod = null;
  deleteDialog.close();
});
document.getElementById('confirmDelete').addEventListener('click', async event => {
  if (!deletingMethod) return;
  const button = event.currentTarget;
  button.disabled = true;
  button.textContent = 'Deleting…';
  try {
    await api.deletePaymentMethod(deletingMethod.id, deletingMethod.updatedAt);
    settings.paymentMethods = settings.paymentMethods.filter(method => method.id !== deletingMethod.id);
    const name = deletingMethod.name;
    deletingMethod = null;
    deleteDialog.close();
    renderMethods();
    showNotice(methodNotice, `${name} was deleted. Historical payments remain unchanged.`, 'success');
  } catch (error) {
    document.getElementById('deleteError').textContent = error.message;
  } finally {
    button.disabled = false;
    button.textContent = 'Delete payment method';
  }
});

for (const dialog of [methodDialog, deleteDialog]) {
  dialog.addEventListener('click', event => {
    if (event.target === dialog) dialog.close();
  });
}

loadSettings();
