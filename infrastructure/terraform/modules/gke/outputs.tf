output "name" {
  value       = try(google_container_cluster.this[0].name, null)
  description = "Cluster name when enabled."
}

output "endpoint" {
  value       = try(google_container_cluster.this[0].endpoint, null)
  description = "API endpoint when enabled."
  sensitive   = true
}
