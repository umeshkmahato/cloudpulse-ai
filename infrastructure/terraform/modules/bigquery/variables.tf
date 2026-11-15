variable "project_id" {
  type        = string
  description = "GCP project ID."
}

variable "dataset_id" {
  type        = string
  description = "Dataset ID."
}

variable "location" {
  type        = string
  description = "Dataset location (immutable; e.g. asia-south2 or a multi-region)."
}

variable "description" {
  type        = string
  default     = ""
  description = "Dataset description."
}

variable "create_default_tables" {
  type        = bool
  default     = true
  description = "Create the CloudPulse analytics tables (partitioned and clustered)."
}

variable "delete_contents_on_destroy" {
  type        = bool
  default     = false
  description = "Allow destroy to delete tables with data (dev only)."
}

variable "table_deletion_protection" {
  type        = bool
  default     = true
  description = "Protect tables from destroy."
}

variable "default_table_expiration_ms" {
  type        = number
  default     = null
  description = "Optional table TTL (cost control for dev)."
}

variable "default_partition_expiration_ms" {
  type        = number
  default     = null
  description = "Optional partition TTL, e.g. 30 days for dev."
}

variable "kms_key_name" {
  type        = string
  default     = null
  description = "Optional CMEK key."
}

variable "labels" {
  type        = map(string)
  default     = {}
  description = "Resource labels."
}
