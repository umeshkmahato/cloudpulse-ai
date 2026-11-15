variable "project_id" {
  type        = string
  description = "GCP project ID."
}

variable "service_accounts" {
  type = map(object({
    display_name = string
    description  = optional(string, "")
    roles        = list(string)
  }))
  description = "Service accounts to create, keyed by account_id, with project-level roles."
}

variable "k8s_workload_identity" {
  type = map(object({
    service_account = string
    namespace       = string
    ksa             = string
  }))
  default     = {}
  description = "Kubernetes SA -> Google SA bindings (needs the GKE workload pool to exist)."
}

variable "github" {
  type = object({
    enabled         = bool
    repository      = optional(string, "")
    service_account = optional(string, "")
  })
  default     = { enabled = false }
  description = "GitHub OIDC federation. repository is owner/name; service_account is a key of service_accounts."
}
