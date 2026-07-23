/**
 * RPC function implementations — replaces Supabase stored procedures
 * Each function receives (params, db) and returns a result object
 */
const crypto = require('crypto');

const DEFAULT_ORDERS_PER_REWARD = 300;
const RESERVATION_VALIDITY_MS = 15 * 60 * 1000;

function promotionConfigResponse(row, eligibleItemIds = []) {
  return {
    available: true,
    enabled: Boolean(row.enabled),
    orders_per_reward: Number(row.orders_per_reward),
    cycle_progress: Number(row.cycle_progress),
    lifetime_order_count: Number(row.lifetime_order_count),
    google_form_url_template: row.google_form_url_template || '',
    eligible_item_ids: eligibleItemIds,
    claim_validity_days: Number(row.claim_validity_days),
    updated_at: Number(row.updated_at),
    message: null
  };
}

function adminPromotionConfigResponse(config) {
  return {
    available: config.available,
    enabled: config.enabled,
    ordersPerReward: config.orders_per_reward,
    cycleProgress: config.cycle_progress,
    lifetimeOrderCount: config.lifetime_order_count,
    googleFormUrlTemplate: config.google_form_url_template,
    claimValidityDays: config.claim_validity_days,
    updatedAt: config.updated_at,
    rewardPolicy: 'any-drink-base-price'
  };
}

function httpError(status, message) {
  return Object.assign(new Error(message), { status });
}

function assertExpectedPromotionVersion(currentUpdatedAt, expectedUpdatedAt) {
  if (expectedUpdatedAt != null &&
      Number(expectedUpdatedAt) !== Number(currentUpdatedAt)) {
    throw httpError(409, 'Promotion settings changed in another session. Reload and try again.');
  }
}

function validateGoogleFormTemplate(enabled, template) {
  if (!enabled) return null;
  let url;
  try {
    url = new URL(template);
  } catch {
    return 'Enter a valid promotion QR destination URL.';
  }
  if (url.protocol !== 'https:' || !url.hostname) {
    return 'Use a complete HTTPS URL for the promotion QR.';
  }
  return null;
}

function promotionResultResponse(entry, award, config) {
  if (!entry?.is_winner || !award) {
    return {
      is_winner: false,
      orders_per_reward: Number(config.orders_per_reward),
      sequence_number: Number(entry?.sequence_number || 0),
      printed: false
    };
  }
  return {
    is_winner: true,
    award_id: award.id,
    claim_code: award.claim_code,
    qr_url: buildPromotionQrUrl(config.google_form_url_template, award.claim_code),
    orders_per_reward: Number(config.orders_per_reward),
    sequence_number: Number(entry.sequence_number),
    expires_at: Number(award.expires_at),
    printed: award.printed_at != null
  };
}

function promotionClaimResponse(row, valid, message = null) {
  return {
    valid,
    award_id: row?.id || null,
    claim_code: row?.claim_code || null,
    status: row?.status || null,
    expires_at: row?.expires_at == null ? null : Number(row.expires_at),
    form_submitted: Boolean(row?.form_submitted),
    eligible_item_ids: row?.eligible_item_ids || [],
    reservation_token: row?.reserved_cart_token || null,
    message
  };
}

function buildPromotionQrUrl(template, claimCode) {
  const source = String(template || '');
  if (source.includes('{CLAIM_CODE}')) {
    return source.replaceAll('{CLAIM_CODE}', encodeURIComponent(claimCode));
  }
  try {
    const url = new URL(source);
    const claimEntries = [...new Set(
      [...url.searchParams.keys()].filter(key => /^entry\.\d+$/.test(key))
    )];
    if (claimEntries.length !== 1) return source;
    url.searchParams.set(claimEntries[0], claimCode);
    return url.toString();
  } catch {
    return source;
  }
}

async function promotionTransaction(db, work) {
  const client = await db.pool.connect();
  try {
    await client.query('BEGIN');
    const result = await work(client);
    await client.query('COMMIT');
    return result;
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {});
    throw error;
  } finally {
    client.release();
  }
}

