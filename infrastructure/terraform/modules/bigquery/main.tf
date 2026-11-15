locals {
  tables = var.create_default_tables ? {
    order_events = {
      time_field = "occurred_at"
      cluster    = ["order_id"]
      schema = [
        { name = "event_id", type = "STRING", mode = "REQUIRED" },
        { name = "occurred_at", type = "TIMESTAMP", mode = "REQUIRED" },
        { name = "correlation_id", type = "STRING", mode = "NULLABLE" },
        { name = "order_id", type = "STRING", mode = "REQUIRED" },
        { name = "customer_id", type = "STRING", mode = "NULLABLE" },
        { name = "total_amount", type = "NUMERIC", mode = "NULLABLE" },
        { name = "currency", type = "STRING", mode = "NULLABLE" },
        { name = "payload", type = "JSON", mode = "NULLABLE" },
        { name = "ingested_at", type = "TIMESTAMP", mode = "REQUIRED" },
      ]
    }
    inventory_events = {
      time_field = "occurred_at"
      cluster    = ["order_id"]
      schema = [
        { name = "event_id", type = "STRING", mode = "REQUIRED" },
        { name = "occurred_at", type = "TIMESTAMP", mode = "REQUIRED" },
        { name = "correlation_id", type = "STRING", mode = "NULLABLE" },
        { name = "order_id", type = "STRING", mode = "REQUIRED" },
        { name = "status", type = "STRING", mode = "NULLABLE" },
        { name = "reason", type = "STRING", mode = "NULLABLE" },
        { name = "payload", type = "JSON", mode = "NULLABLE" },
        { name = "ingested_at", type = "TIMESTAMP", mode = "REQUIRED" },
      ]
    }
    metric_samples = {
      time_field = "observed_at"
      cluster    = ["service", "metric"]
      schema = [
        { name = "event_id", type = "STRING", mode = "REQUIRED" },
        { name = "occurred_at", type = "TIMESTAMP", mode = "REQUIRED" },
        { name = "observed_at", type = "TIMESTAMP", mode = "REQUIRED" },
        { name = "service", type = "STRING", mode = "REQUIRED" },
        { name = "metric", type = "STRING", mode = "REQUIRED" },
        { name = "value", type = "FLOAT", mode = "REQUIRED" },
        { name = "unit", type = "STRING", mode = "NULLABLE" },
        { name = "synthetic", type = "BOOLEAN", mode = "REQUIRED" },
        { name = "ingested_at", type = "TIMESTAMP", mode = "REQUIRED" },
      ]
    }
  } : {}
}

resource "google_bigquery_dataset" "this" {
  project                         = var.project_id
  dataset_id                      = var.dataset_id
  location                        = var.location
  description                     = var.description
  labels                          = var.labels
  delete_contents_on_destroy      = var.delete_contents_on_destroy
  default_table_expiration_ms     = var.default_table_expiration_ms
  default_partition_expiration_ms = var.default_partition_expiration_ms

  dynamic "default_encryption_configuration" {
    for_each = var.kms_key_name == null ? [] : [1]
    content {
      kms_key_name = var.kms_key_name
    }
  }
}

resource "google_bigquery_table" "this" {
  for_each            = local.tables
  project             = var.project_id
  dataset_id          = google_bigquery_dataset.this.dataset_id
  table_id            = each.key
  deletion_protection = var.table_deletion_protection
  labels              = var.labels
  schema              = jsonencode(each.value.schema)
  clustering          = each.value.cluster

  time_partitioning {
    type  = "DAY"
    field = each.value.time_field
  }
}
