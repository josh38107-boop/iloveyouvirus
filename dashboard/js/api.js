// API client for all backend calls
// ⚠️ Change this to your Render URL when deploying!
const API_BASE = window.location.origin;

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

  // Table endpoints
  getMenuCategories: () => apiFetch('/admin/data/menu_category?select=*'),
  getMenuItems: () => apiFetch('/admin/data/menu_item?select=*'),
  getEmployees: () => apiFetch('/admin/data/employee?select=*'),
  getPaymentMethods: () => apiFetch('/admin/data/payment_method?select=*'),
  getStoreSettings: () => apiFetch('/admin/data/store_settings?select=*'),
  getIngredients: () => apiFetch('/admin/data/ingredient?select=*'),

  upsertMenuItem: (item) => apiFetch('/admin/data/menu_item?on_conflict=id', {
    method: 'POST', body: JSON.stringify(item)
  }),
  upsertEmployee: (emp) => apiFetch('/admin/data/employee?on_conflict=id', {
    method: 'POST', body: JSON.stringify(emp)
  }),
  upsertIngredient: (ing) => apiFetch('/admin/data/ingredient?on_conflict=id', {
    method: 'POST', body: JSON.stringify(ing)
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
