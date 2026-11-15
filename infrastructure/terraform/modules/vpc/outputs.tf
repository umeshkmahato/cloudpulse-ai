output "id" {
  value       = google_compute_network.this.id
  description = "Network resource ID."
}

output "name" {
  value       = google_compute_network.this.name
  description = "Network name."
}
