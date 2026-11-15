# Phase 2 starts with LOCAL state (git-ignored). After running ../../bootstrap, migrate:
#   1. uncomment the block below and set the bucket name
#   2. terraform init -migrate-state
# terraform {
#   backend "gcs" {
#     bucket = "cloudpulse-ai-poc-tfstate"
#     prefix = "env/prod"
#   }
# }
