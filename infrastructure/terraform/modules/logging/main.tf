# Audit logs are exported to a bucket for long retention; noisy health-check logs are excluded
# from the default bucket to control Cloud Logging ingestion cost.
resource "google_logging_project_sink" "audit_to_gcs" {
  project                = var.project_id
  name                   = "audit-to-gcs"
  destination            = "storage.googleapis.com/${var.audit_bucket_name}"
  filter                 = "logName:\"cloudaudit.googleapis.com\""
  unique_writer_identity = true
}

resource "google_storage_bucket_iam_member" "sink_writer" {
  bucket = var.audit_bucket_name
  role   = "roles/storage.objectCreator"
  member = google_logging_project_sink.audit_to_gcs.writer_identity
}

resource "google_logging_project_exclusion" "health_checks" {
  project     = var.project_id
  name        = "exclude-health-checks"
  description = "Drop successful kube-probe / health check request logs."
  filter      = "httpRequest.requestUrl:\"/actuator/health\" AND httpRequest.status=200"
}

resource "google_logging_project_bucket_config" "default" {
  project        = var.project_id
  location       = "global"
  bucket_id      = "_Default"
  retention_days = var.default_retention_days
}
