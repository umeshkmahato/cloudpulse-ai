output "ids" {
  value       = { for k, v in google_compute_subnetwork.this : k => v.id }
  description = "Subnet IDs by name."
}

output "names" {
  value       = { for k, v in google_compute_subnetwork.this : k => v.name }
  description = "Subnet names."
}
