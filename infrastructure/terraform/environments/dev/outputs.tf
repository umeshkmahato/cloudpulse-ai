output "enabled_apis" {
  value       = module.apis.enabled_apis
  description = "APIs enabled by Terraform."
}

output "service_account_emails" {
  value       = module.iam.service_account_emails
  description = "Per-workload service accounts."
}

output "artifact_registry" {
  value       = module.artifact_registry.repository_urls
  description = "Image repository paths."
}

output "pubsub_topics" {
  value       = module.pubsub.topics
  description = "Topic IDs."
}

output "github_workload_identity_provider" {
  value       = module.iam.github_workload_identity_provider
  description = "Use as workload_identity_provider in GitHub Actions."
}

output "gke_cluster" {
  value       = module.gke.name
  description = "Cluster name (null while enable_gke=false)."
}

output "cloud_sql_connection_name" {
  value       = module.cloud_sql.connection_name
  description = "Cloud SQL connection name (null while disabled)."
}
