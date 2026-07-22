require('dotenv').config();
const db = require('./db');

async function audit() {
  const tables = ['pos_order', 'payment', 'order_line', 'shift', 'ingredient', 'inventory_balance'];
  const counts = {};
  for (const table of tables) {
    counts[table] = Number((await db.query(`SELECT COUNT(*) AS count FROM "${table}"`)).rows[0].count);
  }
  const totals = (await db.query(`SELECT
    (SELECT COALESCE(SUM(total_cents), 0) FROM pos_order) AS order_total_cents,
    (SELECT COALESCE(SUM(amount_cents), 0) FROM payment) AS payment_total_cents`)).rows[0];
  console.log(JSON.stringify({ capturedAt: new Date().toISOString(), counts, totals }, null, 2));
}

audit().then(() => db.pool.end()).catch(async (error) => {
  console.error(error); await db.pool.end().catch(() => {}); process.exit(1);
});