async function ensurePromotionCampaign(client) {
  await client.query(`INSERT INTO promotion_campaign(id)
    VALUES ('default') ON CONFLICT (id) DO NOTHING`);
  const result = await client.query(
    "SELECT * FROM promotion_campaign WHERE id='default' LIMIT 1"
  );
  return result.rows[0];
}

async function promotionEligibleItemIds(client) {
  const result = await client.query(
    "SELECT item_id FROM promotion_eligible_item WHERE campaign_id='default' ORDER BY item_id"
  );
  return result.rows.map(row => row.item_id);
}

async function enrichPromotionClaim(client, row) {
  if (!row) return row;
  const [submission, eligibleItemIds] = await Promise.all([
    client.query('SELECT 1 FROM promotion_form_submission WHERE claim_code=$1 LIMIT 1', [row.claim_code]),
    promotionEligibleItemIds(client)
  ]);
  return {
    ...row,
    form_submitted: submission.rowCount > 0,
    eligible_item_ids: eligibleItemIds
  };
}

// ─── apply_inventory_event ────────────────────────────────────────────────────
async function apply_inventory_event({ ingredient_id, delta, delta_quantity, branch_id, event_id, reason, created_at, authenticated_device_id }, db) {
  delta = delta ?? delta_quantity;
  if (!ingredient_id || delta == null) throw new Error('ingredient_id and delta required');
  const client = await db.pool.connect();
  try {
    await client.query('BEGIN');
    if (event_id) {
      const inserted = await client.query(`INSERT INTO sync_inventory_event
        (event_id, branch_id, device_id, ingredient_id, delta_quantity, reason, created_at)
        VALUES ($1,$2,$3,$4,$5,$6,$7) ON CONFLICT (event_id) DO NOTHING RETURNING event_id`,
        [event_id, branch_id || 'main', authenticated_device_id || null, ingredient_id, delta, reason || null, created_at || Date.now()]);
      if (!inserted.rowCount) {
        const existing = await client.query('SELECT * FROM inventory_balance WHERE branch_id=$1 AND ingredient_id=$2', [branch_id || 'main', ingredient_id]);
        await client.query('COMMIT');
        return existing.rows[0] || {};
      }
    }
    const result = await client.query(`INSERT INTO inventory_balance (branch_id, ingredient_id, quantity)
      VALUES ($1, $2, $3) ON CONFLICT (branch_id, ingredient_id)
      DO UPDATE SET quantity = inventory_balance.quantity + EXCLUDED.quantity RETURNING *`,
      [branch_id || 'main', ingredient_id, delta]);
    await client.query('COMMIT');
    return result.rows[0] || {};
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {});
    throw error;
  } finally { client.release(); }
}

// ─── get_promotion_config ─────────────────────────────────────────────────────
async function get_promotion_config(params, db) {
  try {
    return await promotionTransaction(db, async client => {
      const campaign = await ensurePromotionCampaign(client);
      return promotionConfigResponse(campaign, await promotionEligibleItemIds(client));
    });
  } catch (error) {
    if (error.code !== '42P01') throw error;
    return {
      available: false,
      enabled: false,
      orders_per_reward: DEFAULT_ORDERS_PER_REWARD,
      cycle_progress: 0,
      lifetime_order_count: 0,
      google_form_url_template: '',
      eligible_item_ids: [],
      claim_validity_days: 30,
      updated_at: 0,
      message: 'Promotion database migration is not installed on Render.'
    };
  }
}

