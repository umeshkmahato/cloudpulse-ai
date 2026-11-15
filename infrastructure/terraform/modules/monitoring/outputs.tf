output "notification_channel_id" {
  value       = google_monitoring_notification_channel.email.id
  description = "Email channel ID (reused by the budget)."
}
