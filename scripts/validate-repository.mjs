import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';

const root = process.cwd();
const failures = [];

function walk(directory) {
  const files = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (entry.name === '.git') {
      if (directory !== root) failures.push(`nested Git metadata: ${relative(root, join(directory, entry.name))}`);
      continue;
    }
    const absolute = join(directory, entry.name);
    if (entry.isDirectory()) files.push(...walk(absolute));
    else if (entry.isFile()) files.push(absolute);
  }
  return files;
}

for (const required of [
  'README.md',
  'CHANGELOG.md',
  'docs/releases/README.md',
  '.github/RELEASE_POLICY.md',
  '.github/release-allowed-signers'
]) {
  if (!existsSync(join(root, required))) failures.push(`missing required file: ${required}`);
}

for (const required of [
  'settings.gradle.kts',
  'build.gradle.kts',
  'gradle/wrapper/gradle-wrapper.jar',
  'gradlew',
  'operations/build.gradle.kts',
  'operations/src/main/AndroidManifest.xml',
  'feature/access/build.gradle.kts'
]) {
  if (!existsSync(join(root, required))) failures.push(`missing native preview file: ${required}`);
}

const allFiles = walk(root);
for (const file of allFiles.filter(file => file.endsWith('.md'))) {
  const text = readFileSync(file, 'utf8');
  const name = relative(root, file);
  const lines = text.split('\n');
  if (!text.endsWith('\n')) failures.push(`missing final newline: ${name}`);
  if (lines.some(line => /[ \t]+$/.test(line))) failures.push(`trailing whitespace: ${name}`);
  if ((text.match(/^```/gm) || []).length % 2 !== 0) failures.push(`unbalanced code fence: ${name}`);
  for (const line of lines) {
    if (line.includes('](') && !/\[[^\]]+\]\([^\s)]+\)/.test(line)) failures.push(`malformed Markdown link: ${name}`);
  }
}

const tracked = execFileSync('git', ['ls-files'], { encoding: 'utf8' }).trim().split('\n').filter(Boolean);
const generatedPattern = /(^|\/)(node_modules|dist|target|coverage|playwright-report|test-results)(\/|$)|(^|\/)([^/]+\.(log|patch))$/;
for (const file of tracked) if (generatedPattern.test(file)) failures.push(`generated artifact tracked: ${file}`);
const secretPattern = /-----BEGIN (?:RSA|EC|OPENSSH|PRIVATE) KEY-----|(?:ghp_|github_pat_|sk_live_|AKIA)[A-Za-z0-9_/-]{12,}/;
for (const file of tracked) {
  const contents = readFileSync(join(root, file), 'utf8');
  if (secretPattern.test(contents)) failures.push(`credential-like material tracked: ${file}`);
}
const policy = readFileSync(join(root, '.github/RELEASE_POLICY.md'), 'utf8');
for (const phrase of ['Semantic Versioning', 'SSH-signed', 'git verify-tag', 'documentation foundations only']) {
  if (!policy.includes(phrase)) failures.push(`release policy missing: ${phrase}`);
}

if (failures.length) {
  console.error(failures.join('\n'));
  process.exit(1);
}

console.log('Mobile repository validation passed: native preview source and repository hygiene checks passed; build and runtime evidence remain separate gates.');