// ─── update_promotion_config ──────────────────────────────────────────────────
async function update_promotion_config(params, db) {
  const enabled = Boolean(params.enabled);
  const ordersPerReward = Number(params.orders_per_reward);
  const formUrl = String(params.google_form_url_template || '').trim();
  const eligibleItemIds = Array.isArray(params.eligible_item_ids)
    ? [...new Set(params.eligible_item_ids.map(String).filter(Boolean))]
    : [];
  if (!Number.isInteger(ordersPerReward) || ordersPerReward < 1 || ordersPerReward > 100_000) {
    throw httpError(400, 'Orders per QR reward must be from 1 to 100,000.');
  }
  const templateError = validateGoogleFormTemplate(enabled, formUrl);
  if (templateError) throw httpError(400, templateError);
  return promotionTransaction(db, async client => {
    const existing = await ensurePromotionCampaign(client);
    assertExpectedPromotionVersion(existing.updated_at, params.expected_updated_at);
    const intervalChanged = Number(existing.orders_per_reward) !== ordersPerReward;
    const nextUpdatedAt = Math.max(Date.now(), Number(existing.updated_at) + 1);
    const result = await client.query(`UPDATE promotion_campaign SET
        enabled=$2,
        orders_per_reward=$3,
        cycle_progress=CASE WHEN $4 THEN 0 ELSE cycle_progress END,
        google_form_url_template=$5,
        updated_at=$6
      WHERE id=$1 RETURNING *`,
      ['default', enabled, ordersPerReward, intervalChanged, formUrl, nextUpdatedAt]);
    await client.query("DELETE FROM promotion_eligible_item WHERE campaign_id='default'");
    for (const itemId of eligibleItemIds) {
      await client.query(`INSERT INTO promotion_eligible_item(campaign_id,item_id)
        VALUES ('default',$1) ON CONFLICT DO NOTHING`, [itemId]);
    }
    return promotionConfigResponse(result.rows[0], eligibleItemIds);
  });
}

async function list_promotion_claims(params, db) {
  const { status, limit, offset } = validatePromotionClaimListInput(params);
  const currentTime = Date.now();
  const effectiveStatus = `CASE
    WHEN award.status IN ('issued','reserved') AND award.expires_at < $1 THEN 'expired'
    ELSE award.status END`;
  const filter = status === 'all' ? '' : `WHERE effective_status=$2`;
  const baseValues = status === 'all' ? [currentTime] : [currentTime, status];
  const listValues = [...baseValues, limit, offset];
  const limitIndex = baseValues.length + 1;
  const offsetIndex = baseValues.length + 2;
  const [itemsResult, totalResult, countsResult] = await Promise.all([
    db.query(`WITH claims AS (
        SELECT award.*, entry.cycle_position,
          ${effectiveStatus} AS effective_status,
          EXISTS (
            SELECT 1 FROM promotion_form_submission submission
            WHERE submission.claim_code=award.claim_code
          ) AS form_submitted
        FROM promotion_award award
        JOIN promotion_entry entry ON entry.order_id=award.order_id
      )
      SELECT * FROM claims ${filter}
      ORDER BY issued_at DESC, sequence_number DESC
      LIMIT $${limitIndex} OFFSET $${offsetIndex}`, listValues),
    db.query(`WITH claims AS (
        SELECT ${effectiveStatus} AS effective_status
        FROM promotion_award award
      )
      SELECT COUNT(*)::INTEGER AS total FROM claims ${filter}`, baseValues),
    db.query(`WITH claims AS (
        SELECT ${effectiveStatus} AS effective_status
        FROM promotion_award award
      )
      SELECT effective_status, COUNT(*)::INTEGER AS count
      FROM claims GROUP BY effective_status`, [currentTime])
  ]);
  const statusCounts = Object.fromEntries(
    ['issued', 'reserved', 'claimed', 'expired', 'cancelled'].map(key => [key, 0])
  );
  for (const row of countsResult.rows) statusCounts[row.effective_status] = Number(row.count);
  return {
    items: itemsResult.rows.map(row => ({
      awardId: String(row.id),
      claimCode: row.claim_code,
      status: row.effective_status,
      sequenceNumber: Number(row.sequence_number),
      cyclePosition: Number(row.cycle_position),
      sourceOrderId: row.order_id,
      issuedAt: Number(row.issued_at),
      expiresAt: Number(row.expires_at),
      printedAt: row.printed_at == null ? null : Number(row.printed_at),
      printCount: Number(row.print_count),
      formSubmitted: Boolean(row.form_submitted),
      reservedAt: row.reserved_at == null ? null : Number(row.reserved_at),
      redemptionOrderId: row.redemption_order_id,
      claimedAt: row.claimed_at == null ? null : Number(row.claimed_at)
    })),
    total: Number(totalResult.rows[0].total),
    statusCounts,
    limit,
    offset
  };
}

