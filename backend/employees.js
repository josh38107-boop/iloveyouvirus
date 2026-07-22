const crypto = require('crypto');

function httpError(status, message) {
  return Object.assign(new Error(message), { status });
}

function normalizeEmployeeId(name) {
  return String(name || '').toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '') || 'employee';
}

function validateEmployeeInput(input, options = {}) {
  const editing = Boolean(options.editing);
  const name = String(input?.name || '').trim();
  const role = String(input?.role || '').trim().toLowerCase();
  const active = editing ? input?.active : true;
  const suppliedPin = input?.pin == null ? null : String(input.pin).trim();
  const pin = suppliedPin === '' && editing ? null : suppliedPin;

  if (!name) throw httpError(400, 'Enter a staff name.');
  if (name.length > 120) throw httpError(400, 'Staff name must be 120 characters or fewer.');
  if (!['cashier', 'manager'].includes(role)) throw httpError(400, 'Choose Cashier or Manager as the role.');
  if (editing && typeof active !== 'boolean') throw httpError(400, 'Active must be true or false.');
  if (!editing && pin == null) throw httpError(400, 'Enter a 4 to 6 digit PIN.');
  if (pin != null && !/^\d{4,6}$/.test(pin)) throw httpError(400, 'PIN must contain 4 to 6 digits.');

  return { name, role, active, pin };
}

function publicEmployee(row) {
  return { id: row.id, name: row.name, role: row.role, active: Boolean(row.active) };
}

function createEmployeeService(db, options = {}) {
  const branchId = options.branchId || 'main';
  const clock = options.now || Date.now;
  const randomId = options.randomId || (() => crypto.randomUUID().slice(0, 8));

  async function transaction(work) {
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

  async function recordChange(client, employee) {
    await client.query(`INSERT INTO sync_change
      (branch_id, entity_type, entity_id, operation, payload, device_id, created_at)
      VALUES ($1,'employee',$2,'upsert',$3,NULL,$4)`,
      [branchId, employee.id, employee, clock()]);
  }

  async function ensurePinAvailable(client, pin, employeeId = null) {
    const result = await client.query(`SELECT id FROM employee
      WHERE pin=$1 AND active=TRUE AND ($2::text IS NULL OR id<>$2) LIMIT 1`, [pin, employeeId]);
    if (result.rowCount) throw httpError(409, 'PIN already in use by another active staff member.');
  }

  async function list() {
    const result = await db.query('SELECT id,name,role,active FROM employee ORDER BY name,id');
    return result.rows.map(publicEmployee);
  }

  async function create(input) {
    const data = validateEmployeeInput(input);
    return transaction(async client => {
      await client.query('LOCK TABLE employee IN SHARE ROW EXCLUSIVE MODE');
      await ensurePinAvailable(client, data.pin);
      const id = `${normalizeEmployeeId(data.name)}-${randomId()}`;
      const existing = await client.query('SELECT id FROM employee WHERE id=$1', [id]);
      if (existing.rowCount) throw httpError(409, 'Could not create a unique staff ID. Please try again.');
      let employee;
      try {
        employee = (await client.query(`INSERT INTO employee(id,name,pin,role,active)
          VALUES($1,$2,$3,$4,TRUE) RETURNING *`, [id, data.name, data.pin, data.role])).rows[0];
      } catch (error) {
        if (error.code === '23505') throw httpError(409, 'That staff record already exists.');
        throw error;
      }
      await recordChange(client, employee);
      return publicEmployee(employee);
    });
  }

  async function update(id, input) {
    const data = validateEmployeeInput(input, { editing: true });
    return transaction(async client => {
      await client.query('LOCK TABLE employee IN SHARE ROW EXCLUSIVE MODE');
      const found = await client.query('SELECT * FROM employee WHERE id=$1 FOR UPDATE', [id]);
      if (!found.rowCount) throw httpError(404, 'Staff member not found.');
      const current = found.rows[0];
      const pin = data.pin == null ? current.pin : data.pin;
      if (data.active) await ensurePinAvailable(client, pin, id);
      const employee = (await client.query(`UPDATE employee SET name=$1,pin=$2,role=$3,active=$4
        WHERE id=$5 RETURNING *`, [data.name, pin, data.role, data.active, id])).rows[0];
      await recordChange(client, employee);
      return publicEmployee(employee);
    });
  }

  async function deactivate(id) {
    return transaction(async client => {
      const found = await client.query('SELECT * FROM employee WHERE id=$1 FOR UPDATE', [id]);
      if (!found.rowCount) throw httpError(404, 'Staff member not found.');
      if (!found.rows[0].active) return publicEmployee(found.rows[0]);
      const employee = (await client.query('UPDATE employee SET active=FALSE WHERE id=$1 RETURNING *', [id])).rows[0];
      await recordChange(client, employee);
      return publicEmployee(employee);
    });
  }

  return { list, create, update, deactivate };
}

module.exports = { createEmployeeService, normalizeEmployeeId, validateEmployeeInput, publicEmployee };
