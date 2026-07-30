// API client for all backend calls
const API_BASE = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1' 
  ? 'http://localhost:3000' 
  : 'https://kanlungan-coffee-api.onrender.com';

async function apiFetch(path, options = {}) {
  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    credentials: 'include',
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
  getStats: (days = 1, customRange = null) => {
    if (customRange?.fromDate && customRange?.toDate) {
      return apiFetch(`/admin/stats?fromDate=${customRange.fromDate}&toDate=${customRange.toDate}`);
    }
    return apiFetch(`/admin/stats?days=${days}`);
  },
  getSales: (days = 7, customRange = null) => {
    if (customRange?.fromDate && customRange?.toDate) {
      return apiFetch(`/admin/sales?fromDate=${customRange.fromDate}&toDate=${customRange.toDate}`);
    }
    return apiFetch(`/admin/sales?days=${days}`);
  },
  getOrders: (limit = 50, offset = 0) => apiFetch(`/admin/orders?limit=${limit}&offset=${offset}`),
  getOrder: (id) => apiFetch(`/admin/orders/${encodeURIComponent(id)}`),
  getHappenings: (start, end) => {
    const params = new URLSearchParams();
    if (start && end && /^\d{4}-\d{2}-\d{2}$/.test(start) && /^\d{4}-\d{2}-\d{2}$/.test(end)) {
      params.append('fromDate', start);
      params.append('toDate', end);
    } else {
      if (start) params.append('start', start);
      if (end) params.append('end', end);
    }
    return apiFetch(`/admin/happenings?${params.toString()}`);
  },
  deleteHappening: (id) => apiFetch(`/admin/happenings/${encodeURIComponent(id)}`, {
    method: 'DELETE'
  }),
  getInventory: (customRange = null) => {

    if (customRange?.fromDate && customRange?.toDate) {
      return apiFetch(`/admin/inventory?fromDate=${customRange.fromDate}&toDate=${customRange.toDate}`);
    }
    return apiFetch('/admin/inventory');
  },
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
  getPromotion: () => apiFetch('/admin/promotion'),
  updatePromotion: (promotion) => apiFetch('/admin/promotion', {
    method: 'PUT', body: JSON.stringify(promotion)
  }),
  getPromotionClaims: (status = 'all', limit = 20, offset = 0) =>
    apiFetch(`/admin/promotion/claims?status=${encodeURIComponent(status)}&limit=${limit}&offset=${offset}`),
  getDiscountSettings: () => apiFetch('/admin/discount-settings'),
  updateDiscountBenefits: (settings) => apiFetch('/admin/discount-settings', {
    method: 'PUT', body: JSON.stringify(settings)
  }),
  createCustomDiscount: (discount) => apiFetch('/admin/discount-settings/custom', {
    method: 'POST', body: JSON.stringify(discount)
  }),
  updateCustomDiscount: (id, discount) => apiFetch(`/admin/discount-settings/custom/${encodeURIComponent(id)}`, {
    method: 'PUT', body: JSON.stringify(discount)
  }),
  getPaymentVoidSettings: () => apiFetch('/admin/payment-void-settings'),
  getBusinessDaySettings: () => apiFetch('/admin/business-day-settings'),
  updateBusinessDaySettings: (settings) => apiFetch('/admin/business-day-settings', {
    method: 'PUT', body: JSON.stringify(settings)
  }),
  updateVoidRefundPin: (settings) => apiFetch('/admin/payment-void-settings/pin', {
    method: 'PUT', body: JSON.stringify(settings)
  }),
  createPaymentMethod: (method) => apiFetch('/admin/payment-void-settings/methods', {
    method: 'POST', body: JSON.stringify(method)
  }),
  updatePaymentMethod: (id, method) => apiFetch(`/admin/payment-void-settings/methods/${encodeURIComponent(id)}`, {
    method: 'PUT', body: JSON.stringify(method)
  }),
  deletePaymentMethod: (id, expectedUpdatedAt) => apiFetch(`/admin/payment-void-settings/methods/${encodeURIComponent(id)}`, {
    method: 'DELETE', body: JSON.stringify({ expectedUpdatedAt })
  }),
  getDataMaintenance: () => apiFetch('/admin/data-maintenance'),
  resetOperationalData: (request) => apiFetch('/admin/data-maintenance/reset', {
    method: 'POST', body: JSON.stringify(request)
  }),

  // Table endpoints
  getMenuCategories: () => apiFetch('/admin/data/menu_category?select=*'),
  getMenuItems: () => apiFetch('/admin/data/menu_item?select=*'),
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
  const value = typeof ms === 'string' && /^\d+$/.test(ms.trim()) ? Number(ms) : ms;
  const d = new Date(value);
  if (isNaN(d.getTime())) return '—';
  return d.toLocaleString('en-PH', {
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
