/**
 * RPC function implementations — replaces Supabase stored procedures
 * Each function receives (params, db) and returns a result object
 */

// ─── apply_inventory_event ────────────────────────────────────────────────────
async function apply_inventory_event({ ingredient_id, delta, branch_id, event_id, reason }, db) {
  if (!ingredient_id || delta == null) throw new Error('ingredient_id and delta required');
  const result = await db.query(`
    INSERT INTO inventory_balance (branch_id, ingredient_id, quantity)
    VALUES ($1, $2, $3)
    ON CONFLICT (branch_id, ingredient_id)
    DO UPDATE SET quantity = inventory_balance.quantity + $3
    RETURNING *
  `, [branch_id || 'main', ingredient_id, delta]);
  return result.rows[0] || {};
}

// ─── get_promotion_config ─────────────────────────────────────────────────────
async function get_promotion_config({ branch_id }, db) {
  try {
    const result = await db.query(
      `SELECT * FROM promotion_config WHERE branch_id = $1 LIMIT 1`,
      [branch_id || 'main']
    );
    if (!result.rows.length) {
      return { available: false, enabled: false, orders_per_reward: 300, cycle_progress: 0, lifetime_order_count: 0 };
    }
    return result.rows[0];
  } catch {
    return { available: false, enabled: false, orders_per_reward: 300, cycle_progress: 0, lifetime_order_count: 0 };
  }
}

// ─── update_promotion_config ──────────────────────────────────────────────────
async function update_promotion_config({ branch_id, enabled, orders_per_reward }, db) {
  try {
    await db.query(`
      INSERT INTO promotion_config (branch_id, enabled, orders_per_reward)
      VALUES ($1, $2, $3)
      ON CONFLICT (branch_id) DO UPDATE SET enabled = $2, orders_per_reward = $3
    `, [branch_id || 'main', enabled, orders_per_reward]);
    return { success: true };
  } catch {
    return { success: false };
  }
}

// ─── get_promotion_result ─────────────────────────────────────────────────────
async function get_promotion_result({ branch_id, order_id, device_id }, db) {
  // Default: no winner (promotion not configured on plain DB)
  return { is_winner: false, sequence_number: 0, orders_per_reward: 300 };
}

// ─── lookup_promotion_claim ───────────────────────────────────────────────────
async function lookup_promotion_claim({ claim_code }, db) {
  try {
    const result = await db.query(
      `SELECT * FROM promotion_award WHERE claim_code = $1 LIMIT 1`,
      [claim_code]
    );
    if (!result.rows.length) return { valid: false };
    const row = result.rows[0];
    return { valid: true, award_id: row.id, claim_code: row.claim_code, status: row.status, expires_at: row.expires_at };
  } catch {
    return { valid: false };
  }
}

// ─── reserve_promotion_claim ──────────────────────────────────────────────────
async function reserve_promotion_claim({ claim_code, device_id }, db) {
  try {
    const result = await db.query(
      `UPDATE promotion_award SET status = 'reserved', reserved_by = $2
       WHERE claim_code = $1 AND status = 'pending' RETURNING *`,
      [claim_code, device_id]
    );
    if (!result.rows.length) return { valid: false, message: 'Claim not available' };
    return { valid: true, reservation_token: result.rows[0].id };
  } catch {
    return { valid: false };
  }
}

// ─── release_promotion_claim ──────────────────────────────────────────────────
async function release_promotion_claim({ claim_code, device_id }, db) {
  try {
    await db.query(
      `UPDATE promotion_award SET status = 'pending', reserved_by = NULL
       WHERE claim_code = $1 AND reserved_by = $2`,
      [claim_code, device_id]
    );
    return { success: true };
  } catch {
    return { success: false };
  }
}

// ─── finalize_promotion_claim ─────────────────────────────────────────────────
async function finalize_promotion_claim({ claim_code, device_id, order_id }, db) {
  try {
    const result = await db.query(
      `UPDATE promotion_award SET status = 'claimed', claimed_at = $3, claimed_order_id = $4
       WHERE claim_code = $1 AND reserved_by = $2 RETURNING *`,
      [claim_code, device_id, Date.now(), order_id]
    );
    if (!result.rows.length) return { success: false };
    return { success: true };
  } catch {
    return { success: false };
  }
}

// ─── mark_promotion_printed ───────────────────────────────────────────────────
async function mark_promotion_printed({ award_id }, db) {
  try {
    await db.query(
      `UPDATE promotion_award SET printed = true WHERE id = $1`,
      [award_id]
    );
    return { success: true };
  } catch {
    return { success: false };
  }
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
  mark_promotion_printed
};
