-- Older installations used UUID branch identifiers. The Render sync API uses
-- stable text identifiers ("main" by default), so normalize legacy tables and
-- carry their data to the branch assigned to the same manager device.

ALTER TABLE sync_device_authority
  ALTER COLUMN branch_id TYPE TEXT USING branch_id::text;
ALTER TABLE sync_tombstone
  ALTER COLUMN branch_id TYPE TEXT USING branch_id::text;
ALTER TABLE inventory_balance
  ALTER COLUMN branch_id TYPE TEXT USING branch_id::text;

CREATE TEMP TABLE legacy_branch_mapping ON COMMIT DROP AS
SELECT DISTINCT authority.branch_id AS old_branch_id, device.branch_id AS new_branch_id
FROM sync_device_authority authority
JOIN sync_device device ON device.hardware_id = authority.manager_device_id
WHERE authority.branch_id <> device.branch_id;

-- Update rows in place so any installation-specific legacy columns (for
-- example inventory_balance.updated_at) are preserved without assumptions.
UPDATE sync_tombstone tombstone
SET branch_id = mapping.new_branch_id
FROM legacy_branch_mapping mapping
WHERE tombstone.branch_id = mapping.old_branch_id;

UPDATE inventory_balance balance
SET branch_id = mapping.new_branch_id
FROM legacy_branch_mapping mapping
WHERE balance.branch_id = mapping.old_branch_id;

UPDATE sync_device_authority authority
SET branch_id = mapping.new_branch_id
FROM legacy_branch_mapping mapping
WHERE authority.branch_id = mapping.old_branch_id;

-- Preserve the authority already granted to a legacy manager tablet when it
-- was re-enrolled before role-aware enrollment was introduced.
UPDATE sync_device device
SET role = 'manager'
FROM sync_device_authority authority
WHERE device.branch_id = authority.branch_id
  AND device.hardware_id = authority.manager_device_id
  AND device.role <> 'manager';
