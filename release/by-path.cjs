// A semantic-release plugin that makes one repository release two things independently.
//
// It runs the stock commit analyzer and release-notes generator, but only on the commits that touched
// `paths`. A `feat` that only changes firmware/esp32/ is then invisible to the app release, and the other
// way round. A commit that touches both (a protocol change, say) counts for both.
const { execFileSync } = require("node:child_process");
const commitAnalyzer = require("@semantic-release/commit-analyzer");
const notesGenerator = require("@semantic-release/release-notes-generator");

const touched = new Map();

function files(hash, cwd) {
  const key = `${cwd}:${hash}`;
  if (!touched.has(key)) {
    const out = execFileSync(
      "git",
      ["diff-tree", "--no-commit-id", "--name-only", "-r", "-m", "--first-parent", hash],
      { cwd, encoding: "utf8" },
    );
    touched.set(key, out.split("\n").filter(Boolean));
  }
  return touched.get(key);
}

function scoped({ paths }, context) {
  const inside = (file) => paths.some((p) => file === p || file.startsWith(p.endsWith("/") ? p : `${p}/`));
  const commits = context.commits.filter((c) => files(c.hash, context.cwd).some(inside));
  context.logger.log("%d of %d commits touch %s", commits.length, context.commits.length, paths.join(", "));
  return { ...context, commits };
}

module.exports = {
  analyzeCommits: (config, context) => commitAnalyzer.analyzeCommits(config.analyzer ?? {}, scoped(config, context)),
  generateNotes: (config, context) => notesGenerator.generateNotes(config.notes ?? {}, scoped(config, context)),
};
