-- The HPA owns replica counts, so the controller raises the HPA's minReplicas ("capacity floor") instead of fighting it
-- with deployments/scale, and restores the original floor once the incident recovers.
ALTER TABLE remediations ADD COLUMN floor_before INT;
ALTER TABLE remediations ADD COLUMN floor_restored_at TIMESTAMPTZ;
