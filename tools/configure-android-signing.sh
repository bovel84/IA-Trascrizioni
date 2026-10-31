#!/usr/bin/env bash
# Run once on your own computer/Termux with GitHub CLI already authenticated.
set +x
set -euo pipefail

signing_dir="${1:-$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)}"
repository='bovel84/IA-Trascrizioni'
command -v gh >/dev/null || { echo 'Installa GitHub CLI (gh) e accedi prima di continuare.' >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo 'Esegui gh auth login prima di continuare.' >&2; exit 1; }
test -s "$signing_dir/voicescribe-release.p12"
test -s "$signing_dir/keystore-password.txt"

# Private bytes go directly to GitHub's encrypted Actions secrets over stdin.
# They are never printed, passed as arguments or written in the repository.
base64 < "$signing_dir/voicescribe-release.p12" | tr -d '\r\n' | \
  gh secret set VOICESCRIBE_KEYSTORE_B64 --repo "$repository"
tr -d '\r\n' < "$signing_dir/keystore-password.txt" | \
  gh secret set VOICESCRIBE_KEYSTORE_PASSWORD --repo "$repository"
gh workflow run android-apk.yml --repo "$repository" --ref main
echo 'Firma permanente configurata. Build Android avviata su GitHub.'
