variable "project_id" {
  type        = string
  description = "GCP project ID."
}

variable "region" {
  type        = string
  description = "Subnet region."
}

variable "network" {
  type        = string
  description = "Network ID or self link."
}

variable "subnets" {
  type = map(object({
    cidr                  = string
    private_google_access = optional(bool, true)
    flow_logs             = optional(bool, false)
    secondary_ranges      = optional(map(string), {})
  }))
  description = "Subnets keyed by name."
}