function validatePromotionClaimListInput(params = {}) {
  const allowedStatuses = new Set(['all', 'issued', 'reserved', 'claimed', 'expired', 'cancelled']);
  const status = String(params.status || 'all').toLowerCase();
  if (!allowedStatuses.has(status)) throw httpError(400, 'Invalid promotion claim status.');
  const limit = Number(params.limit ?? 50);
  const offset = Number(params.offset ?? 0);
  if (!Number.isInteger(limit) || limit < 1 || limit > 100) {
    throw httpError(400, 'Claim page size must be from 1 to 100.');
  }
  if (!Number.isInteger(offset) || offset < 0) {
    throw httpError(400, 'Claim offset must be a nonnegative integer.');
  }
  return { status, limit, offset };
}

function createPromotionService(db) {
  return {
    async getConfig() {
      return adminPromotionConfigResponse(await get_promotion_config({}, db));
    },
    async updateConfig(input) {
      if (typeof input.enabled !== 'boolean') {
        throw httpError(400, 'Promotion enabled state must be true or false.');
      }
      if (typeof input.googleFormUrlTemplate !== 'string') {
        throw httpError(400, 'Google Form URL template is required.');
      }
      if (input.expectedUpdatedAt == null ||
          !Number.isSafeInteger(Number(input.expectedUpdatedAt)) ||
          Number(input.expectedUpdatedAt) < 0) {
        throw httpError(400, 'Reload promotion settings before saving.');
      }
      const config = await update_promotion_config({
        enabled: input.enabled,
        orders_per_reward: input.ordersPerReward,
        google_form_url_template: input.googleFormUrlTemplate,
        eligible_item_ids: [],
        expected_updated_at: input.expectedUpdatedAt
      }, db);
      return adminPromotionConfigResponse(config);
    },
    listClaims(input) {
      return list_promotion_claims(input, db);
    }
  };
}

// ─── get_promotion_result ─────────────────────────────────────────────────────
async function get_promotion_result(params, db) {
  const orderId = String(params.order_id || '').trim();
  if (!orderId) throw new Error('Order ID is required.');
  return promotionTransaction(db, async client => {
    await ensurePromotionCampaign(client);
    const config = (await client.query(
      "SELECT * FROM promotion_campaign WHERE id='default' FOR UPDATE"
    )).rows[0];

    const prior = await client.query(`SELECT entry.sequence_number, entry.is_winner,
        award.id, award.claim_code, award.expires_at, award.printed_at
      FROM promotion_entry entry
      LEFT JOIN promotion_award award ON award.order_id=entry.order_id
      WHERE entry.order_id=$1 LIMIT 1`, [orderId]);
    if (prior.rowCount) {
      const row = prior.rows[0];
      const award = row.id ? row : null;
      return promotionResultResponse(row, award, config);
    }

    if (!config.enabled) {
      return {
        is_winner: false,
        orders_per_reward: Number(config.orders_per_reward),
        sequence_number: 0,
        printed: false
      };
    }

    const paidOrder = await client.query(`SELECT orders.id FROM pos_order orders
      WHERE orders.id=$1 AND orders.status='paid'
        AND EXISTS (SELECT 1 FROM payment WHERE payment.order_id=orders.id)
      LIMIT 1`, [orderId]);
    if (!paidOrder.rowCount) throw new Error('Paid order is not available on Render yet.');

    const sequenceNumber = Number(config.lifetime_order_count) + 1;
    const nextProgress = Number(config.cycle_progress) + 1;
    const isWinner = nextProgress >= Number(config.orders_per_reward);
    await client.query(`UPDATE promotion_campaign SET
        lifetime_order_count=$2,
        cycle_progress=$3,
        updated_at=$4
      WHERE id=$1`,
      ['default', sequenceNumber, isWinner ? 0 : nextProgress, Date.now()]);
    const entry = (await client.query(`INSERT INTO promotion_entry
        (order_id, campaign_id, sequence_number, cycle_position, interval_at_entry, is_winner, counted_at)
      VALUES ($1,'default',$2,$3,$4,$5,$6) RETURNING *`,
      [orderId, sequenceNumber, nextProgress, Number(config.orders_per_reward), isWinner, Date.now()])).rows[0];

    if (!isWinner) return promotionResultResponse(entry, null, config);

    let claimCode;
    do {
      claimCode = `FREE-${crypto.randomBytes(5).toString('hex').toUpperCase()}`;
    } while ((await client.query('SELECT 1 FROM promotion_award WHERE claim_code=$1', [claimCode])).rowCount);
    const award = (await client.query(`INSERT INTO promotion_award
        (campaign_id, order_id, sequence_number, interval_at_award, claim_code,
         status, issued_at, expires_at)
      VALUES ('default',$1,$2,$3,$4,'issued',$5,$6) RETURNING *`,
      [
        orderId,
        sequenceNumber,
        Number(config.orders_per_reward),
        claimCode,
        Date.now(),
        Date.now() + Number(config.claim_validity_days) * 24 * 60 * 60 * 1000
      ])).rows[0];
    return promotionResultResponse(entry, award, config);
  });
}

