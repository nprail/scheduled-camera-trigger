#!/usr/bin/env bash
# Makes the upload keystore for Google Play, and a keystore.properties that points at it.
#
#   apps/android/scripts/generate-keystore.sh [directory]     (default: ~/.kairos-signing)
#
# The keystore and its passwords stay OUTSIDE the repository (and are git-ignored if you put them inside).
# Back the directory up somewhere safe and offline. Google Play App Signing keeps the real app signing key, so
# this is the *upload* key: if it is lost, Google can reset it, but it takes a support request.
set -euo pipefail

dir="${1:-$HOME/.kairos-signing}"
store="$dir/android-upload.jks"
props="$dir/android-keystore.properties"
alias="kairos-upload"

if [ -e "$store" ]; then
  echo "$store exists; not overwriting" >&2
  exit 1
fi

mkdir -p "$dir"
chmod 700 "$dir"
# a long random password; the store and the key share it (Play accepts that, and it is one secret less to keep)
password="$(openssl rand -hex 24)"

"${JAVA_HOME:+$JAVA_HOME/bin/}keytool" -genkeypair -v \
  -keystore "$store" -storetype PKCS12 \
  -alias "$alias" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$password" -keypass "$password" \
  -dname "CN=Kairos, O=Noah Prail, C=US" >/dev/null

umask 077
cat > "$props" <<PROPS
storeFile=$store
storePassword=$password
keyAlias=$alias
keyPassword=$password
PROPS
chmod 600 "$store" "$props"

echo "Wrote $store and $props"
echo
echo "Build a signed release locally with:  cp $props apps/android/keystore.properties"
echo
echo "Set the GitHub secrets for the release workflow:"
echo "  base64 -i $store | gh secret set ANDROID_KEYSTORE_BASE64"
echo "  sed -n 's/^storePassword=//p' $props | gh secret set ANDROID_KEYSTORE_PASSWORD"
echo "  gh secret set ANDROID_KEY_ALIAS --body $alias"
echo "  sed -n 's/^keyPassword=//p' $props | gh secret set ANDROID_KEY_PASSWORD"
echo "  gh secret set PLAY_SERVICE_ACCOUNT_JSON < play-service-account.json   # see docs/releasing-android.md"
