#!/usr/bin/env bash
# End-to-end walk through the running stack: one identity across the portal, core and fraud modules; then
# quote, bind, bitemporal endorsement, straight-through claim with a presigned upload, emails, a renewal run, and
# a transaction through the Kafka fraud pipeline. Synthetic data only; safe to rerun.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

RUN=$(openssl rand -hex 3)
# A fresh phone per run: reusing one across runs links the holders in the fraud graph and the claim is held.
PHONE="+91 9$(printf '%09d' $(( $(od -An -N4 -tu4 /dev/urandom) % 1000000000 )))"
CUST="cust-$RUN@demo.example.test"
M=$(token "$MANAGER_SUB" "")   # staff come from the staff table, not from a role claim
C=$(token "$CUST")
S=$(token "stranger-$RUN@demo.example.test")

echo "== auth"
check "no token is 401" "$(call "" GET /v1/me | tail -1)" 401
check "customer role" "$(expect 200 "$C" GET /v1/me | jq -r .role)" CUSTOMER
check "manager role (bootstrap)" "$(expect 200 "$M" GET /v1/me | jq -r .role)" MANAGER

echo "== one identity across modules"
A=$(login_admin)
check "portal admin login issues a token" "$([[ -n $A && $A != null ]] && echo yes || echo no)" yes
check "the same token works on the core API (portal ADMIN is core MANAGER)" "$(expect 200 "$A" GET /v1/me | jq -r .role)" MANAGER
check "no token on the fraud API is 401" "$(call "" GET /api/v1/transactions/nope | tail -1)" 401
check "a customer token is refused by the fraud API" "$(call "$C" GET /api/v1/transactions/nope | tail -1)" 403
check "the admin token is accepted there (unknown id is a business 404)" "$(call "$A" GET /api/v1/transactions/nope | tail -1)" 404

echo "== product"
expect 201 "$M" POST /v1/products "$(demo_product "$M")" > /tmp/insurai-demo-product.json
echo "  published health-demo v$(jq -r .version /tmp/insurai-demo-product.json)"
check "customers can't publish" "$(call "$C" POST /v1/products "$(demo_product "$M")" | tail -1)" 403

echo "== quote and bind"
Q=$(expect 201 "$C" POST /v1/quotes "$(jq -nc --arg e "$CUST" '{productId: "health-demo", holderName: "Demo Holder",
    holderEmail: $e, inputs: {sumInsuredMinor: 50000000, numeric: {age: 34, members: 2},
    categorical: {region: "TIER2"}, riders: ["MATERNITY"]}}')")