// ─── lookup_promotion_claim ───────────────────────────────────────────────────
async function lookup_promotion_claim(params, db) {
  const claimCode = String(params.claim_code || '').trim().toUpperCase();
  if (!claimCode) return promotionClaimResponse(null, false, 'Enter a claim code.');
  return promotionTransaction(db, async client => {
    await client.query(`UPDATE promotion_award SET
        status='issued', reserved_device_id=NULL, reserved_employee_id=NULL,
        reserved_cart_token=NULL, reserved_at=NULL
      WHERE claim_code=$1 AND status='reserved'
        AND reserved_at < $2`, [claimCode, Date.now() - RESERVATION_VALIDITY_MS]);
    const result = await client.query(
      'SELECT * FROM promotion_award WHERE claim_code=$1 LIMIT 1',
      [claimCode]
    );
    if (!result.rows.length) return promotionClaimResponse(null, false, 'Claim code was not found.');
    const row = await enrichPromotionClaim(client, result.rows[0]);
    if (Number(row.expires_at) < Date.now()) {
      return promotionClaimResponse(row, false, 'This claim has expired.');
    }
    if (row.status === 'claimed') {
      return promotionClaimResponse(row, false, 'This claim has already been redeemed.');
    }
    if (row.status === 'cancelled') {
      return promotionClaimResponse(row, false, 'This claim was cancelled.');
    }
    if (row.status === 'reserved') {
      return promotionClaimResponse(row, false, 'This claim is currently reserved at another checkout.');
    }
    return promotionClaimResponse(row, true);
  });
}

