#!/usr/bin/env bash
# Builds an image for every service and web app:  deploy/build-images.sh [tag] [--load-kind]
# With --load-kind the images are also loaded into the kind cluster named "cutpay".
set -euo pipefail
cd "$(dirname "$0")/.."
tag=${1:-dev}
services="gateway identity payments acquirer-sim ledger payouts webhooks links billing bnpl-sim bnpl notifications kyc-sim reporting"
apps="checkout dashboard backoffice site"
for s in $services; do
  docker build -q -f backend/Dockerfile --build-arg SERVICE="$s" -t "cutpay/$s:$tag" backend
done
for a in $apps; do
  docker build -q -f frontend/Dockerfile --build-arg APP="$a" -t "cutpay/$a:$tag" .
done
if [ "${2:-}" = "--load-kind" ]; then
  for i in $services $apps; do kind load docker-image "cutpay/$i:$tag" --name cutpay; done
fi
