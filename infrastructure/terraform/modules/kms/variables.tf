variable "enabled" {
  type        = bool
  default     = false
  description = "Create the key ring and keys."
}

variable "project_id" {
  type        = string
  description = "GCP project ID."
}

variable "name" {
  type        = string
  description = "Key ring name."
}

variable "region" {
  type        = string
  description = "Key ring location (must match the resources it protects)."
}

variable "keys" {
  type        = list(string)
  default     = ["data"]
  description = "Crypto key names."
}

variable "labels" {
  type        = map(string)
  default     = {}
  description = "Resource labels."
}
