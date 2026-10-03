import { spawnSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, readdirSync, lstatSync, chmodSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');
const javaHome = process.env.JAVA_HOME;
if (!javaHome) throw new Error('Set JAVA_HOME to an OpenJDK 25 installation.');
const extension = process.platform === 'win32' ? '.exe' : '';
function run(command, args) {
  const result = spawnSync(command, args, { cwd: root, stdio: 'inherit' });
  if (result.status !== 0) throw new Error(`${command} failed (${result.status ?? result.error}).`);
}
const java = join(javaHome, 'bin/java' + extension);
const javaVersion = spawnSync(java, ['-version'], { encoding: 'utf8' });
if (
  javaVersion.status !== 0 ||
  !/version "25(?:\.|\")/.test(javaVersion.stderr + javaVersion.stdout)
)
  throw new Error('JAVA_HOME must point to an OpenJDK 25 installation.');
const source = resolve(
  process.env.DUI_SOURCE ||
    (existsSync(join(root, '../dui/settings.gradle'))
      ? join(root, '../dui')
      : join(root, '.cache/dui')),
);
if (!existsSync(join(source, 'settings.gradle'))) {
  if (process.env.DUI_SOURCE) throw new Error('DUI_SOURCE must point to a DUI checkout.');
  run(process.execPath, [join(root, 'scripts/fetch-dui.mjs')]);
}
// Calling the wrapper through Java avoids shell/path interpolation on Windows.
run(java, [
  '-classpath',
  join(root, 'gradle/wrapper/gradle-wrapper.jar'),
  'org.gradle.wrapper.GradleWrapperMain',
  ':studio-engine:engineJar',
  `-PduiSource=${source}`,
  '--console=plain',
]);
const resources = join(root, 'src-tauri/resources');
mkdirSync(resources, { recursive: true });
cpSync(join(root, 'studio-engine/build/libs/studio-engine.jar'), join(resources, 'engine.jar'));
const runtime = join(resources, 'jre');
if (existsSync(runtime)) rmSync(runtime, { recursive: true });
run(join(javaHome, 'bin/jlink' + extension), [
  '--add-modules',
  'java.base,java.desktop,java.xml,java.logging,java.net.http,java.management,java.sql,jdk.unsupported,jdk.crypto.ec,jdk.zipfs',
  '--strip-debug',
  '--no-header-files',
  '--no-man-pages',
  '--output',
  runtime,
]);
// Tauri recopies resources on incremental builds; jlink makes some files read-only.
function writable(path) {
  for (const name of readdirSync(path)) {
    const p = join(path, name),
      s = lstatSync(p);
    if (s.isSymbolicLink()) continue;
    if (s.isDirectory()) writable(p);
    else chmodSync(p, s.mode | 0o200);
  }
}
writable(runtime);
console.log('Prepared the local Java renderer/runtime. Minecraft assets are not bundled.');
