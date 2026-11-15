variable "project_id" {
  type        = string
  description = "GCP project ID (bucket names are prefixed with it)."
}

variable "buckets" {
  type = map(object({
    location          = string
    storage_class     = optional(string, "STANDARD")
    versioning        = optional(bool, false)
    force_destroy     = optional(bool, false)
    delete_after_days = optional(number)
    kms_key_name      = optional(string)
  }))
  description = "Buckets keyed by suffix."
}

variable "labels" {
  type        = map(string)
  default     = {}
  description = "Resource labels."
}
