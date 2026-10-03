import { invoke, isTauri } from '@tauri-apps/api/core';
import { open, confirm } from '@tauri-apps/plugin-dialog';
import { getCurrentWindow } from '@tauri-apps/api/window';

export type Entry = { path: string; kind: string; size: number };
export type Bounds = { x: number; y: number; width: number; height: number };
export type Preview = {
  revision: number;
  width: number;
  height: number;
  backend: string;
  mapHit?: { id: string; enabled: boolean };
  nodes?: { id: string; parent: string; backend: string; bounds: Bounds; layer: number }[];
  warnings: string[];
  report?: unknown;
  definition?: { defaultZoom: number; maxZoom: number };
};
export type Project = {
  root: string;
  files: Entry[];
  profile: {
    name: string;
    adapter: string;
    gpu: string;
    minecraftAssets: boolean;
    schema: {
      builtins: Record<string, BuiltinSchema>;
      components: Record<string, ComponentContract>;
      shaders: unknown[];
      sources?: { path: string; kind: string; id: string; previewDocument: string }[];
    };
  };
};
export const desktop = isTauri();
export async function chooseDirectory() {
  return (await open({ directory: true, multiple: false, title: 'Open a DUI project' })) as
    string | null;
}
export async function chooseMinecraft() {
  return (await open({
    multiple: false,
    title: 'Select your Minecraft 26.2 client JAR',
    filters: [{ name: 'Minecraft assets', extensions: ['jar'] }],
  })) as string | null;
}
export async function openProject(path: string, minecraftJar?: string): Promise<Project> {
  return invoke('open_project', { path, minecraftJar });
}
export async function readFile(path: string): Promise<{ text: string; hash: string }> {
  return invoke('read_file', { path });
}
export async function listFiles(): Promise<Entry[]> {
  return invoke('list_files');
}
export async function saveFile(
  path: string,
  text: string,
  expectedHash: string | null,
): Promise<{ hash: string }> {
  return invoke('save_file', { path, text, expectedHash });
}
export async function preview(params: unknown): Promise<Preview> {
  return invoke('preview', { method: 'render', params });
}
export async function fixtures(
  document: string,
): Promise<{ id: string; label: string; data: unknown }[]> {
  return invoke('preview', { method: 'fixtures', params: { document } });
}
export async function frame(revision: number): Promise<string> {
  const bytes = await invoke<ArrayBuffer>('preview_frame', { revision });
  return URL.createObjectURL(new Blob([bytes], { type: 'image/png' }));
}
export async function asset(path: string): Promise<string> {
  const bytes = await invoke<ArrayBuffer>('read_asset', { path });
  return URL.createObjectURL(new Blob([bytes]));
}
export async function discardChanges(): Promise<boolean> {
  return confirm('Discard unsaved changes in this file?', {
    title: 'Unsaved document',
    kind: 'warning',
  });
}

export async function documentDirty(value: boolean): Promise<void> {
  if (desktop) await invoke('set_document_dirty', { value });
}

/** Tauri's native close path does not rely on browser beforeunload behavior. */
export async function guardClose(canClose: () => Promise<boolean>): Promise<() => void> {
  if (!desktop) return () => {};
  return getCurrentWindow().onCloseRequested(async (event) => {
    if (!(await canClose())) event.preventDefault();
  });
}

export type BuiltinSchema = {
  attributes: string[];
  defaults: Record<string, string>;
  backend: string;
};
export type ScalarProperty = {
  type: string;
  required: boolean;
  defaultValue?: string;
  min: number;
  max: number;
  choices: string[];
};
export type ComponentContract = {
  scalars: Record<string, ScalarProperty>;
  values: Record<string, { type: string; required: boolean; default: unknown }>;
};
export async function startup(): Promise<{
  project?: string;
  minecraftJar?: string;
  document?: string;
}> {
  return invoke('startup');
}
export async function refreshRenderer(): Promise<Project['profile']> {
  return invoke('preview', { method: 'refresh', params: {} });
}
