// Admin auth uses an expiring HttpOnly session cookie; no secret is stored in JavaScript.

async function login(username, password) {
  try {
    const res = await fetch(`${window.location.origin}/admin/login`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    });
    if (!res.ok) return { success: false };
    const data = await res.json();
    if (!data.success) return { success: false };
    sessionStorage.setItem('kape_admin_session', 'active');
    sessionStorage.setItem('kape_user', data.user || username);
    return { success: true };
  } catch {
    return { success: false, error: 'Cannot reach the Render service.' };
  }
}

async function logout(callServer = true) {
  if (callServer) {
    await fetch(`${window.location.origin}/admin/logout`, { method: 'POST', credentials: 'same-origin' }).catch(() => {});
  }
  sessionStorage.removeItem('kape_admin_session');
  sessionStorage.removeItem('kape_user');
  window.location.href = 'login.html';
}

function requireAuth() {
  if (!sessionStorage.getItem('kape_admin_session')) {
    window.location.href = 'login.html';
    return false;
  }
  fetch(`${window.location.origin}/admin/session`, { credentials: 'same-origin' })
    .then((res) => { if (!res.ok) logout(false); })
    .catch(() => {});
  return true;
}
