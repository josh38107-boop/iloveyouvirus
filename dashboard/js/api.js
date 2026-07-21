// API client for all backend calls
// ⚠️ Change this to your Render URL when deploying!
const API_BASE = 'http://localhost:3000';

async function apiFetch(path, options = {}) {
  const token = getToken();
  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      'apikey': token,
      'Authorization': `Bearer ${token}`,
      ...(options.headers || {})
    }
  });
  if (res.status === 401) { logout(); return null; }
  if (!res.ok) throw new Error(`API error: ${res.status}`);
  return res.json();
}

// Admin endpoints
const api = {
  getStats: () => apiFetch('/admin/stats'),
  getSales: (days = 7) => apiFetch(`/admin/sales?days=${days}`),
  getOrders: (limit = 50, offset = 0) => apiFetch(`/admin/orders?limit=${limit}&offset=${offset}`),
  getInventory: () => apiFetch('/admin/inventory'),

  // Table endpoints
  getMenuCategories: () => apiFetch('/rest/v1/menu_category?select=*'),
  getMenuItems: () => apiFetch('/rest/v1/menu_item?select=*'),
  getEmployees: () => apiFetch('/rest/v1/employee?select=*'),
  getPaymentMethods: () => apiFetch('/rest/v1/payment_method?select=*'),
  getStoreSettings: () => apiFetch('/rest/v1/store_settings?select=*'),
  getIngredients: () => apiFetch('/rest/v1/ingredient?select=*'),

  upsertMenuItem: (item) => apiFetch('/rest/v1/menu_item?on_conflict=id', {
    method: 'POST', body: JSON.stringify(item)
  }),
  upsertEmployee: (emp) => apiFetch('/rest/v1/employee?on_conflict=id', {
    method: 'POST', body: JSON.stringify(emp)
  }),
  upsertIngredient: (ing) => apiFetch('/rest/v1/ingredient?on_conflict=id', {
    method: 'POST', body: JSON.stringify(ing)
  }),
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
