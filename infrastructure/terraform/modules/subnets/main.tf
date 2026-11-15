resource "google_compute_subnetwork" "this" {
  for_each                 = var.subnets
  project                  = var.project_id
  name                     = each.key
  region                   = var.region
  network                  = var.network
  ip_cidr_range            = each.value.cidr
  private_ip_google_access = each.value.private_google_access

  dynamic "secondary_ip_range" {
    for_each = each.value.secondary_ranges
    content {
      range_name    = secondary_ip_range.key
      ip_cidr_range = secondary_ip_range.value
    }
  }

  dynamic "log_config" {
    for_each = each.value.flow_logs ? [1] : []
    content {
      aggregation_interval = "INTERVAL_10_MIN"
      flow_sampling        = 0.1 # sampled to limit logging cost
      metadata             = "INCLUDE_ALL_METADATA"
    }
  }
}
