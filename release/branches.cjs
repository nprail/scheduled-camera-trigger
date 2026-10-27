// main publishes stable releases; beta publishes pre-releases (1.3.0-beta.1), which the apps only offer
// firmware from under Firmware > Advanced.
module.exports = ["main", { name: "beta", prerelease: true }];
