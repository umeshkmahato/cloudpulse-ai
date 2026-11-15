variable "project_id" {
  type        = string
  description = "GCP project ID."
}

variable "region" {
  type        = string
  description = "Repository location."
}

variable "repositories" {
  type = map(object({
    description       = optional(string, "")
    keep_count        = optional(number, 5)
    delete_after_days = optional(number, 30)
  }))
  description = "Docker repositories keyed by id."
}

variable "labels" {
  type        = map(string)
  default     = {}
  description = "Resource labels."
}
