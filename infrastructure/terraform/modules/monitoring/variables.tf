variable "project_id" {
  type        = string
  description = "GCP project ID."
}

variable "alert_email" {
  type        = string
  description = "Address that receives alerts."
}

variable "enable_gke_alerts" {
  type        = bool
  default     = false
  description = "Create GKE alert policies."
}

variable "enable_sql_alerts" {
  type        = bool
  default     = false
  description = "Create Cloud SQL alert policies."
}

variable "labels" {
  type        = map(string)
  default     = {}
  description = "Resource labels."
}
