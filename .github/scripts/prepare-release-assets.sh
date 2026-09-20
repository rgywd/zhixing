#!/usr/bin/env bash
set -euo pipefail

: "${VERSION:?VERSION is required}"
: "${TAG_NAME:?TAG_NAME is required}"
: "${SOURCE_COMMIT:?SOURCE_COMMIT is required}"
: "${RELEASES_REPOSITORY:?RELEASES_REPOSITORY is required}"

output_dir="${OUTPUT_DIR:-release}"
apk_dir="${APK_DIR:-app/build/outputs/apk/release}"
mkdir -p "${output_dir}"

mapfile -t release_apks < <(find "${apk_dir}" -type f -name '*.apk' -print)
if [[ "${#release_apks[@]}" -ne 1 ]]; then
  echo "Expected exactly one universal release APK, found ${#release_apks[@]}" >&2
  printf '%s\n' "${release_apks[@]}" >&2
  exit 1
fi

universal_name="zhixing-${VERSION}-universal.apk"
source_name="zhixing-${VERSION}-source.tar.gz"
cp "${release_apks[0]}" "${output_dir}/${universal_name}"
work_manifest='null'
checksum_files=("${universal_name}" "${source_name}")
if [[ -d work-app ]]; then
  mapfile -t work_apks < <(find "${WORK_APK_DIR:-work-app/build/outputs/apk/release}" -type f -name '*.apk' -print)
  if [[ "${#work_apks[@]}" -ne 1 ]]; then
    echo "Expected exactly one universal Work APK" >&2
    exit 1
  fi
  work_name="zhixing-work-${VERSION}-universal.apk"
  cp "${work_apks[0]}" "${output_dir}/${work_name}"
  checksum_files+=("${work_name}")
  work_manifest="$(jq -n --arg name "${work_name}" \
    --arg url "https://github.com/${RELEASES_REPOSITORY}/releases/download/${TAG_NAME}/${work_name}" \
    --arg sha256 "$(sha256sum "${output_dir}/${work_name}" | cut -d ' ' -f 1)" \
    '{name: $name, url: $url, sha256: $sha256}')"
fi

tar \
  --exclude='./.git' \
  --exclude='*/.git' \
  --exclude='./.gradle' \
  --exclude='*/build' \
  --exclude='./web-ui/node_modules' \
  --exclude='./app/app.key' \
  --exclude='./local.properties' \
  --exclude="./${output_dir}" \
  --transform="s,^\./,zhixing-${VERSION}/," \
  -czf "${output_dir}/${source_name}" .

cp LICENSE THIRD_PARTY_NOTICES.md "${output_dir}/"

universal_sha256="$(sha256sum "${output_dir}/${universal_name}" | cut -d ' ' -f 1)"
source_sha256="$(sha256sum "${output_dir}/${source_name}" | cut -d ' ' -f 1)"
size_bytes="$(stat -c '%s' "${output_dir}/${universal_name}")"
size="$(numfmt --to=iec-i --suffix=B "${size_bytes}")"

if [[ -f "release-notes/${VERSION}.md" ]]; then
  changelog="$(cat "release-notes/${VERSION}.md")"
else
  changelog="$(git log -1 --pretty=%B)"
fi

published_at="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
base_url="https://github.com/${RELEASES_REPOSITORY}/releases/download/${TAG_NAME}"

jq -n \
  --arg version "${VERSION}" \
  --arg publishedAt "${published_at}" \
  --arg changelog "${changelog}" \
  --arg apkName "${universal_name}" \
  --arg apkUrl "${base_url}/${universal_name}" \
  --arg apkSize "${size}" \
  --arg apkSha256 "${universal_sha256}" \
  --arg sourceName "${source_name}" \
  --arg sourceUrl "${base_url}/${source_name}" \
  --arg sourceSha256 "${source_sha256}" \
  --arg sourceCommit "${SOURCE_COMMIT}" \
  --argjson work "${work_manifest}" \
  '{
    version: $version,
    publishedAt: $publishedAt,
    changelog: $changelog,
    downloads: [{name: $apkName, url: $apkUrl, size: $apkSize, sha256: $apkSha256}],
    source: {name: $sourceName, url: $sourceUrl, sha256: $sourceSha256, commit: $sourceCommit},
    work: $work
  }' > "${output_dir}/latest.json"

(
  cd "${output_dir}"
  sha256sum "${checksum_files[@]}" > SHA256SUMS.txt
)
