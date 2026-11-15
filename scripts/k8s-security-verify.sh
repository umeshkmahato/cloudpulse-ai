#!/usr/bin/env bash
# Phase 13 security verification against the local kind cluster: gateway authentication and authorization (roles, CSRF,
# path tricks, brute force), defence in depth at the controller, admission policy, audit integrity and secret hygiene.
# Usage: scripts/k8s-security-verify.sh      (needs scripts/k8s-up.sh to have been run; credentials in .run/identity-credentials.txt)
set -uo pipefail
cd "$(dirname "$0")/.."
NS=cloudpulse; K="kubectl -n $NS"
fail=0
check() { if eval "$2"; then echo "[PASS] $1"; else echo "[FAIL] $1"; fail=1; fi; }
pw() { awk -v u="$1" '$1==u{print $2}' .run/identity-credentials.txt; }
J=$(mktemp -d); trap 'kill $PF1 $PF2 2>/dev/null; rm -rf $J' EXIT
$K port-forward svc/dashboard 18088:8080 >/dev/null 2>&1 & PF1=$!
$K port-forward svc/remediation-controller 18086:8086 >/dev/null 2>&1 & PF2=$!
sleep 3
GW=http://localhost:18088; RC=http://localhost:18086
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
login() { curl -s -c "$J/$1.jar" -D "$J/$1.hdr" -o "$J/$1.body" -w '%{http_code}' -X POST $GW/auth/login -H 'Content-Type: application/json' -d "{\"username\":\"$1\",\"password\":\"$(pw $1)\"}"; }
jwt() { awk '$6=="cp_session"{print $7}' "$J/$1.jar"; }
ORDER='{"customerId":"CUST-SEC","currency":"USD","items":[{"sku":"SKU-RED-1","quantity":1,"unitPrice":19.99}]}'

# The edge login rate limit (10/min) is real: if a previous run just hammered it, wait for it to cool down instead of failing.
for i in $(seq 1 24); do c=$(curl -s -o /dev/null -w '%{http_code}' -X POST $GW/auth/login -H 'Content-Type: application/json' -d '{"username":"cooldown-probe","password":"x"}'); [ "$c" != 429 ] && break; [ $i = 1 ] && echo "   edge login rate limiter still cooling down from a previous run (this is the control working)..."; sleep 5; done

echo "--- 1. authentication at the gateway"
for p in ops-api/api/v1/incidents orders-api/api/v1/orders inventory-api/api/v1/inventory assistant-api/api/v1/assistant/questions remediation-api/api/v1/remediations; do
  check "unauthenticated GET /$p -> 401" "[ \$(code $GW/$p) = 401 ]"
done
check "garbage session cookie -> 401" "[ \$(code -H 'Cookie: cp_session=abc.def.ghi' $GW/ops-api/api/v1/incidents) = 401 ]"
check "unknown user and wrong password are indistinguishable (both 401, same body)" "A=\$(curl -s -X POST $GW/auth/login -H 'Content-Type: application/json' -d '{\"username\":\"ghost\",\"password\":\"x\"}'); B=\$(curl -s -X POST $GW/auth/login -H 'Content-Type: application/json' -d '{\"username\":\"viewer\",\"password\":\"wrong\"}'); [ \"\$A\" = \"\$B\" ]"
check "login succeeds for viewer, operator, admin" "[ \$(login viewer) = 200 ] && [ \$(login operator) = 200 ] && [ \$(login admin) = 200 ]"
check "session cookie is HttpOnly + SameSite=Strict and the token is not in the response body" "grep -i '^set-cookie' $J/viewer.hdr | grep -qi 'HttpOnly' && grep -i '^set-cookie' $J/viewer.hdr | grep -qi 'SameSite=Strict' && ! grep -q 'eyJ' $J/viewer.body"
check "/auth/me reports the logged-in identity" "curl -s -b $J/operator.jar $GW/auth/me | python3 -c 'import sys,json;d=json.load(sys.stdin);sys.exit(0 if d[\"username\"]==\"operator\" and d[\"roles\"]==[\"OPERATOR\"] else 1)'"

