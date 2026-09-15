from pathlib import Path

p = Path('.github/workflows/build.yml')
text = p.read_text()
old = '''          elif git ls-remote --exit-code --tags origin "refs/tags/$EXPECTED_TAG" >/dev/null 2>&1; then
            echo "Release tag already exists: $EXPECTED_TAG" >&2
            echo "Bump versionName/versionCode instead of replacing assets on an older tag." >&2
            exit 1
          fi
'''
new = '''          else
            set +e
            git ls-remote --exit-code --tags origin "refs/tags/$EXPECTED_TAG" >/dev/null 2>&1
            TAG_LOOKUP_STATUS=$?
            set -e
            if [[ "$TAG_LOOKUP_STATUS" -eq 0 ]]; then
              echo "Release tag already exists: $EXPECTED_TAG" >&2
              echo "Bump versionName/versionCode instead of replacing assets on an older tag." >&2
              exit 1
            elif [[ "$TAG_LOOKUP_STATUS" -ne 2 ]]; then
              echo "Unable to verify whether release tag exists: $EXPECTED_TAG (git ls-remote exit $TAG_LOOKUP_STATUS)" >&2
              exit "$TAG_LOOKUP_STATUS"
            fi
          fi
'''
if text.count(old) != 1:
    raise SystemExit(f'expected one tag guard match, found {text.count(old)}')
p.write_text(text.replace(old, new, 1))
Path('.github/scripts/harden_release_tag_guard.py').unlink()
Path('.github/workflows/apply-tag-guard-fail-closed.yml').unlink()
