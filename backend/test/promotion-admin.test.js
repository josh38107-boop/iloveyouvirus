const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const backendRoot = path.join(__dirname, '..');
const projectRoot = path.join(backendRoot, '..');

test('admin promotion endpoints require the existing admin session', () => {
  const server = fs.readFileSync(path.join(backendRoot, 'server.js'), 'utf8');
  assert.match(server, /app\.get\('\/admin\/promotion', adminAuthenticate/);
  assert.match(server, /app\.put\('\/admin\/promotion', adminAuthenticate/);
  assert.match(server, /app\.get\('\/admin\/promotion\/claims', adminAuthenticate/);
  assert.match(server, /promotion\.updateConfig\(req\.body \|\| \{\}\)/);
});

test('both legacy and device POS RPC routes block promotion writes', () => {
  const server = fs.readFileSync(path.join(backendRoot, 'server.js'), 'utf8');
  assert.match(server, /app\.post\('\/rest\/v1\/rpc\/:fn', authenticate, rejectDevicePromotionUpdate/);
  assert.match(server, /app\.post\('\/sync\/v1\/rpc\/:fn', cloud\.deviceAuth/);
  assert.match(server, /req\.params\.fn === 'update_promotion_config'/);
  assert.match(server, /Manage Free Drink Promotion settings in the admin website/);
});

test('dashboard promotion script parses and exposes accessible management controls', () => {
  const script = fs.readFileSync(path.join(projectRoot, 'dashboard', 'js', 'promotions.js'), 'utf8');
  const html = fs.readFileSync(path.join(projectRoot, 'dashboard', 'promotions.html'), 'utf8');
  assert.doesNotThrow(() => new Function(script));
  const validatorSource = script.slice(
    script.indexOf('function validateGoogleFormTemplate'),
    script.indexOf('function updateEnabledLabel')
  );
  const validateTemplate = new Function(`${validatorSource}; return validateGoogleFormTemplate;`)();
  assert.equal(
    validateTemplate(true, 'https://docs.google.com/forms/d/e/example/viewform?entry.123=SAMPLE'),
    ''
  );
  assert.match(
    validateTemplate(true, 'https://docs.google.com/forms/d/e/example/viewform?usp=publish-editor{CLAIM_CODE}'),
    /Prefill only/
  );
  assert.match(html, /for="promotionEnabled"/);
  assert.match(html, /for="ordersPerReward"/);
  assert.match(html, /for="googleFormUrl"/);
  assert.match(html, /role="status" aria-live="polite"/);
  assert.match(html, /<dialog[\s\S]*Confirm promotion change/);
  assert.match(html, /class="claims-table-wrap"/);
  for (const status of ['all', 'issued', 'reserved', 'claimed', 'expired', 'cancelled']) {
    assert.match(html, new RegExp(`option value="${status}"`));
  }
  assert.match(html, /Loading campaign/);
  assert.match(html, /Loading claims/);
  assert.match(html, /\[hidden\] \{ display:none !important; \}/);
  assert.doesNotMatch(html, /replace the claim field value with <strong>\{CLAIM_CODE\}/);
  assert.match(script, /Current cycle progress .* will reset to zero/);
  assert.match(script, /Existing issued claims will remain redeemable/);
});

test('Android keeps promotion operations but has no settings writer', () => {
  const main = fs.readFileSync(
    path.join(projectRoot, 'app', 'src', 'main', 'java', 'com', 'kape', 'coffeepos', 'MainActivity.kt'),
    'utf8'
  );
  const viewModel = fs.readFileSync(
    path.join(projectRoot, 'app', 'src', 'main', 'java', 'com', 'kape', 'coffeepos', 'viewmodel', 'PosViewModel.kt'),
    'utf8'
  );
  const sync = fs.readFileSync(
    path.join(projectRoot, 'app', 'src', 'main', 'java', 'com', 'kape', 'coffeepos', 'data', 'SupabaseSyncManager.kt'),
    'utf8'
  );
  assert.doesNotMatch(main, /Free Drink QR Promotion/);
  assert.doesNotMatch(viewModel, /savePromotionConfig|togglePromotionEnabled|updatePromotionInterval|updatePromotionFormUrl/);
  assert.doesNotMatch(sync, /suspend fun updatePromotionConfig/);
  assert.match(viewModel, /fun refreshPromotionConfig/);
  assert.match(viewModel, /fun lookupPromotionClaim/);
  assert.match(viewModel, /fun applyPromotionToLine/);
  assert.match(sync, /suspend fun getPromotionConfig/);
  assert.match(sync, /suspend fun getPromotionResult/);
  assert.match(sync, /suspend fun lookupPromotionClaim/);
});
