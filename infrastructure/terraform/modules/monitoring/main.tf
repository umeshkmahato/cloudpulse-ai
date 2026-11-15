resource "google_monitoring_notification_channel" "email" {
  project      = var.project_id
  display_name = "CloudPulse on-call email"
  type         = "email"
  labels = {
    email_address = var.alert_email
  }
}

# Any message in a dead-letter subscription means a consumer failed 5 times.
resource "google_monitoring_alert_policy" "dlq_backlog" {
  project      = var.project_id
  display_name = "CloudPulse: dead-letter backlog"
  combiner     = "OR"
  conditions {
    display_name = "DLQ subscription has undelivered messages"
    condition_threshold {
      filter          = "resource.type = \"pubsub_subscription\" AND metric.type = \"pubsub.googleapis.com/subscription/num_undelivered_messages\" AND resource.label.subscription_id = monitoring.regex.full_match(\".*-dlq-sub\")"
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = "300s"
      aggregations {
        alignment_period   = "60s"
        per_series_aligner = "ALIGN_MAX"
      }
    }
  }
  notification_channels = [google_monitoring_notification_channel.email.id]
  user_labels           = var.labels
  documentation {
    content   = "Inspect the DLQ, fix the root cause, then replay with scripts/dlq-replay.sh (see architecture/messaging-design.md)."
    mime_type = "text/markdown"
  }
}

resource "google_monitoring_alert_policy" "consumer_lag" {
  project      = var.project_id
  display_name = "CloudPulse: consumer lag (oldest unacked > 5 min)"
  combiner     = "OR"
  conditions {
    display_name = "Oldest unacked message age"
    condition_threshold {
      filter          = "resource.type = \"pubsub_subscription\" AND metric.type = \"pubsub.googleapis.com/subscription/oldest_unacked_message_age\" AND resource.label.subscription_id != monitoring.regex.full_match(\".*-dlq-sub\")"
      comparison      = "COMPARISON_GT"
      threshold_value = 300
      duration        = "300s"
      aggregations {
        alignment_period   = "60s"
        per_series_aligner = "ALIGN_MAX"
      }
    }
  }
  notification_channels = [google_monitoring_notification_channel.email.id]
  user_labels           = var.labels
}

resource "google_monitoring_alert_policy" "pod_restarts" {
  count        = var.enable_gke_alerts ? 1 : 0
  project      = var.project_id
  display_name = "CloudPulse: container restarts"
  combiner     = "OR"
  conditions {
    display_name = "Restart count increasing"
    condition_threshold {
      filter          = "resource.type = \"k8s_container\" AND metric.type = \"kubernetes.io/container/restart_count\""
      comparison      = "COMPARISON_GT"
      threshold_value = 3
      duration        = "600s"
      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_DELTA"
      }
    }
  }
  notification_channels = [google_monitoring_notification_channel.email.id]
  user_labels           = var.labels
}

resource "google_monitoring_alert_policy" "sql_cpu" {
  count        = var.enable_sql_alerts ? 1 : 0
  project      = var.project_id
  display_name = "CloudPulse: Cloud SQL CPU > 80%"
  combiner     = "OR"
  conditions {
    display_name = "CPU utilization"
    condition_threshold {
      filter          = "resource.type = \"cloudsql_database\" AND metric.type = \"cloudsql.googleapis.com/database/cpu/utilization\""
      comparison      = "COMPARISON_GT"
      threshold_value = 0.8
      duration        = "600s"
      aggregations {
        alignment_period   = "60s"
        per_series_aligner = "ALIGN_MEAN"
      }
    }
  }
  notification_channels = [google_monitoring_notification_channel.email.id]
  user_labels           = var.labels
}
