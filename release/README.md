# Releases

`scripts/make-release.sh` builds `v<version>/` with:

| File | Content |
|---|---|
| `sidecar_launcher-<version>.apk` | the launcher (R8-optimized, signed with the debug key — personal sideload) |
| `GUIDA.md` | quick guide (Italian): install, gestures, settings |
| `LICENSE.md`, `THIRD_PARTY_NOTICES.md` | licenses |
| `SHA256SUMS` | checksums (`sha256sum -c SHA256SUMS`) |

Bundles are git-ignored: publish them as a GitHub release
(`gh release create v<version> release/v<version>/* --prerelease`).
