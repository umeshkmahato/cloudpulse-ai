variable "enabled" {
  type        = bool
  default     = false
  description = "Create the cluster (billable)."
}

variable "project_id" {
  type        = string
  description = "GCP project ID."
}

variable "name" {
  type        = string
  description = "Cluster name."
}

variable "region" {
  type        = string
  description = "Cluster location (region = regional/multi-zone control plane)."
}

variable "network" {
  type        = string
  description = "VPC ID."
}

variable "subnetwork" {
  type        = string
  description = "Subnet ID."
}

variable "pods_range_name" {
  type        = string
  description = "Secondary range name for pods."
}

variable "services_range_name" {
  type        = string
  description = "Secondary range name for services."
}

variable "master_ipv4_cidr" {
  type        = string
  default     = "172.16.0.0/28"
  description = "Control-plane peering /28."
}

variable "private_endpoint" {
  type        = bool
  default     = false
  description = "True = control plane reachable only from inside the VPC."
}

variable "authorized_networks" {
  type        = map(string)
  default     = {}
  description = "Name => CIDR allowed to reach the Kubernetes API."
}

variable "release_channel" {
  type        = string
  default     = "REGULAR"
  description = "RAPID, REGULAR or STABLE."
}

variable "deletion_protection" {
  type        = bool
  default     = true
  description = "Protect against accidental terraform destroy."
}

variable "labels" {
  type        = map(string)
  default     = {}
  description = "Resource labels."
}
