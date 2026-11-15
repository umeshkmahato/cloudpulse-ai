variable "enabled" {
  type        = bool
  default     = false
  description = "Create the router and NAT (billable)."
}

variable "project_id" {
  type        = string
  description = "GCP project ID."
}

variable "name" {
  type        = string
  description = "Name prefix."
}

variable "region" {
  type        = string
  description = "Region."
}

variable "network" {
  type        = string
  description = "Network ID."
}
