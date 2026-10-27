// Writes one version into both apps. Run by semantic-release (release/app.config.cjs) and by hand:
//   node scripts/set-app-version.mjs 1.4.0 [build]
//
// versionName / MARKETING_VERSION: the semver.
// versionCode / CURRENT_PROJECT_VERSION: the build number, which both stores need to go up with every upload.
// It is the second argument, else $GITHUB_RUN_NUMBER (the release workflow's incrementing run number).
// With neither (a local run) the build number is left alone.
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const root = new URL("../", import.meta.url);
const version = process.argv[2];
const m = /^(\d+)\.(\d+)\.(\d+)(?:-[0-9A-Za-z.-]+)?$/.exec(version ?? "");
if (!m) {
  console.error("usage: set-app-version.mjs <major.minor.patch[-prerelease]> [build]");
  process.exit(1);
}
const rawBuild = process.argv[3] ?? process.env.GITHUB_RUN_NUMBER;
const build = rawBuild === undefined || rawBuild === "" ? null : Number(rawBuild);
if (build !== null && !(Number.isInteger(build) && build > 0)) {
  console.error(`the build number must be a positive integer, got "${rawBuild}"`);
  process.exit(1);
}
// App Store and Play only accept numeric versions: a pre-release keeps its core version
const marketing = `${m[1]}.${m[2]}.${m[3]}`;

function edit(path, replacements) {
  const file = fileURLToPath(new URL(path, root));
  let text = readFileSync(file, "utf8");
  for (const [pattern, to] of replacements) {
    if (!pattern.test(text)) throw new Error(`${path}: nothing matches ${pattern}`);
    text = text.replace(pattern, to);
  }
  writeFileSync(file, text);
}

edit("apps/android/app/build.gradle.kts", [
  ...(build === null ? [] : [[/versionCode = \d+/, `versionCode = ${build}`]]),
  [/versionName = "[^"]*"/, `versionName = "${version}"`],
]);
edit("apps/ios/Kairos.xcodeproj/project.pbxproj", [
  ...(build === null ? [] : [[/CURRENT_PROJECT_VERSION = [^;]+;/g, `CURRENT_PROJECT_VERSION = ${build};`]]),
  [/MARKETING_VERSION = [^;]+;/g, `MARKETING_VERSION = ${marketing};`],
]);
console.log(`apps -> ${version}${build === null ? " (build number unchanged)" : ` (build ${build})`}`);
