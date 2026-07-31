requireAuth();
startClock();

const rows = document.getElementById('deviceRows');
const notice = document.getElementById('deviceNotice');
const modal = document.getElementById('enrollmentModal');
const form = document.getElementById('enrollmentForm');
const result = document.getElementById('enrollmentResult');
const apkVersion = document.getElementById('apkVersion');

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>'"]/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' }[char]));
}

function showNotice(message, isError = false) {
  notice.textContent = message;
  notice.classList.toggle('error', isError);
}

function relativeTime(value) {
  if (!value) return 'Never';
  const seconds = Math.max(0, Math.floor((Date.now() - Number(value)) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  return formatDate(value);
}

async function loadDevices() {
  rows.innerHTML = '<tr><td colspan="6"><div class="loading"><span class="spinner"></span>Loading devices…</div></td></tr>';
  try {
    const devices = await api.getDevices();
    if (!devices) return;
    if (!devices.length) {
      rows.innerHTML = '<tr><td colspan="6"><div class="empty-state"><p>No enrolled devices yet.</p></div></td></tr>';
      return;
    }
    rows.innerHTML = devices.map((device) => {
      const active = device.status === 'active';
      return `<tr>
        <td><strong>${escapeHtml(device.name)}</strong><div class="device-id">${escapeHtml(device.hardware_id)}</div></td>
        <td><span class="role-badge">${device.role === 'manager' ? 'Manager Tablet' : 'Counter'}</span></td>
        <td><span class="status-badge ${active ? 'active' : 'revoked'}">${active ? 'Active' : 'Revoked'}</span></td>
        <td title="${escapeHtml(formatDate(device.last_seen_at))}">${relativeTime(device.last_seen_at)}</td>
        <td>${formatDate(device.created_at)}</td>
        <td class="device-actions">${active
          ? `<button class="btn btn-danger btn-small" data-action="revoke" data-id="${escapeHtml(device.id)}" data-name="${escapeHtml(device.name)}">Revoke</button>`
          : `<button class="btn btn-outline btn-small" data-action="reenroll" data-id="${escapeHtml(device.id)}">Re-enroll</button>`}</td>
      </tr>`;
    }).join('');
  } catch (error) {
    rows.innerHTML = `<tr><td colspan="6"><div class="empty-state"><p>${escapeHtml(error.message)}</p></div></td></tr>`;
  }
}

function renderApkVersion(info) {
  if (!apkVersion || !info) return;
  const label = info.versionName
    ? `Latest APK: v${info.versionName}${info.versionCode ? ` (${info.versionCode})` : ''}`
    : info.configured ? 'Latest APK is ready' : '';
  apkVersion.textContent = label;
  apkVersion.hidden = !label;
}

async function loadLatestApkInfo() {
  try {
    renderApkVersion(await api.getLatestApkInfo());
  } catch {
    renderApkVersion(null);
  }
}

async function downloadLatestApk() {
  const button = document.getElementById('downloadLatestApk');
  button.disabled = true;
  try {
    const info = await api.getLatestApkInfo();
    renderApkVersion(info);
    if (!info?.configured) {
      showNotice('Latest APK is not configured yet. Set APK_DOWNLOAD_URL in Render.', true);
      return;
    }
    window.location.href = api.latestApkUrl();
    showNotice('Latest APK download started.');
  } catch (error) {
    showNotice(error.message || 'Latest APK download failed.', true);
  } finally {
    button.disabled = false;
  }
}

function openModal() {
  form.hidden = false; result.hidden = true; form.reset(); modal.classList.add('active');
  setTimeout(() => document.getElementById('deviceName').focus(), 50);
}
function closeModal() { modal.classList.remove('active'); }

document.getElementById('openEnrollment').addEventListener('click', openModal);
document.getElementById('closeEnrollment').addEventListener('click', closeModal);
document.getElementById('cancelEnrollment').addEventListener('click', closeModal);
document.getElementById('refreshDevices').addEventListener('click', loadDevices);
document.getElementById('downloadLatestApk').addEventListener('click', downloadLatestApk);
modal.addEventListener('click', (event) => { if (event.target === modal) closeModal(); });

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = document.getElementById('createCode'); button.disabled = true; button.textContent = 'Creating…';
  try {
    const enrollment = await api.createEnrollment(document.getElementById('deviceName').value.trim(), document.getElementById('deviceRole').value);
    form.hidden = true; result.hidden = false;
    document.getElementById('enrollmentCode').textContent = enrollment.code;
    document.getElementById('enrollmentExpiry').textContent = `Expires ${formatDate(enrollment.expiresAt)}. This code will not be shown again.`;
  } catch (error) { showNotice(error.message, true); closeModal(); }
  finally { button.disabled = false; button.textContent = 'Create code'; }
});

document.getElementById('copyCode').addEventListener('click', async () => {
  await navigator.clipboard.writeText(document.getElementById('enrollmentCode').textContent);
  document.getElementById('copyCode').textContent = 'Copied';
});

rows.addEventListener('click', async (event) => {
  const button = event.target.closest('[data-action]'); if (!button) return;
  button.disabled = true;
  try {
    if (button.dataset.action === 'revoke') {
      if (!window.confirm(`Revoke ${button.dataset.name}? It will lose cloud access immediately.`)) return;
      await api.revokeDevice(button.dataset.id); showNotice('Device revoked.');
    } else {
      const enrollment = await api.reenrollDevice(button.dataset.id);
      openModal(); form.hidden = true; result.hidden = false;
      document.getElementById('enrollmentCode').textContent = enrollment.code;
      document.getElementById('enrollmentExpiry').textContent = `Expires ${formatDate(enrollment.expiresAt)}. This code will not be shown again.`;
    }
    await loadDevices();
  } catch (error) { showNotice(error.message, true); }
  finally { button.disabled = false; }
});

loadLatestApkInfo();
loadDevices();