echo "--- 2. authorization (roles)"
check "viewer can read incidents (200)" "[ \$(code -b $J/viewer.jar $GW/ops-api/api/v1/incidents) = 200 ]"
check "viewer can ask the assistant (200)" "[ \$(code -b $J/viewer.jar -X POST -H 'X-CloudPulse-CSRF: 1' -H 'Content-Type: application/json' -d '{\"question\":\"What happens if the inventory service fails?\"}' $GW/assistant-api/api/v1/assistant/ask) = 200 ]"
check "viewer cannot place an order (403)" "[ \$(code -b $J/viewer.jar -X POST -H 'X-CloudPulse-CSRF: 1' -H 'Content-Type: application/json' -d '$ORDER' $GW/orders-api/api/v1/orders) = 403 ]"
check "operator can place an order (201)" "[ \$(code -b $J/operator.jar -X POST -H 'X-CloudPulse-CSRF: 1' -H 'Content-Type: application/json' -d '$ORDER' $GW/orders-api/api/v1/orders) = 201 ]"
check "operator cannot flip the kill switch (403)" "[ \$(code -b $J/operator.jar -X POST -H 'X-CloudPulse-CSRF: 1' -H 'Content-Type: application/json' -d '{\"enabled\":true}' $GW/remediation-api/api/v1/remediation/kill-switch) = 403 ]"
check "admin can flip the kill switch (200) and resume" "[ \$(code -b $J/admin.jar -X POST -H 'X-CloudPulse-CSRF: 1' -H 'Content-Type: application/json' -d '{\"enabled\":true}' $GW/remediation-api/api/v1/remediation/kill-switch) = 200 ] && [ \$(code -b $J/admin.jar -X POST -H 'X-CloudPulse-CSRF: 1' -H 'Content-Type: application/json' -d '{\"enabled\":false}' $GW/remediation-api/api/v1/remediation/kill-switch) = 200 ]"
check "no rule = deny: even admin cannot DELETE an order (403)" "[ \$(code -b $J/admin.jar -X DELETE -H 'X-CloudPulse-CSRF: 1' $GW/orders-api/api/v1/orders/ORD-1) = 403 ]"
check "actuator is never reachable through the gateway, even for admin (403)" "[ \$(code -b $J/admin.jar $GW/ops-api/actuator/prometheus) = 403 ]"
check "internal auth endpoints are not exposed (jwks, verify, audit -> 404)" "[ \$(code $GW/auth/jwks) = 404 ] && [ \$(code $GW/auth/verify) = 404 ] && [ \$(code $GW/auth/audit) = 404 ]"

echo "--- 3. request-level attacks"
check "CSRF: state-changing call without the custom header is refused (403) even with a valid cookie" "[ \$(code -b $J/operator.jar -X POST -H 'Content-Type: application/json' -d '$ORDER' $GW/orders-api/api/v1/orders) = 403 ]"
check "path traversal /api/../actuator is refused (403)" "[ \$(code --path-as-is -b $J/admin.jar '$GW/ops-api/api/../actuator/env') = 403 ]"
check "encoded traversal (%2e%2e) is refused (403)" "[ \$(code --path-as-is -b $J/admin.jar '$GW/ops-api/api/%2e%2e/actuator/env') = 403 ]"
check "client-supplied X-Auth-User / Authorization headers cannot impersonate (still 401)" "[ \$(code -H 'X-Auth-User: admin' -H 'X-Auth-Roles: ADMIN' -H 'Authorization: Bearer abc' $GW/ops-api/api/v1/incidents) = 401 ]"
check "security headers on SPA and on API denials" "curl -sI $GW/ | grep -qi 'x-frame-options: DENY' && curl -sI $GW/ops-api/api/v1/incidents | grep -qi 'x-content-type-options: nosniff'"
check "server version not disclosed" "! curl -sI $GW/ | grep -qi 'server: nginx/'"

echo "--- 4. defence in depth: the controller verifies identity itself (bypassing the gateway)"
check "controller refuses a request with no token (401)" "[ \$(code -X POST -H 'Content-Type: application/json' -d '{}' $RC/api/v1/remediations/00000000-0000-4000-8000-000000000000/approve) = 401 ]"
check "controller refuses a VIEWER token for approvals (403)" "[ \$(code -X POST -H \"Authorization: Bearer \$(jwt viewer)\" -H 'Content-Type: application/json' -d '{}' $RC/api/v1/remediations/00000000-0000-4000-8000-000000000000/approve) = 403 ]"
check "controller accepts an OPERATOR token (404: authorised, remediation does not exist)" "[ \$(code -X POST -H \"Authorization: Bearer \$(jwt operator)\" -H 'Content-Type: application/json' -d '{}' $RC/api/v1/remediations/00000000-0000-4000-8000-000000000000/approve) = 404 ]"
check "controller refuses an OPERATOR token for the kill switch (403)" "[ \$(code -X POST -H \"Authorization: Bearer \$(jwt operator)\" -H 'Content-Type: application/json' -d '{\"enabled\":true}' $RC/api/v1/remediation/kill-switch) = 403 ]"
TAMPER="\$(jwt viewer | cut -d. -f1).\$(printf '{\"sub\":\"viewer\",\"roles\":[\"ADMIN\"],\"iss\":\"cloudpulse-identity\",\"aud\":\"cloudpulse-api\",\"exp\":9999999999}' | base64 | tr -d '=\\n' | tr '+/' '-_').\$(jwt viewer | cut -d. -f3)"
check "controller refuses a token whose payload was edited to ADMIN (401, signature mismatch)" "[ \$(code -X POST -H \"Authorization: Bearer $TAMPER\" -H 'Content-Type: application/json' -d '{\"enabled\":true}' $RC/api/v1/remediation/kill-switch) = 401 ]"

