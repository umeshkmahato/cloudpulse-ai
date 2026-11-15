variable "project_id" {
  type        = string
  default     = "cloudpulse-ai-staging"
  description = "GCP project ID (separate project per environment; must be created and billing-linked first)."
}

variable "environment" {
  type        = string
  default     = "staging"
  description = "Environment name (dev, staging, prod)."
}

variable "region" {
  type        = string
  default     = "asia-south2"
  description = "Primary region. asia-south2 (Delhi) availability/pricing of each service must be verified before apply."
}

variable "bq_location" {
  type        = string
  default     = "asia-south2"
  description = "BigQuery location (immutable after creation). Alternatives: US, EU, asia-south1."
}

variable "billing_account_id" {
  type        = string
  description = "Billing account ID for the budget (XXXXXX-XXXXXX-XXXXXX)."
}

variable "budget_amount" {
  type        = number
  default     = 25
  description = "Monthly budget (billing-account currency units). Alerts only; does not cap spend."
}

variable "budget_currency" {
  type        = string
  default     = "USD"
  description = "Must match the billing account currency."
}

variable "alert_email" {
  type        = string
  description = "Email for budget and monitoring alerts."
}

variable "github_repository" {
  type        = string
  default     = ""
  description = "owner/name for GitHub OIDC federation; empty disables it."
}

# ---- Cost gates: every billable-while-idle resource is OFF until explicitly enabled ----
variable "enable_gke" {
  type        = bool
  default     = false
  description = "Create the GKE Autopilot cluster (billable)."
}

variable "enable_cloud_sql" {
  type        = bool
  default     = false
  description = "Create Cloud SQL PostgreSQL (billable 24x7)."
}

variable "enable_cloud_nat" {
  type        = bool
  default     = false
  description = "Create Cloud NAT (billable)."
}

variable "enable_kms" {
  type        = bool
  default     = false
  description = "Create KMS key ring/keys (keys cannot be fully deleted)."
}

variable "sql_availability_type" {
  type        = string
  default     = "ZONAL"
  description = "ZONAL (cheaper) or REGIONAL (HA)."
}

variable "protect_resources" {
  type        = bool
  default     = true
  description = "Deletion protection on data resources (true for staging/prod)."
}

variable "dr_replica_region" {
  type        = string
  default     = null
  description = "Region for the Cloud SQL cross-region DR replica (billable; requires enable_cloud_sql). null = none."
}

variable "backup_location" {
  type        = string
  default     = null
  description = "Location for Cloud SQL backups, ideally outside the instance region (e.g. asia)."
}
