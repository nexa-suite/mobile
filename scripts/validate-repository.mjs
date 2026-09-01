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
const policy = readFileSync(join(root, '.github/RELEASE_POLICY.md'), 'utf8');
for (const phrase of ['Semantic Versioning', 'SSH-signed', 'git verify-tag', 'documentation foundations only']) {
  if (!policy.includes(phrase)) failures.push(`release policy missing: ${phrase}`);
}

if (failures.length) {
  console.error(failures.join('\n'));
  process.exit(1);
}

console.log('Mobile repository validation passed: documentation-only, no native build or runtime claim.');
