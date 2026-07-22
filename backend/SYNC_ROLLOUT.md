# Render Cloud synchronization rollout

The migration is additive: it leaves all operational POS tables and rows in place. Do not run it against production until a PostgreSQL backup has completed successfully.

## Pre-deploy verification

1. Create a Render PostgreSQL backup or recovery snapshot.
2. Restore it to a temporary database.
3. Run `npm run audit:data` and save its JSON output.
4. Point `DATABASE_URL` at the restored database and run `npm run migrate` from `backend`.
5. Run `npm run audit:data` again. Every count and financial total must match exactly.

## Render configuration

- Build: `cd backend && npm ci`
- Pre-deploy: `cd backend && npm run migrate`
- Start: `cd backend && npm start`
- Liveness: `/health`
- Database readiness: `/ready`
- Required secrets: `DATABASE_URL`, `SESSION_SECRET`, `TOKEN_PEPPER`, `ADMIN_USERNAME`, `ADMIN_PASSWORD`
- Set `ALLOWED_ORIGINS` to the public Render service origin.
- Set `LEGACY_SYNC_ENABLED=true` until every tablet is enrolled.

## Device migration

Open **Cloud Devices** in the admin website, create a 10-minute code with the correct role, then enter the Render URL, device name, and code in Android **Render Cloud Synchronization** settings. Migrate the Manager Tablet first, validate reports and inventory, then enroll each Counter.

After all tablets are stable, set `LEGACY_SYNC_ENABLED=false`, rotate `API_KEY`, and redeploy. Rollback uses the prior Render release; leave the additive migration tables intact.