// ─── reserve_promotion_claim ──────────────────────────────────────────────────
async function reserve_promotion_claim(params, db) {
  const claimCode = String(params.claim_code || '').trim().toUpperCase();
  const deviceId = String(params.authenticated_device_id || params.device_id || '').trim();
  const employeeId = String(params.employee_id || '').trim();
  if (!claimCode || !deviceId) return promotionClaimResponse(null, false, 'Claim code and device are required.');
  return promotionTransaction(db, async client => {
    const found = await client.query(
      'SELECT * FROM promotion_award WHERE claim_code=$1 FOR UPDATE',
      [claimCode]
    );
    if (!found.rowCount) return promotionClaimResponse(null, false, 'Claim code was not found.');
    let row = await enrichPromotionClaim(client, found.rows[0]);
    if (Number(row.expires_at) < Date.now()) return promotionClaimResponse(row, false, 'This claim has expired.');
    if (row.status === 'claimed') return promotionClaimResponse(row, false, 'This claim has already been redeemed.');
    if (row.status === 'cancelled') return promotionClaimResponse(row, false, 'This claim was cancelled.');
    if (row.status === 'reserved' && Number(row.reserved_at) + RESERVATION_VALIDITY_MS >= Date.now()) {
      if (row.reserved_device_id === deviceId) return promotionClaimResponse(row, true);
      return promotionClaimResponse(row, false, 'This claim is currently reserved at another checkout.');
    }
    const token = crypto.randomUUID();
    row = (await client.query(`UPDATE promotion_award SET
        status='reserved', reserved_device_id=$2, reserved_employee_id=$3,
        reserved_cart_token=$4, reserved_at=$5
      WHERE claim_code=$1 RETURNING *`,
      [claimCode, deviceId, employeeId || null, token, Date.now()])).rows[0];
    row = await enrichPromotionClaim(client, row);
    return promotionClaimResponse(row, true);
  });
}

// ─── release_promotion_claim ──────────────────────────────────────────────────
async function release_promotion_claim(params, db) {
  const token = String(params.reservation_token || '').trim();
  const deviceId = String(params.authenticated_device_id || params.device_id || '').trim();
  if (!token || !deviceId) throw new Error('Reservation token and device are required.');
  await db.query(`UPDATE promotion_award SET
      status='issued', reserved_device_id=NULL, reserved_employee_id=NULL,
      reserved_cart_token=NULL, reserved_at=NULL
    WHERE reserved_cart_token=$1 AND reserved_device_id=$2 AND status='reserved'`,
    [token, deviceId]);
  return { success: true };
}

// ─── finalize_promotion_claim ─────────────────────────────────────────────────
async function finalize_promotion_claim(params, db) {
  const token = String(params.reservation_token || '').trim();
  const deviceId = String(params.authenticated_device_id || params.device_id || '').trim();
  const redemptionOrderId = String(params.redemption_order_id || params.order_id || '').trim();
  const employeeId = String(params.employee_id || '').trim();
  if (!token || !deviceId || !redemptionOrderId) {
    throw new Error('Reservation token, device, and redemption order are required.');
  }
  const result = await db.query(`UPDATE promotion_award SET
      status='claimed', claimed_at=$3, redemption_order_id=$4, claimed_employee_id=$5
    WHERE reserved_cart_token=$1 AND reserved_device_id=$2
      AND status='reserved' AND reserved_at >= $6 RETURNING id`,
    [token, deviceId, Date.now(), redemptionOrderId, employeeId || null, Date.now() - RESERVATION_VALIDITY_MS]);
  if (!result.rowCount) throw new Error('Promotion reservation is no longer valid.');
  return { success: true };
}

// ─── mark_promotion_printed ───────────────────────────────────────────────────
async function mark_promotion_printed(params, db) {
  const awardId = String(params.award_id || '').trim();
  const deviceId = String(params.authenticated_device_id || params.device_id || '').trim();
  if (!awardId) throw new Error('Award ID is required.');
  await db.query(
    `UPDATE promotion_award SET
      printed_at=COALESCE(printed_at,$2),
      print_count=print_count+1,
      last_print_device_id=$3
    WHERE id=$1`,
    [awardId, Date.now(), deviceId || null]
  );
  return { success: true };
}

module.exports = {
  apply_inventory_event,
  get_promotion_config,
  update_promotion_config,
  get_promotion_result,
  lookup_promotion_claim,
  reserve_promotion_claim,
  release_promotion_claim,
  finalize_promotion_claim,
  mark_promotion_printed,
  list_promotion_claims,
  createPromotionService,
  _test: {
    buildPromotionQrUrl,
    promotionConfigResponse,
    promotionResultResponse,
    promotionClaimResponse,
    adminPromotionConfigResponse,
    validateGoogleFormTemplate,
    assertExpectedPromotionVersion,
    validatePromotionClaimListInput
  }
};
