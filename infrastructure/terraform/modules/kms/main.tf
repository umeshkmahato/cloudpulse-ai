# CMEK building block. Keys and key rings cannot be deleted (only versions destroyed): keep off until needed.
resource "google_kms_key_ring" "this" {
  count    = var.enabled ? 1 : 0
  project  = var.project_id
  name     = var.name
  location = var.region
}

resource "google_kms_crypto_key" "keys" {
  for_each        = var.enabled ? toset(var.keys) : toset([])
  name            = each.value
  key_ring        = google_kms_key_ring.this[0].id
  rotation_period = "7776000s" # 90 days
  labels          = var.labels

  lifecycle {
    prevent_destroy = false # set true in prod
  }
}