echo "  premium $(jq -r '.premium.totalMinor' <<< "$Q") paise: $(jq -c '[.premium.breakdown[] | .step]' <<< "$Q")"
QID=$(jq -r .id <<< "$Q")
B=$(expect 201 "$C" POST "/v1/quotes/$QID/bind" '{"paymentToken":"tok_visa_demo","autopay":true}' -H "Idempotency-Key: bind-$QID")
P=$(jq -r .policyId <<< "$B")
echo "  policy $(jq -r .number <<< "$B") term $(jq -r .termStart <<< "$B") .. $(jq -r .termEnd <<< "$B")"
replay=$(curl -s -D - -o /dev/null -X POST "$BASE/v1/quotes/$QID/bind" -H "Authorization: Bearer $C" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: bind-$QID" -d '{"paymentToken":"tok_visa_demo","autopay":true}' \
  | tr -d '\r' | awk -F': ' 'tolower($1)=="idempotent-replayed" {print $2}')
check "bind retry is a replay, not a second charge" "$replay" true
check "stranger can't see the policy" "$(call "$S" GET "/v1/policies/$P" | tail -1)" 404

echo "== bitemporal endorsement"
BEFORE=$(now_iso)
sleep 1
EFF=$(date -u -d '+1 day' +%Y-%m-%dT%H:%M:%SZ)
LATER=$(date -u -d '+2 days' +%Y-%m-%dT%H:%M:%SZ)
E=$(expect 201 "$C" POST "/v1/policies/$P/endorsements" "{\"effectiveFrom\":\"$EFF\",\"sumInsuredMinor\":80000000}" \
  -H "Idempotency-Key: e-$P")
echo "  endorsement premium delta $(jq -r .premiumDeltaMinor <<< "$E") paise, receipt $(jq -r .receiptNo <<< "$E")"
check "cover after the change" "$(expect 200 "$C" GET "/v1/policies/$P?validAt=$LATER" | jq -r .cover.coverage.inputs.sumInsuredMinor)" 80000000
check "what we believed before recording it" "$(expect 200 "$C" GET "/v1/policies/$P?validAt=$LATER&asOf=$BEFORE" | jq -r .cover.coverage.inputs.sumInsuredMinor)" 50000000
check "cover today is unchanged" "$(expect 200 "$C" GET "/v1/policies/$P" | jq -r .cover.coverage.inputs.sumInsuredMinor)" 50000000

echo "== claim: FNOL, presigned upload, straight-through settlement"
F=$(expect 201 "$C" POST /v1/claims "$(jq -nc --arg p "$P" --arg at "$(now_iso)" --arg phone "$PHONE" '{policyId: $p,
    coverageCode: "HOSPITALISATION", lossAt: $at, claimedMinor: 1200000, description: "Day-care procedure (synthetic)",
    parties: [{type: "PHONE", value: $phone}], payoutToken: "tok_payout_demo",
    documents: [{fileName: "bill.pdf", contentType: "application/pdf", sizeBytes: 9}]}')" -H "Idempotency-Key: fnol-$P")
CL=$(jq -r .claim.id <<< "$F")
DOC=$(jq -r '.uploads[0].documentId' <<< "$F")
URL=$(jq -r '.uploads[0].url' <<< "$F")
mapfile -t HDRS < <(jq -r '.uploads[0].headers | to_entries[] | select(.key | ascii_downcase | IN("host","content-length") | not)
  | "\(.key): \(.value[0])"' <<< "$F")
HARGS=()
for h in "${HDRS[@]}"; do HARGS+=(-H "$h"); done
check "upload to object storage" "$(printf '%%PDF-1.4\n' | curl -s -o /dev/null -w '%{http_code}' -X PUT "${HARGS[@]}" --data-binary @- "$URL")" 200
CLAIM=$(expect 200 "$C" POST "/v1/claims/$CL/documents/$DOC/uploaded" '{}')
check "claim status" "$(jq -r .status <<< "$CLAIM")" SETTLED
check "straight through" "$(jq -r .straightThrough <<< "$CLAIM")" true
echo "  path $(jq -c '[.history[].to]' <<< "$CLAIM"), paid $(jq -r .settlement.amountMinor <<< "$CLAIM") paise"
expect 200 "$C" POST "/v1/claims/$CL/documents/$DOC/uploaded" '{}' > /dev/null
check "settled once after a retried callback" "$(expect 200 "$M" GET "/v1/claims/$CL" | jq -r .settlement.amountMinor)" 1200000

echo "== emails (Kafka -> notification consumer -> Mailpit)"
until_eq 30 1 mails "$CUST" "is active" > /dev/null && echo "  ok   policy issued mail"
until_eq 30 1 mails "$CUST" "was changed" > /dev/null && echo "  ok   endorsement mail"
until_eq 30 1 mails "$CUST" "paid" > /dev/null && echo "  ok   claim paid mail"

echo "== renewals"
check "customers can't run renewals" "$(call "$C" POST "/v1/renewals/run?date=$(TZ=Asia/Kolkata date +%F)" | tail -1)" 403
# The new policy falls due in 365 days. Each business date runs once, so on a reused stack move to the next
# date that hasn't run; the policy is still due on those.
for off in $(seq 365 420); do
  DUE=$(TZ=Asia/Kolkata date -d "+$off days" +%F)
  R1=$(expect 200 "$M" POST "/v1/renewals/run?date=$DUE")
  [[ $(jq -r .replayed <<< "$R1") == false ]] && break
done
echo "  run for $DUE: $(jq -c '{status, renewed, paymentDue, lapsed, reminders, durationMs, replayed}' <<< "$R1")"
R2=$(expect 200 "$M" POST "/v1/renewals/run?date=$DUE")
check "same date again is a replay" "$(jq -r .replayed <<< "$R2")" true
check "this policy renewed once" "$(expect 200 "$C" GET "/v1/policies/$P/renewals" | jq 'length')" 1
until_eq 30 1 mails "$CUST" "renewed" > /dev/null && echo "  ok   renewal mail"

echo "== fraud pipeline (HTTP -> Kafka -> Redis/Postgres -> decision)"
TX=$(cat /proc/sys/kernel/random/uuid)
check "transaction accepted for processing" "$(call "$A" POST /api/v1/transactions "$(jq -nc --arg id "$TX" --arg now "$(now_iso)" \
  '{transactionId: $id, accountId: "acct-demo", amount: 125.50, currency: "USD", merchantId: "m-demo",
    merchantName: "Demo Store", location: "US", timestamp: $now}')" | tail -1)" 202
DECISION=""
for _ in $(seq 30); do
  DECISION=$(curl -s -H "Authorization: Bearer $A" "$BASE/api/v1/transactions/$TX" | jq -r '.decision // empty')
  [[ -n $DECISION ]] && break
  sleep 1
done
check "a decision was recorded by the consumer" "$([[ -n $DECISION ]] && echo yes || echo no)" yes
echo "  decision $DECISION"

echo "== metrics"
METRICS=$(curl -sf "$BASE/actuator/prometheus")
for m in claimpilot_renewals_total claimpilot_notifications_total claimpilot_claims_triaged_total claimpilot_policy_read_seconds fraud_transactions; do
  grep -q "^$m" <<< "$METRICS" && echo "  ok   $m" || { echo "  FAIL missing $m" >&2; exit 1; }
done
echo "demo passed"
