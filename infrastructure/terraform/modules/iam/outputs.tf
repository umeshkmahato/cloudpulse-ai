output "service_account_emails" {
  description = "Map of account_id to email."
  value       = { for k, v in google_service_account.this : k => v.email }
}

output "github_workload_identity_provider" {
  description = "Full provider resource name for google-github-actions/auth."
  value       = try(google_iam_workload_identity_pool_provider.github[0].name, null)
}
