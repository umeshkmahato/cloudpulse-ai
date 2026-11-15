output "sink_writer_identity" {
  value       = google_logging_project_sink.audit_to_gcs.writer_identity
  description = "Identity the sink writes as."
}
