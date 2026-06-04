// No-op stub for @vue/devtools-api.
// vue-router (and a few other Vue libs) import setupDevtoolsPlugin from this
// package. The real package is a dev-only enhancement; in a build pipeline
// it gets tree-shaken away. With browser ESM there is no tree-shaking, so we
// satisfy the import with this stub. See docs/adr/0002-vue-without-npm.md.
export function setupDevtoolsPlugin() {}
