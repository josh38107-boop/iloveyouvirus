// API client for all backend calls
const API_BASE = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1' 
  ? 'http://localhost:3000' 
  : 'https://kanlungan-coffee-api.onrender.com';

async function apiFetch(path, options = {}) {
  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {})
    }
  });
  if (res.status === 401) { logout(false); return null; }
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error || `API error: ${res.status}`);
  }
  return res.status === 204 ? null : res.json();
}

// Admin endpoints
const api = {
  getStats: (days = 1) => apiFetch(`/admin/stats?days=${days}`),
  getSales: (days = 7) => apiFetch(`/admin/sales?days=${days}`),
  getOrders: (limit = 50, offset = 0) => apiFetch(`/admin/orders?limit=${limit}&offset=${offset}`),
  getInventory: () => apiFetch('/admin/inventory'),
  createInventoryIngredient: (ingredient) => apiFetch('/admin/inventory', {
    method: 'POST', body: JSON.stringify(ingredient)
  }),
  updateInventoryIngredient: (id, ingredient) => apiFetch(`/admin/inventory/${encodeURIComponent(id)}`, {
    method: 'PUT', body: JSON.stringify(ingredient)
  }),
  deleteInventoryIngredient: (id) => apiFetch(`/admin/inventory/${encodeURIComponent(id)}`, {
    method: 'DELETE'
  }),
  getMenuManagement: () => apiFetch('/admin/menu'),
  createMenuCategory: (category) => apiFetch('/admin/menu/categories', {
    method: 'POST', body: JSON.stringify(category)
  }),
  updateMenuCategory: (id, category) => apiFetch(`/admin/menu/categories/${encodeURIComponent(id)}`, {
    method: 'PUT', body: JSON.stringify(category)
  }),
  deleteMenuCategory: (id) => apiFetch(`/admin/menu/categories/${encodeURIComponent(id)}`, {
    method: 'DELETE'
  }),
  createMenuItem: (item) => apiFetch('/admin/menu/items', {
    method: 'POST', body: JSON.stringify(item)
  }),
  updateMenuItem: (id, item) => apiFetch(`/admin/menu/items/${encodeURIComponent(id)}`, {
    method: 'PUT', body: JSON.stringify(item)
  }),
  deleteMenuItem: (id) => apiFetch(`/admin/menu/items/${encodeURIComponent(id)}`, {
    method: 'DELETE'
  }),
  createModifierGroup: (group) => apiFetch('/admin/menu/modifier-groups', {
    method: 'POST', body: JSON.stringify(group)
  }),
  updateModifierGroup: (id, group) => apiFetch(`/admin/menu/modifier-groups/${encodeURIComponent(id)}`, {
    method: 'PUT', body: JSON.stringify(group)
  }),
  deleteModifierGroup: (id) => apiFetch(`/admin/menu/modifier-groups/${encodeURIComponent(id)}`, {
    method: 'DELETE'
  }),
  createModifierOption: (groupId, option) => apiFetch(`/admin/menu/modifier-groups/${encodeURIComponent(groupId)}/options`, {
    method: 'POST', body: JSON.stringify(option)
  }),
  updateModifierOption: (id, option) => apiFetch(`/admin/menu/modifier-options/${encodeURIComponent(id)}`, {
    method: 'PUT', body: JSON.stringify(option)
  }),
  deleteModifierOption: (id) => apiFetch(`/admin/menu/modifier-options/${encodeURIComponent(id)}`, {
    method: 'DELETE'
  }),
  getEmployees: () => apiFetch('/admin/employees'),
  createEmployee: (employee) => apiFetch('/admin/employees', {
    method: 'POST', body: JSON.stringify(employee)
  }),
  updateEmployee: (id, employee) => apiFetch(`/admin/employees/${encodeURIComponent(id)}`, {
    method: 'PUT', body: JSON.stringify(employee)
  }),
  deactivateEmployee: (id) => apiFetch(`/admin/employees/${encodeURIComponent(id)}`, {
    method: 'DELETE'
  }),

  // Table endpoints
  getMenuCategories: () => apiFetch('/admin/data/menu_category?select=*'),
  getMenuItems: () => apiFetch('/admin/data/menu_item?select=*'),
  getPaymentMethods: () => apiFetch('/admin/data/payment_method?select=*'),
  getStoreSettings: () => apiFetch('/admin/data/store_settings?select=*'),
  getIngredients: () => apiFetch('/admin/data/ingredient?select=*'),

  upsertMenuItem: (item) => apiFetch('/admin/data/menu_item?on_conflict=id', {
    method: 'POST', body: JSON.stringify(item)
  }),
  getDevices: () => apiFetch('/admin/devices'),
  createEnrollment: (deviceName, role) => apiFetch('/admin/enrollments', {
    method: 'POST', body: JSON.stringify({ deviceName, role })
  }),
  revokeDevice: (id) => apiFetch(`/admin/devices/${encodeURIComponent(id)}/revoke`, { method: 'POST' }),
  reenrollDevice: (id) => apiFetch(`/admin/devices/${encodeURIComponent(id)}/reenroll`, { method: 'POST' }),
};

// Utility: format currency (Philippine Peso)
function formatPeso(cents) {
  return '₱' + (cents / 100).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

// Utility: format timestamp
function formatDate(ms) {
  if (!ms) return '—';
  return new Date(parseInt(ms)).toLocaleString('en-PH', {
    month: 'short', day: 'numeric', year: 'numeric',
    hour: 'numeric', minute: '2-digit', hour12: true
  });
}

function formatDateShort(ms) {
  if (!ms) return '—';
  return new Date(parseInt(ms)).toLocaleDateString('en-PH', { month: 'short', day: 'numeric' });
}

// Update clock in topbar
function startClock() {
  const el = document.getElementById('topbarTime');
  if (!el) return;
  function tick() {
    el.textContent = new Date().toLocaleString('en-PH', {
      weekday: 'short', month: 'short', day: 'numeric',
      hour: 'numeric', minute: '2-digit', hour12: true
    });
  }
  tick();
  setInterval(tick, 1000);
}
