output "repository_urls" {
  value       = { for k, v in google_artifact_registry_repository.this : k => "${v.location}-docker.pkg.dev/${v.project}/${k}" }
  description = "Image path prefix per repository."
}
