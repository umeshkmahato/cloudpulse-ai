resource "google_storage_bucket" "this" {
  for_each                    = var.buckets
  project                     = var.project_id
  name                        = "${var.project_id}-${each.key}"
  location                    = each.value.location
  storage_class               = each.value.storage_class
  force_destroy               = each.value.force_destroy
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  labels                      = var.labels

  versioning {
    enabled = each.value.versioning
  }

  dynamic "encryption" {
    for_each = each.value.kms_key_name == null ? [] : [1]
    content {
      default_kms_key_name = each.value.kms_key_name
    }
  }

  dynamic "lifecycle_rule" {
    for_each = each.value.delete_after_days == null ? [] : [1]
    content {
      condition {
        age = each.value.delete_after_days
      }
      action {
        type = "Delete"
      }
    }
  }

  # Versioned buckets: expire old noncurrent versions so storage cost stays bounded.
  dynamic "lifecycle_rule" {
    for_each = each.value.versioning ? [1] : []
    content {
      condition {
        days_since_noncurrent_time = 30
      }
      action {
        type = "Delete"
      }
    }
  }
}
