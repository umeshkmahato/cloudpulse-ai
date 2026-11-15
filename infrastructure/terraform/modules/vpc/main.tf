resource "google_compute_network" "this" {
  project                 = var.project_id
  name                    = var.name
  auto_create_subnetworks = false
  routing_mode            = "REGIONAL"
}

# Everything not explicitly allowed is denied (implied deny-ingress); allow only intra-VPC traffic.
resource "google_compute_firewall" "allow_internal" {
  project   = var.project_id
  name      = "${var.name}-allow-internal"
  network   = google_compute_network.this.name
  direction = "INGRESS"
  priority  = 1000
  allow {
    protocol = "tcp"
  }
  allow {
    protocol = "udp"
  }
  allow {
    protocol = "icmp"
  }
  source_ranges = var.internal_ranges
}
