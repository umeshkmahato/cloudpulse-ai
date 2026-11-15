variable "project_id" {
  type        = string
  description = "GCP project ID."
}

variable "secrets" {
  type        = set(string)
  description = "Secret IDs to create."
}

variable "replica_locations" {
  type        = list(string)
  description = "Regions that hold the secret (user-managed replication)."
}

variable "accessors" {
  type        = map(list(string))
  default     = {}
  description = "Secret ID => IAM members allowed to read it."
}

variable "initial_values" {
  type        = map(string)
  default     = {}
  sensitive   = true
  description = "Secret ID => initial value (lands in Terraform state; use only for generated secrets)."
}

variable "labels" {
  type        = map(string)
  default     = {}
  description = "Resource labels."
}
