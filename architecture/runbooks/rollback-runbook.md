# Rollback runbook

When: error rate or latency worsens after a deploy, a pod will not become Ready, or a migration misbehaves.
Automatic path: `deploy.yml` already runs `kubectl rollout undo` for any deployment that fails `rollout status` (5 min).

## 1. Decide (2 minutes)
- Did it start at the deploy time? Check the dashboard incidents and `kubectl -n cloudpulse rollout history deploy/<name>`.
- Did the release include a database migration? If yes, read 4 before rolling back.

## 2. Fast rollback (one service or all)
```bash
kubectl -n cloudpulse rollout undo deploy/order-service        # one service
kubectl -n cloudpulse rollout status deploy/order-service --timeout=300s
```
CI path: run workflow `rollback` with the environment (leave `sha` empty) and approve in the protected environment.

## 3. Roll to a known-good release
Run workflow `rollback` with `sha=<released commit>`; it triggers `deploy.yml`, which verifies the signatures and applies that tag.
The previous image list from each deploy is attached to that run as `previous-images`.

## 4. Database caution
Flyway migrations are forward-only. A rollback after a migration is safe only if the old code runs on the new schema (additive changes).
If it does not: stop writes (kill switch / scale order-service to 0), then use `runbooks/dr-runbook.md` point-in-time restore to just before the migration.

## 5. After
Record the cause, the revision, and the time to restore in the incident notes. Keep the failed SHA out of `deploy` until fixed.

## Local rehearsal (kind)
```bash
kubectl -n cloudpulse set image deploy/order-service app=cloudpulse/order-service:does-not-exist
kubectl -n cloudpulse rollout status deploy/order-service --timeout=60s || kubectl -n cloudpulse rollout undo deploy/order-service
```

Measured on kind: a nonexistent image stalled the rollout while both old replicas stayed Ready (the rolling update never reduced capacity),
`rollout status` timed out as designed, and `rollout undo` restored the previous revision in about 46 s in total for the whole rehearsal (including the 45 s wait). Local only.
