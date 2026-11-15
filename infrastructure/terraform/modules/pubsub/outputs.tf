output "topics" {
  value       = { for k, v in google_pubsub_topic.main : k => v.id }
  description = "Topic IDs."
}

output "dlq_subscriptions" {
  value       = { for k, v in google_pubsub_subscription.dlq : k => v.id }
  description = "DLQ subscription IDs (alert on these)."
}

output "consumer_subscriptions" {
  value       = { for k, v in google_pubsub_subscription.consumer : k => v.id }
  description = "Consumer subscription IDs."
}
