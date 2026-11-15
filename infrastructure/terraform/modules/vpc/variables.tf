variable "project_id" {
  type        = string
  description = "GCP project ID."
}

variable "name" {
  type        = string
  description = "VPC name."
}

variable "internal_ranges" {
  type        = list(string)
  description = "CIDRs allowed to talk to each other inside the VPC."
  default     = ["10.0.0.0/8"]
}
