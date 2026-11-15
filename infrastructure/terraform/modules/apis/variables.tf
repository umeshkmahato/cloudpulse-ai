variable "project_id" {
  type        = string
  description = "GCP project ID."
}

variable "apis" {
  type        = list(string)
  description = "API service names to enable, e.g. pubsub.googleapis.com."
}
