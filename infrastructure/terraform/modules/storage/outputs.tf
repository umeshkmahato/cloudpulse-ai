output "bucket_names" {
  value       = { for k, v in google_storage_bucket.this : k => v.name }
  description = "Bucket names by key."
}
