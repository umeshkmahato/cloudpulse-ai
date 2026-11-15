# Creates secret CONTAINERS and access grants. Secret VALUES are added out-of-band
# (gcloud/console/CI) or via var.initial_values for generated secrets such as a DB password.
resource "google_secret_manager_secret" "this" {
  for_each  = var.secrets
  project   = var.project_id
  secret_id = each.key
  labels    = var.labels

  replication {
    user_managed {
      dynamic "replicas" {
        for_each = var.replica_locations
        content {
          location = replicas.value
        }
      }
    }
  }
}

resource "google_secret_manager_secret_version" "initial" {
  for_each    = nonsensitive(toset(keys(var.initial_values))) # keys are secret IDs, not sensitive
  secret      = google_secret_manager_secret.this[each.key].id
  secret_data = var.initial_values[each.key]
}

resource "google_secret_manager_secret_iam_member" "accessors" {
  for_each  = { for b in local.accessor_bindings : "${b.secret}|${b.member}" => b }
  project   = var.project_id
  secret_id = google_secret_manager_secret.this[each.value.secret].secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = each.value.member
}

locals {
  accessor_bindings = flatten([
    for secret, members in var.accessors : [for m in members : { secret = secret, member = m }]
  ])
}
