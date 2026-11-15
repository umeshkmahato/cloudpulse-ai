variable "project_id" {
  type        = string
  description = "GCP project ID."
}

variable "topics" {
  type        = list(string)
  description = "Topic names; each also gets <name>-dlq and <name>-dlq-sub."
}

variable "subscriptions" {
  type = map(object({
    topic                 = string
    ack_deadline_seconds  = optional(number, 30)
    max_delivery_attempts = optional(number, 5)
    min_backoff           = optional(string, "5s")
    max_backoff           = optional(string, "300s")
  }))
  description = "Consumer subscriptions keyed by name."
}

variable "publishers" {
  type        = map(list(string))
  default     = {}
  description = "IAM member (e.g. serviceAccount:x@...) => topics it can publish to."
}

variable "subscribers" {
  type        = map(list(string))
  default     = {}
  description = "IAM member => subscriptions it can consume."
}

variable "topic_retention" {
  type        = string
  default     = "86400s"
  description = "Topic message retention (enables replay window; storage is billable)."
}

variable "allowed_persistence_regions" {
  type        = list(string)
  default     = []
  description = "Restrict message storage to these regions (data residency)."
}

variable "labels" {
  type        = map(string)
  default     = {}
  description = "Resource labels."
}
