// next publishes stable releases; beta publishes pre-releases (1.3.0-beta.1), which the apps only offer
// firmware from under Firmware > Advanced.
module.exports = ["next", { name: "beta", prerelease: true }];
