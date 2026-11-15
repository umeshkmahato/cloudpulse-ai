variable "project_id" {
  type        = string
  description = "GCP project ID."
}

variable "audit_bucket_name" {
  type        = string
  description = "Existing GCS bucket that receives exported audit logs."
}

variable "default_retention_days" {
  type        = number
  default     = 30
  description = "Retention for the _Default log bucket."
}
