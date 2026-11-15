output "dataset_id" {
  value       = google_bigquery_dataset.this.dataset_id
  description = "Dataset ID."
}

output "table_ids" {
  value       = { for k, v in google_bigquery_table.this : k => v.table_id }
  description = "Table IDs."
}
