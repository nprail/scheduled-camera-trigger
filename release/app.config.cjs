// The apps (iOS and Android) are versioned together: one number, one tag (app-v1.2.3), one changelog.
// Run: npx semantic-release --extends ./release/app.config.cjs
//
// Deliberately no GitHub Release: the apps look for firmware at releases/latest/download/manifest.json,
// and an app release would become "latest". The tag and apps/CHANGELOG.md are the record.
module.exports = {
  branches: require("./branches.cjs"),
  tagFormat: "app-v${version}",
  plugins: [
    [
      "./release/by-path.cjs",
      {
        // protocol/ and the spec are shared with the firmware, so a protocol change releases both
        paths: ["apps", "brand", "protocol", "docs/ble-protocol.md"],
      },
    ],
    ["@semantic-release/changelog", { changelogFile: "apps/CHANGELOG.md", changelogTitle: "# Kairos apps changelog" }],
    ["@semantic-release/exec", { prepareCmd: "node scripts/set-app-version.mjs ${nextRelease.version}" }],
    [
      "@semantic-release/git",
      {
        assets: [
          "apps/CHANGELOG.md",
          "apps/android/app/build.gradle.kts",
          "apps/ios/Kairos.xcodeproj/project.pbxproj",
        ],
        message: "chore(release): apps ${nextRelease.version} [skip ci]\n\n${nextRelease.notes}",
      },
    ],
  ],
};
