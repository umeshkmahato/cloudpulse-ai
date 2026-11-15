# Uninstall and cleanup record (2026-11-13)

The project environment was torn down at the end of development. To run the project again, reinstall using `RUN-AND-VALIDATE.md` section 1.

## Removed (all were installed by this project on 2026-11-13)
| Item | How removed |
|---|---|
| kind cluster `cloudpulse` (3 nodes) | `kind delete cluster --name cloudpulse` |
| Docker Compose stack and volumes (postgres, valkey, pub/sub emulator, prometheus, grafana) | `docker compose down -v` |
| 16 local images `cloudpulse/*` | `docker rmi -f` |
| Colima VM and `~/.colima` | `colima delete -f` |
| Homebrew packages: colima, lima, kind, trivy, terraform; tap `hashicorp/tap` | `brew uninstall ...`, `brew untap hashicorp/tap`, `brew autoremove` |
| Caches: `~/.cache/trivy`, `~/Library/Caches/trivy`, `~/.terraform.d`; docker context `colima` | `rm -rf`, `docker context rm` |

## Kept (already on the machine before this project)
Docker Desktop and its CLI context, kubectl contexts for other clusters (untouched), Maven, Node 20, OpenJDK 17/21, Homebrew itself, git, gh.

## Left in the project folder (delete by hand if unwanted)
- Build outputs: `services/*/target`, `data-pipeline/*/target`, `frontend/operations-dashboard/node_modules` and `dist`.
- `.run/` (logs, test results, generated demo credentials `identity-credentials.txt`, git-ignored) and `.env` (git-ignored, local values only).
- The Homebrew download cache may hold installer files: `brew cleanup`.

No GCP resources were ever created, so there is nothing to delete in the cloud. The corporate gcloud account was not used.
