locals {
  name_prefix = "cloudpulse-${var.environment}"

  labels = {
    app         = "cloudpulse-ai"
    environment = var.environment
    managed_by  = "terraform"
    cost_center = "poc"
  }

  # Always-needed, no idle cost.
  base_apis = [
    "serviceusage.googleapis.com",
    "cloudresourcemanager.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "pubsub.googleapis.com",
    "bigquery.googleapis.com",
    "storage.googleapis.com",
    "artifactregistry.googleapis.com",
    "secretmanager.googleapis.com",
    "monitoring.googleapis.com",
    "logging.googleapis.com",
    "billingbudgets.googleapis.com",
    "cloudbilling.googleapis.com",
  ]
  # Enabled only when the matching gate is on.
  gated_apis = concat(
    var.enable_gke ? ["container.googleapis.com", "compute.googleapis.com"] : [],
    var.enable_cloud_nat || var.enable_cloud_sql ? ["compute.googleapis.com"] : [],
    var.enable_cloud_sql ? ["sqladmin.googleapis.com", "servicenetworking.googleapis.com"] : [],
    var.enable_kms ? ["cloudkms.googleapis.com"] : [],
  )
  all_apis = distinct(concat(local.base_apis, local.gated_apis))

  topics = [
    "orders-created",
    "inventory-updated",
    "payment-events",
    "application-metrics",
    "infrastructure-alerts",
    "security-events",
    "remediation-events",
  ]

  subscriptions = {
    "orders-created-analytics-sub"      = { topic = "orders-created" }
    "application-metrics-analytics-sub" = { topic = "application-metrics" }
    "orders-created-inventory-sub"      = { topic = "orders-created" }
    "inventory-updated-analytics-sub"   = { topic = "inventory-updated" }
    "inventory-updated-order-sub"       = { topic = "inventory-updated" }
    "payment-events-ops-sub"            = { topic = "payment-events" }
    "application-metrics-ops-sub"       = { topic = "application-metrics" }
    "infrastructure-alerts-ops-sub"     = { topic = "infrastructure-alerts" }
    "security-events-ops-sub"           = { topic = "security-events" }
    "remediation-events-ops-sub"        = { topic = "remediation-events" }
  }

  # One identity per workload; roles are scoped as tightly as project-level IAM allows
  # (topic/subscription/secret-level grants below narrow them further).
  service_accounts = {
    "sa-order-service" = {
      display_name = "Order Service"
      roles        = ["roles/cloudsql.client", "roles/monitoring.metricWriter", "roles/logging.logWriter", "roles/cloudtrace.agent"]
    }
    "sa-inventory-service" = {
      display_name = "Inventory Service"
      roles        = ["roles/cloudsql.client", "roles/monitoring.metricWriter", "roles/logging.logWriter", "roles/cloudtrace.agent"]
    }
    "sa-operations-service" = {
      display_name = "Operations Intelligence Service"
      roles        = ["roles/cloudsql.client", "roles/bigquery.dataViewer", "roles/bigquery.jobUser", "roles/monitoring.viewer", "roles/logging.logWriter"]
    }
    "sa-analytics-sink" = {
      display_name = "Analytics sink (Pub/Sub to BigQuery)"
      roles        = ["roles/bigquery.dataEditor", "roles/bigquery.jobUser", "roles/logging.logWriter"]
    }
    "sa-remediation-controller" = {
      display_name = "Remediation controller (restricted actions only)"
      roles        = ["roles/logging.logWriter"]
    }
    "sa-github-deployer" = {
      display_name = "GitHub Actions deployer (via WIF, no keys)"
      roles        = ["roles/artifactregistry.writer"]
    }
  }

  sa = module.iam.service_account_emails
}

module "apis" {
  source     = "../../modules/apis"
  project_id = var.project_id
  apis       = local.all_apis
}

module "iam" {
  source           = "../../modules/iam"
  project_id       = var.project_id
  service_accounts = local.service_accounts
  github = {
    enabled         = var.github_repository != ""
    repository      = var.github_repository
    service_account = "sa-github-deployer"
  }
  depends_on = [module.apis]
}

module "vpc" {
  source     = "../../modules/vpc"
  project_id = var.project_id
  name       = "${local.name_prefix}-vpc"
  depends_on = [module.apis]
}

module "subnets" {
  source     = "../../modules/subnets"
  project_id = var.project_id
  region     = var.region
  network    = module.vpc.id
  subnets = {
    "${local.name_prefix}-gke" = {
      cidr      = "10.10.0.0/20"
      flow_logs = false
      secondary_ranges = {
        pods     = "10.20.0.0/16"
        services = "10.30.0.0/20"
      }
    }
  }
}

module "cloud_nat" {
  source     = "../../modules/cloud-nat"
  enabled    = var.enable_cloud_nat
  project_id = var.project_id
  name       = local.name_prefix
  region     = var.region
  network    = module.vpc.id
}

module "kms" {
  source     = "../../modules/kms"
  enabled    = var.enable_kms
  project_id = var.project_id
  name       = "${local.name_prefix}-keyring"
  region     = var.region
  keys       = ["data"]
  labels     = local.labels
  depends_on = [module.apis]
}

