#!/usr/bin/env bash
# Deletes the local kind cluster (all in-cluster data is lost).
set -euo pipefail
kind delete cluster --name cloudpulse
