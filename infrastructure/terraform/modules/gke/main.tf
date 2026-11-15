# BILLABLE: GKE cluster management fee + pod resources (Autopilot) accrue while it exists. Off by default.
# Autopilot: Google manages nodes; Workload Identity, shielded nodes and network policy enforcement come built in.
resource "google_container_cluster" "this" {
  count    = var.enabled ? 1 : 0
  project  = var.project_id
  name     = var.name
  location = var.region

  enable_autopilot    = true
  network             = var.network
  subnetwork          = var.subnetwork
  deletion_protection = var.deletion_protection
  resource_labels     = var.labels

  release_channel {
    channel = var.release_channel
  }

  ip_allocation_policy {
    cluster_secondary_range_name  = var.pods_range_name
    services_secondary_range_name = var.services_range_name
  }

  private_cluster_config {
    enable_private_nodes    = true
    enable_private_endpoint = var.private_endpoint
    master_ipv4_cidr_block  = var.master_ipv4_cidr
  }

  dynamic "master_authorized_networks_config" {
    for_each = length(var.authorized_networks) > 0 ? [1] : []
    content {
      dynamic "cidr_blocks" {
        for_each = var.authorized_networks
        content {
          display_name = cidr_blocks.key
          cidr_block   = cidr_blocks.value
        }
      }
    }
  }

  logging_config {
    enable_components = ["SYSTEM_COMPONENTS", "WORKLOADS"]
  }

  monitoring_config {
    enable_components = ["SYSTEM_COMPONENTS"]
    managed_prometheus {
      enabled = true
    }
  }
}
