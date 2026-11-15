output "nat_name" {
  value       = try(google_compute_router_nat.this[0].name, null)
  description = "NAT name when enabled."
}
