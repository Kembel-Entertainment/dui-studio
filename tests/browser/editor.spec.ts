import { test, expect } from '@playwright/test';
import { readFileSync, existsSync } from 'node:fs';

const source = readFileSync('examples/starter/ui/welcome.html', 'utf8');
const nativeImage = 'build/preview-checks/welcome-default.png';
// Real native pixels when available. This test isolates editor/IPC behavior from the GPU process.
const png = existsSync(nativeImage)
  ? readFileSync(nativeImage).toString('base64')
  : 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6X7sAAAAASUVORK5CYII=';

test.beforeEach(async ({ page }) => {
  await page.addInitScript(
    ({ source, png }) => {
      const documents: Record<string, { text: string; hash: string }> = {
        'ui/welcome.html': { text: source, hash: 'initial' },
        'world/atlas.map.json': {
          text: '{"layers":[{"id":"land","x":560,"y":320,"width":1120,"height":640}],"maxZoom":31}',
          hash: 'map-initial',
        },
      };
      const files = () =>
        Object.keys(documents).map((path) => ({
          path,
          kind: path.split('.').at(-1),
          size: documents[path].text.length,
        }));
      const profile = {
        name: 'Studio test project',
        adapter: 'Project provider',
        gpu: 'Protocol test / native image',
        minecraftAssets: true,
        schema: {
          builtins: {
            text: {
              attributes: ['label', 'x', 'y', 'width', 'height'],
              defaults: {},
              backend: 'font',
            },
            rect: {
              attributes: ['fill', 'x', 'y', 'width', 'height'],
              defaults: {},
              backend: 'font',
            },
            layer: { attributes: ['width', 'height'], defaults: {}, backend: 'font' },
          },
          components: {
            'test-card': {
              scalars: { label: { type: 'STRING', required: true, min: 0, max: 0, choices: [] } },
              values: {},
            },
          },
          shaders: [],
        },
      };
      let revision = 0;
      const w = window as unknown as { __TAURI_INTERNALS__: unknown; studioTest: unknown };
      (window as any).isTauri = true;
      w.studioTest = {
        documents,
        delaySave: false,
        failRead: '',
        completeSave: () => {},
        external(path: string, text: string) {
          documents[path] = { text, hash: 'external' };
        },
      };
      w.__TAURI_INTERNALS__ = {
        metadata: { currentWindow: { label: 'main' }, currentWebview: { label: 'main' } },
        transformCallback() {
          return 1;
        },
        unregisterCallback() {},
        async invoke(command: string, args: Record<string, unknown> = {}) {
          if (command === 'plugin:event|listen') return 1;
          if (command === 'plugin:event|unlisten') return;
          if (command === 'set_document_dirty') return;
          if (command === 'startup') return {};
          if (command === 'plugin:dialog|open') return '/project';
          if (command === 'plugin:dialog|confirm') return true;
          if (command === 'open_project') return { root: '/project', files: files(), profile };
          if (command === 'list_files') return files();
          if (command === 'read_file') {
            if ((window as any).studioTest.failRead === args.path)
              throw new Error('Could not read this file');
            return documents[args.path as string];
          }
          if (command === 'save_file') {
            if ((window as any).studioTest.delaySave)
              await new Promise<void>((resolve) => {
                (window as any).studioTest.completeSave = resolve;
              });
            const path = args.path as string;
            if ((documents[path]?.hash ?? null) !== args.expectedHash)
              throw new Error('CONFLICT: The file changed outside Studio. Reload before saving.');
            documents[path] = { text: args.text as string, hash: 'saved-' + Date.now() };
            return { hash: documents[path].hash };
          }
          if (command === 'preview_frame')
            return Uint8Array.from(atob(png), (c) => c.charCodeAt(0)).buffer;
          if (command === 'preview') {
            if (args.method === 'fixtures') return [{ id: 'default', label: 'Default', data: {} }];
            if (args.method === 'refresh') return profile;
            const params = args.params as Record<string, unknown>;
            const map = String(params.document).endsWith('.map.json');
            return {
              revision: ++revision,
              width: map ? 960 : 360,
              height: map ? 720 : 180,
              backend: map ? 'world' : 'dialog',
              definition: { maxZoom: 31 },
              warnings: [],
              nodes: [
                {
                  id: 'title',
                  parent: 'main/title',
                  bounds: { x: 18, y: 18, width: 324, height: 18 },
                },
                {
                  id: 'background',
                  parent: 'main/background',
                  bounds: { x: 0, y: 0, width: 360, height: 180 },
                },
              ],
            };
          }
          throw new Error('Unexpected command ' + command);
        },
      };
    },
    { source, png },
  );
  await page.goto('/');
  await page.getByRole('button', { name: 'Open a project folder' }).click();
  await expect(page.locator('.document-name')).toContainText('welcome.html');
  await expect(page.getByAltText('Native DUI project preview')).toBeVisible();
});

