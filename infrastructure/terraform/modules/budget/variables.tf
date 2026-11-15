variable "project_id" {
  type        = string
  description = "Project the budget is scoped to."
}

variable "billing_account_id" {
  type        = string
  description = "Billing account ID (format XXXXXX-XXXXXX-XXXXXX)."
}

variable "amount" {
  type        = number
  description = "Monthly budget in whole currency units."
}

variable "currency_code" {
  type        = string
  description = "Must match the billing account currency (e.g. USD, INR)."
}

variable "notification_channel_ids" {
  type        = list(string)
  description = "Monitoring notification channels that receive alerts."
}