echo "--- 5. brute force protection"
n429=0; for i in $(seq 1 16); do c=$(code -X POST $GW/auth/login -H 'Content-Type: application/json' -d '{"username":"brute","password":"guess'$i'"}'); [ "$c" = 429 ] && n429=$((n429+1)); done
check "repeated bad logins get rate limited / locked out (429 seen: $n429)" "[ $n429 -ge 1 ]"

echo "--- 6. audit integrity"
PGPW=$($K get secret cloudpulse-db -o jsonpath='{.data.password}' | base64 -d)
pgq() { $K exec postgres-0 -- env PGPASSWORD=$PGPW psql -U cloudpulse -d cloudpulse -tAc "$1" 2>&1; }
check "identity audit recorded failed logins, successes and denials" "[ \$(pgq \"select count(distinct event) from identity.auth_audit where event in ('LOGIN_FAILED','LOGIN_OK','ACCESS_DENIED','CSRF_REJECTED')\") -ge 4 ]"
UPD=$(pgq "update identity.auth_audit set username='x'"); DEL=$(pgq "delete from identity.auth_audit")
check "identity audit is append-only (UPDATE/DELETE refused by the database)" "echo \"\$UPD\" | grep -q 'append-only' && echo \"\$DEL\" | grep -q 'append-only'"
check "audit never stores passwords or tokens" "[ \$(pgq \"select count(*) from identity.auth_audit where detail ilike '%eyJ%' or detail ilike '%$(pw viewer)%'\") -eq 0 ]"

echo "--- 7. admission policy (ValidatingAdmissionPolicy)"
# adm <image> <extra container json> <extra pod spec json>: server-side dry-run of a pod; prints the API server's verdict
adm() {
  printf '{"apiVersion":"v1","kind":"Pod","metadata":{"name":"adm-test"},"spec":{%s"securityContext":{"runAsNonRoot":true,"runAsUser":100,"seccompProfile":{"type":"RuntimeDefault"}},"containers":[{"name":"adm-test","image":"%s",%s"securityContext":{"allowPrivilegeEscalation":false,%s"capabilities":{"drop":["ALL"]}},"resources":{"requests":{"cpu":"10m","memory":"16Mi"},"limits":{"memory":"64Mi"}}}]}}' "$3" "$1" "" "$2" | $K apply --dry-run=server -f - 2>&1 || true     # a denial exits non-zero; with pipefail that would mask the grep
}
check "image :latest is denied" "adm curlimages/curl:latest '' '' | grep -q 'non-latest'"
check "image from a non-allow-listed registry is denied" "adm evil.example.com/miner:1.0 '' '' | grep -q 'allow-list'"
check "privileged container is denied" "adm curlimages/curl:8.10.1 '\"privileged\":true,' '' | grep -qi 'privileged'"
check "hostPath volume is denied" "adm curlimages/curl:8.10.1 '' '\"volumes\":[{\"name\":\"h\",\"hostPath\":{\"path\":\"/\"}}],' | grep -qi 'hostPath'"
check "a compliant pod is admitted" "adm curlimages/curl:8.10.1 '' '' | grep -q 'pod/adm-test'"

echo "--- 8. secret hygiene"
check "no key material, credentials or .env tracked by git" "[ -z \"\$(git ls-files | grep -iE '\\.pem$|\\.p12$|\\.jks$|credentials|(^|/)\\.env$|tfstate|\\.tfvars$')\" ]"
check "no private keys or cloud/API tokens in tracked file contents" "! git grep -qE 'BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|ghp_[0-9A-Za-z]{36}|xox[baprs]-' -- . ':!scripts/k8s-security-verify.sh' ':!architecture/security-design.md'"
check "generated credentials file is git-ignored" "git check-ignore -q .run/identity-credentials.txt"
check "identity-users secret holds bcrypt hashes only (no demo password appears in it)" "S=\$($K get secret identity-users -o jsonpath='{.data.users\\.json}' | base64 -d); echo \"\$S\" | grep -q '\\\$2a\\\$' && ! echo \"\$S\" | grep -q \"\$(pw operator)\""
check "no workload passes a password/key/token as a literal env value (all use secretKeyRef)" "$K get deploy,statefulset -o json | python3 -c 'import sys,json,re;items=json.load(sys.stdin)[\"items\"];bad=[(i[\"metadata\"][\"name\"],e[\"name\"]) for i in items for c in i[\"spec\"][\"template\"][\"spec\"][\"containers\"] for e in c.get(\"env\",[]) if re.search(\"PASSWORD|KEY|TOKEN|SECRET\",e[\"name\"]) and \"value\" in e and \"valueFrom\" not in e];print(bad);sys.exit(1 if bad else 0)' >/dev/null"
check "ConfigMap contains no credentials" "! $K get cm cloudpulse-config -o yaml | grep -qiE 'password|secret|token'"

kill $PF1 $PF2 2>/dev/null
echo; [ $fail -eq 0 ] && echo "PHASE 13 SECURITY VERIFY: ALL PASSED" || echo "PHASE 13 SECURITY VERIFY: FAILURES"
exit $fail
