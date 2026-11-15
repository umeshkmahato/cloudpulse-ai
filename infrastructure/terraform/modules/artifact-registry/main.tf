resource "google_artifact_registry_repository" "this" {
  for_each      = var.repositories
  project       = var.project_id
  location      = var.region
  repository_id = each.key
  description   = each.value.description
  format        = "DOCKER"
  labels        = var.labels

  # Keeps storage (billable beyond the free allowance) bounded.
  cleanup_policy_dry_run = false
  cleanup_policies {
    id     = "keep-recent"
    action = "KEEP"
    most_recent_versions {
      keep_count = each.value.keep_count
    }
  }
  cleanup_policies {
    id     = "delete-old"
    action = "DELETE"
    condition {
      older_than = "${each.value.delete_after_days * 86400}s"
    }
  }
}
