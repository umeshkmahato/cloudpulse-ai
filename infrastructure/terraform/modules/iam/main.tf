# One service account per workload (least privilege, separate identities) plus optional
# GKE Workload Identity bindings and GitHub Actions Workload Identity Federation (no JSON keys).
locals {
  role_bindings = merge([
    for sa_key, sa in var.service_accounts : {
      for role in sa.roles : "${sa_key}|${role}" => { sa = sa_key, role = role }
    }
  ]...)
}

resource "google_service_account" "this" {
  for_each     = var.service_accounts
  project      = var.project_id
  account_id   = each.key
  display_name = each.value.display_name
  description  = each.value.description
}

# Non-authoritative: adds only these members, never removes other bindings.
resource "google_project_iam_member" "roles" {
  for_each = local.role_bindings
  project  = var.project_id
  role     = each.value.role
  member   = "serviceAccount:${google_service_account.this[each.value.sa].email}"
}

# GKE Workload Identity: lets a Kubernetes SA impersonate a Google SA. Create after the cluster exists.
resource "google_service_account_iam_member" "workload_identity" {
  for_each           = var.k8s_workload_identity
  service_account_id = google_service_account.this[each.value.service_account].name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[${each.value.namespace}/${each.value.ksa}]"
}

# GitHub Actions -> GCP without long-lived keys.
resource "google_iam_workload_identity_pool" "github" {
  count                     = var.github.enabled ? 1 : 0
  project                   = var.project_id
  workload_identity_pool_id = "github-pool"
  display_name              = "GitHub Actions"
}

resource "google_iam_workload_identity_pool_provider" "github" {
  count                              = var.github.enabled ? 1 : 0
  project                            = var.project_id
  workload_identity_pool_id          = google_iam_workload_identity_pool.github[0].workload_identity_pool_id
  workload_identity_pool_provider_id = "github-provider"
  display_name                       = "GitHub OIDC"
  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
    "attribute.ref"        = "assertion.ref"
  }
  # Only tokens minted for THIS repository are accepted.
  attribute_condition = "assertion.repository == \"${var.github.repository}\""
  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

resource "google_service_account_iam_member" "github_impersonation" {
  count              = var.github.enabled ? 1 : 0
  service_account_id = google_service_account.this[var.github.service_account].name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github[0].name}/attribute.repository/${var.github.repository}"
}
