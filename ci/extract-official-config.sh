#!/usr/bin/env bash
# Récupère la configuration publique embarquée dans l'APK officiel de Nuvio
# (serveur de comptes, clés TMDB / Trakt) et la valide auprès des services réels.
# Usage : extract-official-config.sh FICHIER_SORTIE  (lignes KEY=VALUE ; annotations GitHub sur stdout)
# Une valeur fournie par variable d'environnement (secret) a toujours priorité.
set -uo pipefail
out="$(realpath -m "$1")"; : > "$out"
work=$(mktemp -d); cd "$work"
tag=$(gh release list -R NuvioMedia/NuvioMobile --limit 10 --json tagName,isDraft \
  --jq '[.[]|select(.isDraft|not)][0].tagName')
gh release download "$tag" -R NuvioMedia/NuvioMobile --pattern '*arm64-v8a*.apk' --clobber >/dev/null
unzip -qo "$(ls *.apk | head -1)" 'classes*.dex' -d dex
strings -n 20 dex/*.dex | sort -u > all.txt
echo "::notice::Configuration lue depuis l'APK officiel $tag"

emit() { [ -n "$2" ] && { echo "::add-mask::$2"; echo "$1=$2" >> "$out"; }; }

# --- Serveur de comptes ---
url="${NUVIO_SUPABASE_URL:-$(grep -Eo '^https://api\.nuvio\.tv/?$' all.txt | head -1 | sed 's#/$##')}"
fallback="${NUVIO_SUPABASE_FALLBACK_URL:-$(grep -Eo '^https://api-two\.nuvioapp\.space/?$' all.txt | head -1 | sed 's#/$##')}"
key="${NUVIO_SUPABASE_ANON_KEY:-$(grep -Eo '^sb_publishable_[A-Za-z0-9_-]{20,}$' all.txt | head -1)}"
if [ -z "$key" ]; then
  for jwt in $(grep -Eo '^eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$' all.txt); do
    p=$(echo "$jwt" | cut -d. -f2 | tr '_-' '/+'); while [ $(( ${#p} % 4 )) -ne 0 ]; do p="$p="; done
    if echo "$p" | base64 -d 2>/dev/null | grep -q '"anon"'; then key="$jwt"; break; fi
  done
fi
echo "NUVIO_SUPABASE_URL=$url" >> "$out"; emit NUVIO_SUPABASE_ANON_KEY "$key"
[ -n "$fallback" ] && echo "NUVIO_SUPABASE_FALLBACK_URL=$fallback" >> "$out"

# --- TMDB (clé v3 = 32 hex) : validée en appelant l'API ---
tmdb="${TMDB_API_KEY:-}"
if [ -z "$tmdb" ]; then
  for k in $(strings -n 32 dex/*.dex | grep -Eo '^[0-9a-f]{32}$' | sort -u); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "https://api.themoviedb.org/3/configuration?api_key=$k")
    [ "$code" = 200 ] && { tmdb="$k"; break; }
  done
fi
emit TMDB_API_KEY "$tmdb"
echo "::notice::TMDB : ${tmdb:+clé valide}${tmdb:-introuvable}"

# --- Trakt (client id / secret = 64 hex) : id validé via l'API ---
trakt_id="${TRAKT_CLIENT_ID:-}"; trakt_secret="${TRAKT_CLIENT_SECRET:-}"
cands=$(strings -n 64 dex/*.dex | grep -Eo '^[0-9a-f]{64}$' | sort -u)
if [ -z "$trakt_id" ]; then
  for k in $cands; do
    code=$(curl -s -o /dev/null -w '%{http_code}' -H 'trakt-api-version: 2' -H "trakt-api-key: $k" \
      -H 'User-Agent: NuvioFluide-CI' https://api.trakt.tv/genres/movies)
    [ "$code" = 200 ] && { trakt_id="$k"; break; }
  done
fi
if [ -z "$trakt_secret" ] && [ -n "$trakt_id" ]; then
  others=$(echo "$cands" | grep -v "^$trakt_id$" | grep -c . || true)
  [ "$others" = 1 ] && trakt_secret=$(echo "$cands" | grep -v "^$trakt_id$")
fi
emit TRAKT_CLIENT_ID "$trakt_id"; emit TRAKT_CLIENT_SECRET "$trakt_secret"
echo "::notice::Trakt : id ${trakt_id:+valide}${trakt_id:-introuvable}, secret ${trakt_secret:+trouve}${trakt_secret:-introuvable}"

[ -n "$url" ] && [ -n "$key" ] || { echo "::error::Configuration du compte introuvable"; exit 1; }
