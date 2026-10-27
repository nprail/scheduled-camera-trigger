// The ESP32 firmware has its own version line, tagged v1.2.3. The workflow builds and signs the images for
// the tag and publishes the GitHub Release, which semantic-release only creates as a draft.
// Run: npx semantic-release --extends ./release/firmware.config.cjs
module.exports = {
  branches: require("./branches.cjs"),
  tagFormat: "v${version}",
  plugins: [
    [
      "./release/by-path.cjs",
      {
        // the legacy Pi program (firmware/legacy-pi) is not released
        paths: ["firmware/esp32", "protocol", "docs/ble-protocol.md"],
      },
    ],
    // a draft, so the app-facing "latest" release (and its manifest.json) never exists without its images
    ["@semantic-release/github", { draftRelease: true, successComment: false, failComment: false, releasedLabels: false }],
    // tell the workflow which version to build
    ["@semantic-release/exec", { publishCmd: 'echo "version=${nextRelease.version}" >> "$GITHUB_OUTPUT"' }],
  ],
};
