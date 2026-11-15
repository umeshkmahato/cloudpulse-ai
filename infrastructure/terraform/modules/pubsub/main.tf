# Mirrors scripts/pubsub-init.sh: every topic gets a dead-letter topic + pull subscription;
# every consumer subscription has retry backoff and a dead-letter policy.
data "google_project" "this" {
  project_id = var.project_id
}

locals {
  pubsub_agent = "serviceAccount:service-${data.google_project.this.number}@gcp-sa-pubsub.iam.gserviceaccount.com"
}

resource "google_pubsub_topic" "main" {
  for_each                   = toset(var.topics)
  project                    = var.project_id
  name                       = each.value
  labels                     = var.labels
  message_retention_duration = var.topic_retention

  dynamic "message_storage_policy" {
    for_each = length(var.allowed_persistence_regions) > 0 ? [1] : []
    content {
      allowed_persistence_regions = var.allowed_persistence_regions
    }
  }
}

resource "google_pubsub_topic" "dlq" {
  for_each = toset(var.topics)
  project  = var.project_id
  name     = "${each.value}-dlq"
  labels   = var.labels
}

resource "google_pubsub_subscription" "dlq" {
  for_each             = toset(var.topics)
  project              = var.project_id
  name                 = "${each.value}-dlq-sub"
  topic                = google_pubsub_topic.dlq[each.value].id
  ack_deadline_seconds = 30
  labels               = var.labels
  expiration_policy {
    ttl = "" # never expire
  }
}

resource "google_pubsub_subscription" "consumer" {
  for_each             = var.subscriptions
  project              = var.project_id
  name                 = each.key
  topic                = google_pubsub_topic.main[each.value.topic].id
  ack_deadline_seconds = each.value.ack_deadline_seconds
  labels               = var.labels
  expiration_policy {
    ttl = ""
  }
  retry_policy {
    minimum_backoff = each.value.min_backoff
    maximum_backoff = each.value.max_backoff
  }
  dead_letter_policy {
    dead_letter_topic     = google_pubsub_topic.dlq[each.value.topic].id
    max_delivery_attempts = each.value.max_delivery_attempts
  }
}

# Pub/Sub's service agent must publish to the DLQ topic and ack from the source subscription.
resource "google_pubsub_topic_iam_member" "dlq_publisher" {
  for_each = toset(var.topics)
  project  = var.project_id
  topic    = google_pubsub_topic.dlq[each.value].name
  role     = "roles/pubsub.publisher"
  member   = local.pubsub_agent
}

resource "google_pubsub_subscription_iam_member" "dlq_subscriber" {
  for_each     = var.subscriptions
  project      = var.project_id
  subscription = google_pubsub_subscription.consumer[each.key].name
  role         = "roles/pubsub.subscriber"
  member       = local.pubsub_agent
}

# Per-identity access (least privilege): service => topics it may publish to / subscriptions it may read.
resource "google_pubsub_topic_iam_member" "publishers" {
  for_each = { for p in local.publisher_bindings : "${p.member}|${p.topic}" => p }
  project  = var.project_id
  topic    = google_pubsub_topic.main[each.value.topic].name
  role     = "roles/pubsub.publisher"
  member   = each.value.member
}

resource "google_pubsub_subscription_iam_member" "subscribers" {
  for_each     = { for s in local.subscriber_bindings : "${s.member}|${s.subscription}" => s }
  project      = var.project_id
  subscription = google_pubsub_subscription.consumer[each.value.subscription].name
  role         = "roles/pubsub.subscriber"
  member       = each.value.member
}

locals {
  publisher_bindings = flatten([
    for member, topics in var.publishers : [for t in topics : { member = member, topic = t }]
  ])
  subscriber_bindings = flatten([
    for member, subs in var.subscribers : [for s in subs : { member = member, subscription = s }]
  ])
}
