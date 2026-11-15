# Enables project APIs. Enabling an API is free; resources created through it may not be.
resource "google_project_service" "this" {
  for_each                   = toset(var.apis)
  project                    = var.project_id
  service                    = each.value
  disable_on_destroy         = false
  disable_dependent_services = false
}
