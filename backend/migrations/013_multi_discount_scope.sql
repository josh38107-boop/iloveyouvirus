ALTER TABLE discount_rule
  DROP CONSTRAINT IF EXISTS discount_rule_scope_check;

ALTER TABLE discount_rule
  ADD CONSTRAINT discount_rule_scope_check
  CHECK (scope IN ('item', 'order', 'multi'));
