#!/usr/bin/env node
// check-templates.mjs — validate every inline Vue template in the frontend.
//
//   node scripts/check-templates.mjs
//
// The frontend has no build step (ADR-0002): templates are backtick strings
// compiled in the browser, so nothing catches a broken one before a user opens
// the page. Two classes of defect are checked here, both found in production:
//
//   1. Syntax — the template does not compile at all. Checked with the very
//      Vue build the browser loads (vendor/vue.esm-browser.prod.js), so the
//      verdict cannot drift from what actually runs.
//
//   2. Out-of-scope loop variables — an element referring to a `v-for` alias
//      it does not sit inside. This compiles fine and then throws at render
//      time ("Cannot read properties of undefined"), taking the whole view
//      with it. Shipped once in v0.20.0-rc.3, where the peer import dialog
//      never opened.
//
// Deliberately no dependencies: nothing is installed, so there is no package
// to be compromised. That rules out asking Vue's compiler for a proper scope
// analysis — the browser build cannot do `prefixIdentifiers` — hence the
// hand-rolled scope walk below. It only ever flags an identifier that IS a
// loop alias somewhere in the same template, which keeps it free of the false
// positives a general "unknown identifier" check would produce against
// mixins and globals.

import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("..", import.meta.url));
const JS_DIR = join(ROOT, "src/main/resources/META-INF/resources/js");
const VUE = join(ROOT, "src/main/resources/META-INF/resources/vendor/vue.esm-browser.prod.js");

// Vue's browser compiler decodes HTML entities through a detached element, so
// it needs a document even when only compiling. It uses exactly two shapes:
// innerHTML + textContent for text, and innerHTML + children[0].getAttribute
// for attribute values. Stubbing those two is the whole requirement — no DOM
// library, nothing installed.
const ENTITIES = { lt: "<", gt: ">", amp: "&", quot: '"', apos: "'", nbsp: "\u00a0" };
const decodeEntities = (s) => s
  .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(Number(d)))
  .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCodePoint(parseInt(h, 16)))
  .replace(/&([a-z]+);/gi, (m, name) => ENTITIES[name.toLowerCase()] ?? m);

if (typeof globalThis.document === "undefined") {
  globalThis.document = {
    createElement: () => ({
      _raw: "",
      set innerHTML(v) { this._raw = v; },
      get textContent() { return decodeEntities(this._raw); },
      get children() {
        const attr = /^<div foo="([\s\S]*)">$/.exec(this._raw);
        return [{ getAttribute: () => (attr ? decodeEntities(attr[1]) : null) }];
      },
    }),
  };
}

const { compile } = await import(VUE);

// Elements that never have a closing tag, so they must not push a scope.
const VOID = new Set(["area", "base", "br", "col", "embed", "hr", "img", "input",
  "link", "meta", "param", "source", "track", "wbr"]);

// Identifiers that look like variables but are not.
const NOT_IDENTIFIERS = new Set(["true", "false", "null", "undefined", "new", "typeof",
  "in", "of", "instanceof", "return", "function", "await", "void", "delete", "this"]);

const errors = [];

function jsFiles(dir) {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    return statSync(p).isDirectory() ? jsFiles(p) : (name.endsWith(".js") ? [p] : []);
  });
}

/**
 * Pull out every backtick literal assigned to something template-shaped
 * (`template:` in a component, or an exported `...Template = `). `${...}`
 * interpolations are blanked rather than resolved: a template composed from
 * another one is checked where that other one is defined.
 */
function extractTemplates(source) {
  const out = [];
  const start = /(?:template\s*:|[A-Za-z_$][\w$]*Template\s*=)\s*`/g;
  let m;
  while ((m = start.exec(source))) {
    let i = m.index + m[0].length;
    const from = i;
    let depth = 0;
    for (; i < source.length; i++) {
      const c = source[i];
      if (c === "\\") { i++; continue; }
      if (c === "$" && source[i + 1] === "{") { depth++; i++; continue; }
      if (c === "}" && depth > 0) { depth--; continue; }
      if (c === "`" && depth === 0) break;
    }
    out.push({ offset: from, text: blankInterpolations(source.slice(from, i)) });
    start.lastIndex = i + 1;
  }
  return out;
}

/**
 * Replace every `${...}` with a literal 0, padded to the original length so
 * reported line and column numbers still match the source file. Blanking them
 * outright is not enough: several templates interpolate constants *into*
 * expressions (`:r="${NODE_RADIUS}"`), and an empty one is a syntax error the
 * browser never sees. Nesting is tracked, so `${f({a: 1})}` is handled.
 */
function blankInterpolations(text) {
  let out = "";
  for (let i = 0; i < text.length; i++) {
    if (text[i] !== "$" || text[i + 1] !== "{") { out += text[i]; continue; }
    const start = i;
    let depth = 0;
    for (; i < text.length; i++) {
      if (text[i] === "{") depth++;
      else if (text[i] === "}" && --depth === 0) break;
    }
    const raw = text.slice(start, i + 1);
    out += "0" + [...raw.slice(1)].map((c) => (c === "\n" ? "\n" : " ")).join("");
  }
  return out;
}

const lineOf = (source, offset) => source.slice(0, offset).split("\n").length;

/** Aliases a v-for introduces: "(item, i) in xs" -> [item, i]; "c in xs" -> [c]. */
function forAliases(expr) {
  const lhs = expr.split(/\s+(?:in|of)\s+/)[0].trim().replace(/^\(|\)$/g, "");
  return lhs.split(",").map((s) => s.trim()).filter((s) => /^[A-Za-z_$][\w$]*$/.test(s));
}

