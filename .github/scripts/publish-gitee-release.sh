#!/usr/bin/env bash
set -euo pipefail

: "${GITEE_PAT:?GITEE_PAT is required}"
: "${VERSION:?VERSION is required}"
: "${TAG_NAME:?TAG_NAME is required}"
: "${SOURCE_COMMIT:?SOURCE_COMMIT is required}"
: "${RELEASES_REPOSITORY:?RELEASES_REPOSITORY is required}"

api="https://gitee.com/api/v5/repos/${RELEASES_REPOSITORY}"
tag_sha="$(git ls-remote "https://gitee.com/${RELEASES_REPOSITORY}.git" "refs/tags/${TAG_NAME}^{}" | cut -f1)"
if [[ -z "${tag_sha}" ]]; then
  tag_sha="$(git ls-remote "https://gitee.com/${RELEASES_REPOSITORY}.git" "refs/tags/${TAG_NAME}" | cut -f1)"
fi
if [[ "${tag_sha}" != "${SOURCE_COMMIT}" ]]; then
  echo "Gitee tag ${TAG_NAME} must point to ${SOURCE_COMMIT}." >&2
  exit 1
fi

if [[ -f "release-notes/${VERSION}.md" ]]; then
  notes_file="release-notes/${VERSION}.md"
else
  notes_file="${RUNNER_TEMP:-/tmp}/release-notes-${TAG_NAME}.md"
  git log -1 --pretty=%B > "${notes_file}"
fi

gitee_curl() {
  curl --silent --show-error --fail-with-body --retry 3 \
    -H "Authorization: Bearer ${GITEE_PAT}" \
    "$@"
}

release="$(gitee_curl "${api}/releases/tags/${TAG_NAME}")"
if [[ "${release}" == "null" ]]; then
  gitee_curl -X POST \
    --data-urlencode "tag_name=${TAG_NAME}" \
    --data-urlencode "target_commitish=${SOURCE_COMMIT}" \
    --data-urlencode "name=Zhixing v${VERSION}" \
    --data-urlencode "body@${notes_file}" \
    "${api}/releases" >/dev/null
  release="$(gitee_curl "${api}/releases/tags/${TAG_NAME}")"
fi
release_id="$(jq -r '.id // empty' <<< "${release}")"
test -n "${release_id}" || { echo "Gitee Release was not created." >&2; exit 1; }

assets=(
  "release/zhixing-${VERSION}-universal.apk"
  "release/zhixing-${VERSION}-source.tar.gz"
  "release/SHA256SUMS.txt"
  "release/LICENSE"
  "release/THIRD_PARTY_NOTICES.md"
  "release/latest.json"
)
for file in "${assets[@]}"; do
  test -s "${file}" || { echo "Missing release asset: ${file}" >&2; exit 1; }
  name="$(basename "${file}")"
  existing_id="$(jq -r --arg name "${name}" '.assets[]? | select(.name == $name) | .id' <<< "${release}" | head -n 1)"
  if [[ -n "${existing_id}" ]]; then
    gitee_curl -X DELETE "${api}/releases/${release_id}/attach_files/${existing_id}" >/dev/null
  fi
  uploaded="$(gitee_curl -X POST -F "file=@${file}" "${api}/releases/${release_id}/attach_files")"
  jq -e --arg name "${name}" '.name == $name' <<< "${uploaded}" >/dev/null
  echo "Uploaded ${name} to Gitee ${TAG_NAME}."
done

readback="$(gitee_curl "${api}/releases/tags/${TAG_NAME}")"
for file in "${assets[@]}"; do
  name="$(basename "${file}")"
  jq -e --arg name "${name}" '[.assets[]? | select(.name == $name)] | length == 1' <<< "${readback}" >/dev/null
done
echo "Gitee Release ${TAG_NAME} contains all six official assets."