test('visual edit preserves source, undo/redo, and save rejects external conflicts', async ({
  page,
}) => {
  await page.getByRole('button', { name: '◇ title', exact: true }).click();
  const label = page
    .locator('.properties label')
    .filter({ hasText: /^label$/ })
    .locator('input');
  await label.fill('A better title');
  await label.press('Tab');
  await page.getByRole('button', { name: 'Save changes' }).click();
  const saved = await page.evaluate(
    () => (window as any).studioTest.documents['ui/welcome.html'].text,
  );
  expect(saved).toContain('<!-- The Studio preview skin');
  expect(saved).toContain('label="A better title"');
  await label.fill('Another title');
  await label.press('Tab');
  await page.getByTitle('Undo', { exact: true }).click();
  await expect(label).toHaveValue('A better title');
  await page.getByTitle('Redo', { exact: true }).click();
  await expect(label).toHaveValue('Another title');
  await page.evaluate(() =>
    (window as any).studioTest.external('ui/welcome.html', 'external document'),
  );
  await page.getByRole('button', { name: 'Save changes' }).click();
  await expect(page.getByRole('alert')).toContainText('CONFLICT');
  expect(
    await page.evaluate(() => (window as any).studioTest.documents['ui/welcome.html'].text),
  ).toBe('external document');
  await page.screenshot({ path: 'test-results/editor.png' });
});

test('new template and project components are authored in project files', async ({ page }) => {
  await page.getByRole('button', { name: 'Files', exact: true }).click();
  await page.getByRole('textbox', { name: 'New template path' }).fill('ui/fresh.html');
  await page.getByRole('button', { name: '+ New template' }).click();
  await page.getByRole('button', { name: 'Layers', exact: true }).click();
  await page.locator('.tree button').filter({ hasText: 'main' }).click();
  await page.getByRole('button', { name: 'Components', exact: true }).click();
  await page.locator('.component-list button').filter({ hasText: 'test-card' }).click();
  await page.getByRole('button', { name: 'Save changes' }).click();
  const saved = await page.evaluate(
    () => (window as any).studioTest.documents['ui/fresh.html'].text,
  );
  expect(saved).toContain('<dui-component');
  expect(saved).toContain('name="test-card"');
});

test('map layer inspector edits JSON and previews camera controls', async ({ page }) => {
  await page.getByRole('button', { name: 'Files', exact: true }).click();
  await page.locator('.file-list button').filter({ hasText: 'atlas.map.json' }).click();
  await expect(page.getByLabel('Map layer')).toBeVisible();
  const x = page.locator('.properties label').filter({ hasText: /^x$/ }).locator('input');
  await x.fill('600');
  await x.press('Tab');
  await page.getByRole('button', { name: 'Save changes' }).click();
  const saved = await page.evaluate(
    () => (window as any).studioTest.documents['world/atlas.map.json'].text,
  );
  expect(JSON.parse(saved).layers[0].x).toBe(600);
  await expect(page.locator('.map-controls')).toContainText('Drag to look');
});

test('typing during an in-flight save keeps newer edits unsaved', async ({ page }) => {
  await page.getByRole('button', { name: '◇ title', exact: true }).click();
  const label = page
    .locator('.properties label')
    .filter({ hasText: /^label$/ })
    .locator('input');
  await label.fill('First save');
  await label.press('Tab');
  await page.evaluate(() => {
    (window as any).studioTest.delaySave = true;
  });
  await page.getByRole('button', { name: 'Save changes' }).click();
  await expect(page.getByRole('button', { name: 'Saving…' })).toBeDisabled();
  await label.fill('Still unsaved');
  await label.press('Tab');
  await page.evaluate(() => {
    (window as any).studioTest.delaySave = false;
    (window as any).studioTest.completeSave();
  });
  await expect(page.getByRole('button', { name: 'Save changes' })).toBeEnabled();
  const first = await page.evaluate(
    () => (window as any).studioTest.documents['ui/welcome.html'].text,
  );
  expect(first).toContain('label="First save"');
  expect(first).not.toContain('Still unsaved');
  await page.getByRole('button', { name: 'Save changes' }).click();
  await expect(page.getByRole('button', { name: 'Save changes' })).toBeDisabled();
  expect(
    await page.evaluate(() => (window as any).studioTest.documents['ui/welcome.html'].text),
  ).toContain('label="Still unsaved"');
});

test('failed file selection retains the previous active document', async ({ page }) => {
  await page.evaluate(() => {
    (window as any).studioTest.failRead = 'world/atlas.map.json';
  });
  await page.getByRole('button', { name: 'Files', exact: true }).click();
  await page.locator('.file-list button').filter({ hasText: 'atlas.map.json' }).click();
  await expect(page.getByRole('alert')).toContainText('Could not read');
  await expect(page.locator('.document-name')).toContainText('welcome.html');
});