/**
 * Names an expression binds itself — `xs.some(r => r.id)` declares `r`, which
 * has nothing to do with a `v-for="r in ..."` elsewhere in the template.
 */
function locallyBound(expr) {
  const names = new Set();
  const arrow = /(?:\(([^)]*)\)|([A-Za-z_$][\w$]*))\s*=>/g;
  let m;
  while ((m = arrow.exec(expr))) {
    for (const p of (m[1] ?? m[2] ?? "").split(",")) {
      const name = p.trim().replace(/[=:].*$/, "").trim();
      if (/^[A-Za-z_$][\w$]*$/.test(name)) names.add(name);
    }
  }
  return names;
}

function identifiers(expr) {
  const bound = locallyBound(expr);
  const found = [];
  const re = /(?<![.\w$'"])([A-Za-z_$][\w$]*)/g;
  let m;
  while ((m = re.exec(expr))) {
    if (!NOT_IDENTIFIERS.has(m[1]) && !bound.has(m[1])) found.push(m[1]);
  }
  // One report per name, not per occurrence: `!c.a && c.b` is a single mistake.
  return [...new Set(found)];
}

/** Attribute values that Vue evaluates as expressions, plus {{ }} interpolations. */
function* expressions(tagBody) {
  const attr = /([@:.#]?[A-Za-z_@:][\w.:@-]*)\s*=\s*"([^"]*)"/g;
  let m;
  while ((m = attr.exec(tagBody))) {
    const name = m[1];
    if (name.startsWith(":") || name.startsWith("@") || name.startsWith("v-") || name.startsWith("#")) {
      yield { name, value: m[2] };
    }
  }
}

function checkScopes(file, source, tpl) {
  // Every alias used anywhere — the check only fires on these, never on a
  // mixin method or an imported helper it knows nothing about.
  const allAliases = new Set();
  const tagRe = /<(\/?)([A-Za-z][\w-]*)((?:[^>"']|"[^"]*"|'[^']*')*?)(\/?)>/g;
  let m;
  while ((m = tagRe.exec(tpl.text))) {
    if (m[1]) continue;
    for (const { name, value } of expressions(m[3])) {
      if (name === "v-for") forAliases(value).forEach((a) => allAliases.add(a));
    }
  }
  if (allAliases.size === 0) return;

  const stack = [];
  const inScope = (id) => stack.some((f) => f.aliases.includes(id));

  tagRe.lastIndex = 0;
  while ((m = tagRe.exec(tpl.text))) {
    const [full, closing, tag, body, selfClosing] = m;

    if (closing) {
      for (let i = stack.length - 1; i >= 0; i--) {
        if (stack[i].tag === tag) { stack.length = i; break; }
      }
      continue;
    }

    // The element's own v-for is in scope for its other attributes.
    let aliases = [];
    for (const { name, value } of expressions(body)) {
      if (name === "v-for") aliases = forAliases(value);
    }
    const frame = { tag, aliases };
    stack.push(frame);

    for (const { name, value } of expressions(body)) {
      if (name === "v-for") continue;
      for (const id of identifiers(value)) {
        if (allAliases.has(id) && !inScope(id)) {
          errors.push(`${file}:${lineOf(source, tpl.offset + m.index)}  <${tag} ${name}="${value}">`
            + `\n    "${id}" is a v-for alias but this element is not inside that loop.`
            + `\n    It compiles, then throws at render time and the view never appears.`);
        }
      }
    }

    if (selfClosing || VOID.has(tag)) stack.pop();
  }

  // Interpolations outside any tag body.
  const text = tpl.text;
  const interp = /\{\{([^}]*)\}\}/g;
  while ((m = interp.exec(text))) {
    // Rebuild the scope at this offset by counting tags before it.
    const before = text.slice(0, m.index);
    const st = [];
    let t;
    const tr = /<(\/?)([A-Za-z][\w-]*)((?:[^>"']|"[^"]*"|'[^']*')*?)(\/?)>/g;
    while ((t = tr.exec(before))) {
      if (t[1]) {
        for (let i = st.length - 1; i >= 0; i--) if (st[i].tag === t[2]) { st.length = i; break; }
        continue;
      }
      let al = [];
      for (const { name, value } of expressions(t[3])) if (name === "v-for") al = forAliases(value);
      st.push({ tag: t[2], aliases: al });
      if (t[4] || VOID.has(t[2])) st.pop();
    }
    for (const id of identifiers(m[1])) {
      if (allAliases.has(id) && !st.some((f) => f.aliases.includes(id))) {
        errors.push(`${file}:${lineOf(source, tpl.offset + m.index)}  {{${m[1]}}}`
          + `\n    "${id}" is a v-for alias but this text is not inside that loop.`);
      }
    }
  }
}

let templateCount = 0;
for (const path of jsFiles(JS_DIR)) {
  const file = relative(ROOT, path);
  const source = readFileSync(path, "utf8");
  for (const tpl of extractTemplates(source)) {
    templateCount++;
    try {
      compile(tpl.text);
    } catch (e) {
      errors.push(`${file}:${lineOf(source, tpl.offset)}  template does not compile\n    ${e.message}`);
      continue;
    }
    checkScopes(file, source, tpl);
  }
}

if (errors.length) {
  console.error(`\n${errors.length} template problem(s):\n`);
  for (const e of errors) console.error("  " + e + "\n");
  process.exit(1);
}
console.log(`OK — ${templateCount} templates compile and use their v-for aliases in scope.`);
