import { existsSync, mkdirSync, readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { join, resolve } from 'node:path';
const root = resolve(import.meta.dirname, '..');
const pin = JSON.parse(readFileSync(join(root, 'dui-version.json'), 'utf8'));
const local =
  process.env.DUI_SOURCE ||
  (existsSync(join(root, '../dui/settings.gradle')) ? join(root, '../dui') : null);
if (local) {
  if (!existsSync(join(resolve(local), 'dui-runtime/build.gradle')))
    throw new Error('DUI_SOURCE must point to the DUI 0.3 / Minecraft 26.3 checkout.');
  console.log(`Using local DUI source: ${resolve(local)}.`);
  process.exit(0);
}
if (pin.revision === null)
  throw new Error(
    'The DUI 0.3 / 26.3 port has not been committed and pinned yet. Use a sibling ../dui checkout or set DUI_SOURCE.',
  );
if (
  !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(pin.repository) ||
  !/^[0-9a-f]{40}$/.test(pin.revision)
)
  throw new Error('Invalid pinned DUI repository/revision.');
const source = join(root, '.cache/dui');
function git(args) {
  const result = spawnSync('git', args, { cwd: source, encoding: 'utf8' });
  if (result.status !== 0) throw new Error(result.stderr || result.error || 'Git failed');
  return result.stdout.trim();
}
mkdirSync(source, { recursive: true });
if (!existsSync(join(source, '.git'))) {
  git(['init']);
  git(['remote', 'add', 'origin', `https://github.com/${pin.repository}.git`]);
}
if (git(['remote', 'get-url', 'origin']) !== `https://github.com/${pin.repository}.git`)
  throw new Error('Unexpected managed DUI origin; refusing to alter this checkout.');
if (git(['status', '--porcelain']))
  throw new Error('Managed DUI checkout has edits; use DUI_SOURCE for custom development.');
const head = spawnSync('git', ['rev-parse', 'HEAD'], { cwd: source, encoding: 'utf8' });
if (head.status !== 0 || head.stdout.trim() !== pin.revision) {
  git(['fetch', '--depth', '1', 'origin', pin.revision]);
  git(['checkout', '--detach', pin.revision]);
}
console.log(`DUI dependency ready at pinned revision ${pin.revision}.`);
