-- Business tables for Kanlungan Coffee Garage POS

CREATE TABLE IF NOT EXISTS menu_category (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  sort_order INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS menu_item (
  id TEXT PRIMARY KEY,
  category_id TEXT REFERENCES menu_category(id) ON DELETE SET NULL,
  name TEXT NOT NULL,
  description TEXT,
  base_price_cents INTEGER NOT NULL DEFAULT 0,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  complementary_exclusions TEXT[] DEFAULT '{}'
);

CREATE TABLE IF NOT EXISTS modifier_group (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  required BOOLEAN NOT NULL DEFAULT FALSE,
  max_selections INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS modifier_option (
  id TEXT PRIMARY KEY,
  group_id TEXT REFERENCES modifier_group(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  price_delta_cents INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS menu_item_modifier_group (
  item_id TEXT REFERENCES menu_item(id) ON DELETE CASCADE,
  group_id TEXT REFERENCES modifier_group(id) ON DELETE CASCADE,
  PRIMARY KEY (item_id, group_id)
);

CREATE TABLE IF NOT EXISTS ingredient (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  unit TEXT NOT NULL DEFAULT 'pcs',
  quantity_on_hand DOUBLE PRECISION NOT NULL DEFAULT 0,
  low_stock_threshold DOUBLE PRECISION NOT NULL DEFAULT 0,
  takeout_only BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS recipe_ingredient (
  item_id TEXT REFERENCES menu_item(id) ON DELETE CASCADE,
  ingredient_id TEXT REFERENCES ingredient(id) ON DELETE CASCADE,
  quantity_used DOUBLE PRECISION NOT NULL DEFAULT 0,
  PRIMARY KEY (item_id, ingredient_id)
);

CREATE TABLE IF NOT EXISTS modifier_recipe_ingredient (
  option_id TEXT REFERENCES modifier_option(id) ON DELETE CASCADE,
  ingredient_id TEXT REFERENCES ingredient(id) ON DELETE CASCADE,
  quantity_used DOUBLE PRECISION NOT NULL DEFAULT 0,
  replaces_ingredient_id TEXT REFERENCES ingredient(id) ON DELETE SET NULL,
  PRIMARY KEY (option_id, ingredient_id)
);

CREATE TABLE IF NOT EXISTS payment_method (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  is_system BOOLEAN NOT NULL DEFAULT FALSE,
  payment_category TEXT
);

CREATE TABLE IF NOT EXISTS employee (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  pin TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'cashier',
  active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS store_settings (
  id TEXT PRIMARY KEY,
  store_name TEXT NOT NULL DEFAULT 'Kanlungan Coffee Garage',
  tax_rate_percent DOUBLE PRECISION NOT NULL DEFAULT 0,
  tip_presets TEXT[] DEFAULT '{}',
  receipt_footer TEXT DEFAULT '',
  senior_discount_percent DOUBLE PRECISION NOT NULL DEFAULT 20,
  pwd_discount_percent DOUBLE PRECISION NOT NULL DEFAULT 20,
  void_refund_pin TEXT DEFAULT '',
  business_day_cutoff_minutes INTEGER NOT NULL DEFAULT 120,
  business_day_settings_updated_at BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sync_device_authority (
  branch_id TEXT PRIMARY KEY,
  manager_device_id TEXT,
  manager_device_name TEXT,
  revision INTEGER NOT NULL DEFAULT 0,
  updated_at BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sync_tombstone (
  branch_id TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  deleted_by_device TEXT,
  deleted_at BIGINT NOT NULL,
  PRIMARY KEY (branch_id, entity_type, entity_id)
);

CREATE TABLE IF NOT EXISTS shift (
  device_id TEXT NOT NULL,
  id TEXT NOT NULL,
  employee_id TEXT,
  opened_at BIGINT NOT NULL,
  closed_at BIGINT,
  starting_cash_cents INTEGER NOT NULL DEFAULT 0,
  ending_cash_cents INTEGER,
  cash_added_cents INTEGER NOT NULL DEFAULT 0,
  cash_removed_cents INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (device_id, id)
);

CREATE TABLE IF NOT EXISTS pos_order (
  id TEXT PRIMARY KEY,
  status TEXT NOT NULL DEFAULT 'open',
  employee_id TEXT,
  shift_id TEXT,
  shift_device_id TEXT,
  subtotal_cents INTEGER NOT NULL DEFAULT 0,
  discount_cents INTEGER NOT NULL DEFAULT 0,
  tax_cents INTEGER NOT NULL DEFAULT 0,
  tip_cents INTEGER NOT NULL DEFAULT 0,
  total_cents INTEGER NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  paid_at BIGINT,
  void_reason TEXT,
  customer_name TEXT,
  table_number TEXT,
  order_type TEXT NOT NULL DEFAULT 'dine_in'
);

CREATE TABLE IF NOT EXISTS order_line (
  device_id TEXT NOT NULL,
  id TEXT NOT NULL,
  order_id TEXT REFERENCES pos_order(id) ON DELETE CASCADE,
  item_id TEXT,
  name TEXT NOT NULL,
  quantity INTEGER NOT NULL DEFAULT 1,
  unit_price_cents INTEGER NOT NULL DEFAULT 0,
  modifiers JSONB DEFAULT '[]',
  notes TEXT,
  discount_category TEXT,
  discount_cents INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (device_id, id)
);

CREATE TABLE IF NOT EXISTS payment (
  device_id TEXT NOT NULL,
  id TEXT NOT NULL,
  order_id TEXT REFERENCES pos_order(id) ON DELETE CASCADE,
  method TEXT NOT NULL,
  amount_cents INTEGER NOT NULL DEFAULT 0,
  amount_tendered_cents INTEGER,
  change_cents INTEGER,
  created_at BIGINT NOT NULL,
  payment_category TEXT,
  PRIMARY KEY (device_id, id)
);

CREATE TABLE IF NOT EXISTS receipt (
  order_id TEXT PRIMARY KEY REFERENCES pos_order(id) ON DELETE CASCADE,
  receipt_number TEXT,
  text TEXT,
  created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS stock_snapshot (
  device_id TEXT NOT NULL,
  shift_id TEXT NOT NULL,
  ingredient_id TEXT REFERENCES ingredient(id) ON DELETE CASCADE,
  quantity DOUBLE PRECISION NOT NULL DEFAULT 0,
  PRIMARY KEY (device_id, shift_id, ingredient_id)
);

CREATE TABLE IF NOT EXISTS inventory_balance (
  branch_id TEXT NOT NULL,
  ingredient_id TEXT REFERENCES ingredient(id) ON DELETE CASCADE,
  quantity DOUBLE PRECISION NOT NULL DEFAULT 0,
  PRIMARY KEY (branch_id, ingredient_id)
);

CREATE TABLE IF NOT EXISTS order_inventory_add_on (
  id TEXT PRIMARY KEY,
  order_id TEXT REFERENCES pos_order(id) ON DELETE CASCADE,
  ingredient_id TEXT REFERENCES ingredient(id) ON DELETE SET NULL,
  quantity DOUBLE PRECISION NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  restored_at BIGINT,
  updated_at BIGINT NOT NULL
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_menu_item_category ON menu_item(category_id);
CREATE INDEX IF NOT EXISTS idx_modifier_option_group ON modifier_option(group_id);
CREATE INDEX IF NOT EXISTS idx_recipe_ingredient_item ON recipe_ingredient(item_id);
CREATE INDEX IF NOT EXISTS idx_inventory_balance_branch ON inventory_balance(branch_id);
CREATE INDEX IF NOT EXISTS idx_shift_device ON shift(device_id);
CREATE INDEX IF NOT EXISTS idx_order_line_order ON order_line(order_id);
CREATE INDEX IF NOT EXISTS idx_payment_method_enabled ON payment_method(enabled);
