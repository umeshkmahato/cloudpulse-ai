# Creates the remote-state bucket. Run ONCE with local state, then migrate environments to it.
# (The bootstrap's own state stays local; it is tiny and recreatable.)
terraform {
  required_version = ">= 1.5"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
  }
}

variable "project_id" {
  type        = string
  default     = "cloudpulse-ai-poc"
  description = "Project that hosts the state bucket."
}

variable "region" {
  type        = string
  default     = "asia-south2"
  description = "Bucket location."
}

provider "google" {
  project = var.project_id
  region  = var.region
}

resource "google_storage_bucket" "tfstate" {
  name                        = "${var.project_id}-tfstate"
  location                    = var.region
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false

  versioning {
    enabled = true # state history + recovery from bad applies
  }

  lifecycle_rule {
    condition {
      num_newer_versions = 20
    }
    action {
      type = "Delete"
    }
  }

  labels = {
    app        = "cloudpulse-ai"
    managed_by = "terraform"
    purpose    = "tfstate"
  }
}

output "state_bucket" {
  value       = google_storage_bucket.tfstate.name
  description = "Use as the bucket in each environment's backend.tf."
}
