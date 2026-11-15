# BILLABLE: an instance accrues cost 24x7 while it exists (even idle). Off by default.
# Private IP only (no public endpoint), TLS enforced, backups + PITR, deletion protection.
resource "google_compute_global_address" "private_range" {
  count         = var.enabled ? 1 : 0
  project       = var.project_id
  name          = "${var.name}-psa-range"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 20
  network       = var.network_id
}

resource "google_service_networking_connection" "psa" {
  count                   = var.enabled ? 1 : 0
  network                 = var.network_id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_range[0].name]
}

resource "google_sql_database_instance" "this" {
  count               = var.enabled ? 1 : 0
  project             = var.project_id
  name                = var.name
  region              = var.region
  database_version    = var.database_version
  deletion_protection = var.deletion_protection
  encryption_key_name = var.kms_key_name

  settings {
    tier              = var.tier
    availability_type = var.availability_type # REGIONAL = HA across zones with automatic failover
    disk_size         = var.disk_size_gb
    disk_autoresize   = true
    user_labels       = var.labels

    ip_configuration {
      ipv4_enabled    = false
      private_network = var.network_id
      ssl_mode        = "ENCRYPTED_ONLY"
    }

    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
      start_time                     = "21:00" # UTC
      transaction_log_retention_days = 7
      location                       = var.backup_location # keep backups in a different location than the instance (null = same region)
      backup_retention_settings {
        retained_backups = var.retained_backups
      }
    }

    maintenance_window {
      day  = 7
      hour = 21
    }

    insights_config {
      query_insights_enabled = true
    }

    database_flags {
      name  = "log_connections"
      value = "on"
    }
    database_flags {
      name  = "log_disconnections"
      value = "on"
    }
  }

  depends_on = [google_service_networking_connection.psa]
}

resource "google_sql_database" "this" {
  for_each = var.enabled ? toset(var.databases) : toset([])
  project  = var.project_id
  instance = google_sql_database_instance.this[0].name
  name     = each.value
}

# The generated password is in Terraform state: keep state in an encrypted, access-controlled bucket,
# push it to Secret Manager, and prefer IAM database authentication for services.
resource "random_password" "app" {
  count   = var.enabled ? 1 : 0
  length  = 32
  special = false
}

resource "google_sql_user" "app" {
  count    = var.enabled ? 1 : 0
  project  = var.project_id
  instance = google_sql_database_instance.this[0].name
  name     = var.app_user
  password = random_password.app[0].result
}

# Cross-region read replica for regional disaster recovery: promote it if the primary region is lost (RPO = replication lag).
# BILLABLE while it exists (a second instance plus cross-region traffic): gated by dr_replica_region, off by default.
resource "google_sql_database_instance" "dr_replica" {
  count                = var.enabled && var.dr_replica_region != null ? 1 : 0
  project              = var.project_id
  name                 = "${var.name}-dr"
  region               = var.dr_replica_region
  database_version     = var.database_version
  master_instance_name = google_sql_database_instance.this[0].name
  deletion_protection  = var.deletion_protection

  settings {
    tier              = var.tier
    availability_type = "ZONAL"
    disk_autoresize   = true
    user_labels       = merge(var.labels, { role = "dr-replica" })

    ip_configuration {
      ipv4_enabled    = false
      private_network = var.network_id
      ssl_mode        = "ENCRYPTED_ONLY"
    }
  }
}
