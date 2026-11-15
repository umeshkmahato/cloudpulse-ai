# Budgets ALERT, they do not stop spending. Pair with the guards in scripts/tf-apply.sh and Phase 12 remediation.
data "google_project" "this" {
  project_id = var.project_id
}

resource "google_billing_budget" "this" {
  billing_account = var.billing_account_id
  display_name    = "${var.project_id}-monthly"

  budget_filter {
    projects               = ["projects/${data.google_project.this.number}"]
    credit_types_treatment = "INCLUDE_ALL_CREDITS"
  }

  amount {
    specified_amount {
      currency_code = var.currency_code
      units         = tostring(var.amount)
    }
  }

  threshold_rules {
    threshold_percent = 0.5
  }
  threshold_rules {
    threshold_percent = 0.9
  }
  threshold_rules {
    threshold_percent = 1.0
  }
  threshold_rules {
    threshold_percent = 1.0
    spend_basis       = "FORECASTED_SPEND"
  }

  all_updates_rule {
    monitoring_notification_channels = var.notification_channel_ids
    disable_default_iam_recipients   = false
  }
}
