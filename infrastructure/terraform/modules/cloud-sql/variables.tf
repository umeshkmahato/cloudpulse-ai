variable "enabled" {
  type        = bool
  default     = false
  description = "Create the instance (billable)."
}

variable "project_id" {
  type        = string
  description = "GCP project ID."
}

variable "name" {
  type        = string
  description = "Instance name."
}

variable "region" {
  type        = string
  description = "Instance region."
}

variable "network_id" {
  type        = string
  description = "VPC ID for private service access."
}

variable "database_version" {
  type        = string
  default     = "POSTGRES_16"
  description = "Engine version."
}

variable "tier" {
  type        = string
  default     = "db-custom-1-3840"
  description = "Machine tier."
}

variable "availability_type" {
  type        = string
  default     = "ZONAL"
  description = "ZONAL or REGIONAL (HA, roughly double the compute cost)."
}

variable "disk_size_gb" {
  type        = number
  default     = 10
  description = "Initial disk size."
}

variable "retained_backups" {
  type        = number
  default     = 7
  description = "Automated backups to keep."
}

variable "databases" {
  type        = list(string)
  default     = ["cloudpulse"]
  description = "Databases to create."
}

variable "app_user" {
  type        = string
  default     = "cloudpulse"
  description = "Application DB user."
}

variable "kms_key_name" {
  type        = string
  default     = null
  description = "Optional CMEK key (must be in the instance region)."
}

variable "deletion_protection" {
  type        = bool
  default     = true
  description = "Protect against destroy."
}

variable "labels" {
  type        = map(string)
  default     = {}
  description = "Resource labels."
}

variable "backup_location" {
  type        = string
  default     = null
  description = "Where automated backups are stored (e.g. a multi-region like asia). Different from the instance region = survives a regional outage."
}

variable "dr_replica_region" {
  type        = string
  default     = null
  description = "Create a cross-region read replica here for disaster recovery (billable). null = none."
}
