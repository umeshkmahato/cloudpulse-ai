# BILLABLE while it exists (NAT gateway hourly + data processing). Off by default.
resource "google_compute_router" "this" {
  count   = var.enabled ? 1 : 0
  project = var.project_id
  name    = "${var.name}-router"
  region  = var.region
  network = var.network
}

resource "google_compute_router_nat" "this" {
  count                              = var.enabled ? 1 : 0
  project                            = var.project_id
  name                               = "${var.name}-nat"
  router                             = google_compute_router.this[0].name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"
  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }
}
