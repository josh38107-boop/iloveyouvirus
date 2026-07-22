require('dotenv').config();
const db = require('./db');

db.query(`SELECT
  to_regclass('public.sync_device') AS sync_device,
  to_regclass('public.sync_enrollment') AS sync_enrollment,
  to_regclass('public.sync_mutation') AS sync_mutation,
  to_regclass('public.sync_change') AS sync_change`)
  .then(result => console.log(JSON.stringify(result.rows[0])))
  .catch(error => { console.error(error.message); process.exitCode = 1; })
  .finally(() => db.pool.end());