module "gke" {
  source              = "../../modules/gke"
  enabled             = var.enable_gke
  project_id          = var.project_id
  name                = "${local.name_prefix}-gke"
  region              = var.region
  network             = module.vpc.id
  subnetwork          = module.subnets.ids["${local.name_prefix}-gke"]
  pods_range_name     = "pods"
  services_range_name = "services"
  deletion_protection = var.protect_resources
  labels              = local.labels
  depends_on          = [module.apis]
}

module "artifact_registry" {
  source       = "../../modules/artifact-registry"
  project_id   = var.project_id
  region       = var.region
  repositories = { "cloudpulse" = { description = "CloudPulse service images" } }
  labels       = local.labels
  depends_on   = [module.apis]
}

module "pubsub" {
  source        = "../../modules/pubsub"
  project_id    = var.project_id
  topics        = local.topics
  subscriptions = local.subscriptions
  labels        = local.labels

  publishers = {
    "serviceAccount:${local.sa["sa-order-service"]}"      = ["orders-created"]
    "serviceAccount:${local.sa["sa-inventory-service"]}"  = ["inventory-updated"]
    "serviceAccount:${local.sa["sa-operations-service"]}" = ["application-metrics", "infrastructure-alerts", "remediation-events"]
  }
  subscribers = {
    "serviceAccount:${local.sa["sa-inventory-service"]}"  = ["orders-created-inventory-sub"]
    "serviceAccount:${local.sa["sa-order-service"]}"      = ["inventory-updated-order-sub"]
    "serviceAccount:${local.sa["sa-analytics-sink"]}"     = ["inventory-updated-analytics-sub", "orders-created-analytics-sub", "application-metrics-analytics-sub"]
    "serviceAccount:${local.sa["sa-operations-service"]}" = ["application-metrics-ops-sub", "infrastructure-alerts-ops-sub", "security-events-ops-sub", "payment-events-ops-sub", "remediation-events-ops-sub"]
  }
  depends_on = [module.apis]
}

module "bigquery_analytics" {
  source                          = "../../modules/bigquery"
  project_id                      = var.project_id
  dataset_id                      = "cloudpulse_analytics"
  location                        = var.bq_location
  description                     = "Order, inventory and metric events (Pub/Sub sink)."
  labels                          = local.labels
  delete_contents_on_destroy      = !var.protect_resources
  table_deletion_protection       = var.protect_resources
  default_partition_expiration_ms = var.protect_resources ? null : 2592000000 # 30 days in dev
  depends_on                      = [module.apis]
}

# Target for the Cloud Billing export (enable the export in the console; data there is ACTUAL billing data).
module "bigquery_finops" {
  source                = "../../modules/bigquery"
  project_id            = var.project_id
  dataset_id            = "cloudpulse_finops"
  location              = var.bq_location
  description           = "Billing export destination (actual cost data; estimates live elsewhere)."
  create_default_tables = false
  labels                = local.labels
  depends_on            = [module.apis]
}

module "storage" {
  source     = "../../modules/storage"
  project_id = var.project_id
  labels     = local.labels
  buckets = {
    "audit-logs"        = { location = var.region, delete_after_days = 365 }
    "dr-backups"        = { location = var.region, versioning = true, delete_after_days = 90 }
    "analytics-staging" = { location = var.region, delete_after_days = 14, force_destroy = !var.protect_resources }
  }
  depends_on = [module.apis]
}

module "cloud_sql" {
  source              = "../../modules/cloud-sql"
  enabled             = var.enable_cloud_sql
  project_id          = var.project_id
  name                = "${local.name_prefix}-pg"
  region              = var.region
  network_id          = module.vpc.id
  availability_type   = var.sql_availability_type
  dr_replica_region   = var.dr_replica_region
  backup_location     = var.backup_location
  deletion_protection = var.protect_resources
  kms_key_name        = null
  labels              = local.labels
  depends_on          = [module.apis]
}

module "secrets" {
  source            = "../../modules/secret-manager"
  project_id        = var.project_id
  secrets           = toset(["db-password", "api-signing-key"])
  replica_locations = [var.region]
  labels            = local.labels
  accessors = {
    "db-password" = [
      "serviceAccount:${local.sa["sa-order-service"]}",
      "serviceAccount:${local.sa["sa-inventory-service"]}",
      "serviceAccount:${local.sa["sa-operations-service"]}",
    ]
  }
  depends_on = [module.apis]
}

module "monitoring" {
  source            = "../../modules/monitoring"
  project_id        = var.project_id
  alert_email       = var.alert_email
  enable_gke_alerts = var.enable_gke
  enable_sql_alerts = var.enable_cloud_sql
  labels            = local.labels
  depends_on        = [module.apis]
}

module "logging" {
  source                 = "../../modules/logging"
  project_id             = var.project_id
  audit_bucket_name      = module.storage.bucket_names["audit-logs"]
  default_retention_days = 30
  depends_on             = [module.apis]
}

module "budget" {
  source                   = "../../modules/budget"
  project_id               = var.project_id
  billing_account_id       = var.billing_account_id
  amount                   = var.budget_amount
  currency_code            = var.budget_currency
  notification_channel_ids = [module.monitoring.notification_channel_id]
  depends_on               = [module.apis]
}
