// Auth helpers — stores token in localStorage

async function login(username, password) {
  try {
    const res = await fetch(`${API_BASE}/admin/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    });
    if (!res.ok) return { success: false };
    const data = await res.json();
    if (data.token) {
      localStorage.setItem('kape_token', data.token);
      localStorage.setItem('kape_user', username);
      return { success: true };
    }
    return { success: false };
  } catch {
    // Fallback: check hardcoded credentials for demo
    const ADMIN_USER = window.ADMIN_USERNAME || 'admin';
    const ADMIN_PASS = window.ADMIN_PASSWORD || 'KapeAdmin2024';
    if (username === ADMIN_USER && password === ADMIN_PASS) {
      localStorage.setItem('kape_token', window.API_KEY || 'KapeAdmin2024SecretKey');
      localStorage.setItem('kape_user', username);
      return { success: true };
    }
    return { success: false };
  }
}

function logout() {
  localStorage.removeItem('kape_token');
  localStorage.removeItem('kape_user');
  window.location.href = 'login.html';
}

function requireAuth() {
  if (!localStorage.getItem('kape_token')) {
    window.location.href = 'login.html';
    return false;
  }
  return true;
}

function getToken() {
  return localStorage.getItem('kape_token') || '';
}
