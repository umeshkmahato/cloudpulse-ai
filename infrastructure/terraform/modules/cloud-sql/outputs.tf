output "connection_name" {
  value       = try(google_sql_database_instance.this[0].connection_name, null)
  description = "Instance connection name."
}

output "private_ip" {
  value       = try(google_sql_database_instance.this[0].private_ip_address, null)
  description = "Private IP."
}

output "app_password" {
  value       = try(random_password.app[0].result, null)
  description = "Generated app password (store in Secret Manager; never print)."
  sensitive   = true
}

output "dr_replica_connection_name" {
  value       = try(google_sql_database_instance.dr_replica[0].connection_name, null)
  description = "Connection name of the DR replica when enabled."
}
