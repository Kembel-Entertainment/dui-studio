<script lang="ts">
  import { onMount } from 'svelte';
  import CodeEditor from './lib/CodeEditor.svelte';
  import * as bridge from './lib/bridge';
  import {
    parse,
    flatten,
    attr,
    setAttributes,
    duplicate,
    group,
    apply,
    History,
    type Element,
  } from './lib/document';
  import type { Project, Preview, Bounds } from './lib/bridge';
  import { jsonValue } from './lib/json-document';
  import { componentMarkup } from './lib/components';
  let project = $state<Project | null>(null),
    file = $state(''),
    source = $state(''),
    saved = $state(''),
    hash = $state<string | null>(null);
  let imageUrl = $state(''),
    preview = $state<Preview | null>(null),
    error = $state(''),
    busy = $state(false),
    loading = $state(false),
    saving = $state(false),
    search = $state('');
  let tab = $state('Design'),
    sidebar = $state('Layers'),
    selection = $state<string[]>([]),
    inspector = $state('Properties');
  let options = $state<{ id: string; label: string; data: unknown }[]>([]),
    fixture = $state('default'),
    data = $state('{}');
  let zoom = $state(200),
    grid = $state(true),
    seconds = $state(1.5),
    playing = $state(false),
    yaw = $state(0),
    pitch = $state(0),
    mapZoom = $state(12);
  let viewport = $state('960 × 720'),
    jar = $state(localStorage.getItem('dui-studio.minecraft') ?? ''),
    rootInput = $state(localStorage.getItem('dui-studio.project') ?? '');
  let propertyName = $state(''),
    propertyValue = $state(''),
    component = $state('button'),
    ready = false,
    serial = 0,
    dirtyPreview = false;
  const history = new History();
  const syntax = $derived(/\.(html|xml)$/.test(file) ? parse(source) : { roots: [], errors: [] });
  const elements = $derived(flatten(syntax.roots));
  const selected = $derived(elements.filter((n) => selection.includes(n.path)));
  const changed = $derived(source !== saved);
  const kind = $derived(
    file.endsWith('.json') ? 'json' : /\.(glsl|[vf]sh)$/.test(file) ? 'glsl' : 'xml',
  );
  const shaderBinding = $derived(project?.profile.schema.sources?.find((s) => s.path === file));
  let previewDocument = $state(''),
    previewSource = $state('');
  const editable = $derived(/\.(html|xml|map.json)$/.test(file) || !!shaderBinding);
  const motionAvailable = $derived(
    selected.length > 0 &&
      selected.every((n) => {
        const type = n.tag.replace('dui-', '');
        return (
          project?.profile.schema.builtins[type]?.attributes.includes('motion-duration') ||
          !!project?.profile.schema.components[attr(n, 'name')]?.scalars['motion-duration']
        );
      }),
  );
  const assetFile = $derived(/\.(png|jpe?g|webp|svg)$/.test(file));
  const files = $derived(
    project?.files.filter((f) => f.path.toLowerCase().includes(search.toLowerCase())) ?? [],
  );
  const palette = $derived(
    Object.keys(project?.profile.schema.builtins ?? {})
      .filter(
        (name) =>
          !['menu', 'option', 'repeat', 'if', 'style', 'component', 'outlet', 'content'].includes(
            name,
          ),
      )
      .sort(),
  );
  const customPalette = $derived(Object.keys(project?.profile.schema.components ?? {}).sort());
  let newPath = $state('ui/new-menu.html'),
    mapLayer = $state(0);
  const mapDocument = $derived.by(() => {
    try {
      return file.endsWith('.map.json') ? JSON.parse(source) : null;
    } catch {
      return null;
    }
  });
  function mutate(text: string) {
    if (text === source) return;
    history.push(source);
    source = text;
  }
  async function guarded(work: () => Promise<void>) {
    error = '';
    try {
      await work();
    } catch (e) {
      error = String(e);
    }
  }
  async function openProject(path?: string) {
    if (loading || saving) return;
    await guarded(async () => {
      if (changed && !(await bridge.discardChanges())) return;
      const root = path ?? (await bridge.chooseDirectory());
      if (!root) return;
      busy = true;
      try {
        project = await bridge.openProject(root, jar || undefined);
        rootInput = root;
        localStorage.setItem('dui-studio.project', root);
        ready = true;
        selection = [];
        preview = null;
        file = '';
        source = saved = '';
        await openFile(
          project.files.find((f) => f.kind === 'html')?.path ?? project.files[0]?.path ?? '',
        );
      } finally {
        busy = false;
      }
    });
  }
  async function openFile(path: string) {
    if (!path || path === file || loading || saving) return;
    await guarded(async () => {
      if (changed && !(await bridge.discardChanges())) return;
      loading = true;
      playing = false;
      try {
        if (/\.(png|jpe?g|webp|svg)$/.test(path)) {
          const image = await bridge.asset(path);
          file = path;
          source = saved = '';
          hash = null;
          selection = [];
          preview = null;
          history.past = [];
          history.future = [];
          replaceImage(image);
          return;
        }
        // Read and resolve the fixture before committing the new active document.
        const document = await bridge.readFile(path);
        const shader = project?.profile.schema.sources?.find((s) => s.path === path);
        const nextPreview = shader ? await bridge.readFile(shader.previewDocument) : document;
        const nextOptions = await bridge.fixtures(shader ? shader.previewDocument : path);
        file = path;
        source = saved = document.text;
        hash = document.hash;
        selection = [];
        history.past = [];
        history.future = [];
        mapLayer = 0;
        preview = null;
        replaceImage('');
        previewDocument = shader ? shader.previewDocument : path;
        previewSource = nextPreview.text;
        if (shader) tab = 'Split';
        else if (!/\.(html|xml|map.json)$/.test(path)) tab = 'Code';
        options = nextOptions;
        fixture = options[0]?.id ?? 'default';
        data = '{}';
        dirtyPreview = true;
      } finally {
        loading = false;
      }
    });
  }
  function replaceImage(next: string) {
    const old = imageUrl;
    imageUrl = next;
    if (old) URL.revokeObjectURL(old);
  }
  async function save() {
    if (!project || !file || assetFile || loading || saving || !changed) return;
    const savingFile = file,
      savingRoot = project.root,
      savingText = source,
      savingHash = hash;
    saving = true;
    await guarded(async () => {
      try {
        const result = await bridge.saveFile(savingFile, savingText, savingHash);
        if (file === savingFile && project?.root === savingRoot) {
          hash = result.hash;
          saved = savingText;
          // Typing during the save leaves the newer edits visibly unsaved.
          project.files = await bridge.listFiles();
        }
      } finally {
        saving = false;
      }
    });
  }
  async function newDocument() {
    if (loading || saving) return;
    if (!newPath.endsWith('.html')) {
      error = 'Use an .html path for a new template.';
      return;
    }
    if (project?.files.some((f) => f.path === newPath)) {
      error = 'That file already exists.';
      return;
    }
    if (changed && !(await bridge.discardChanges())) return;
    file = newPath;
    saved = '';
    hash = null;
    source =
      '<dui-menu width="360" height="180" background="none">\n <dui-layer id="main" height="fill">\n  <dui-rect id="background" width="fill" height="fill" fill="#1B2534"/>\n  <dui-text id="title" x="18" y="18" width="324" height="18" label="A new adventure" color="#D5EDA8"/>\n </dui-layer>\n</dui-menu>\n';
    selection = [];
    options = [{ id: 'default', label: 'Default', data: {} }];
    fixture = 'default';
    data = '{}';
  }
  function mapProperty(key: string, value: string) {
    try {
      const previous = mapDocument.layers[mapLayer][key];
      mutate(
        jsonValue(
          source,
          ['layers', mapLayer, key],
          typeof previous === 'number' ? Number(value) : value,
        ),
      );
    } catch (e) {
      error = String(e);
    }
  }
  async function reload() {
    if (loading || saving) return;
    if (changed && !(await bridge.discardChanges())) return;
    await guarded(async () => {
      const result = await bridge.readFile(file);
      source = saved = result.text;
      hash = result.hash;
      if (project) {
        project.profile = await bridge.refreshRenderer();
        options = await bridge.fixtures(file);
      }
    });
  }
  async function render() {
    if (!ready || !editable || assetFile || !source || busy || loading || !dirtyPreview) return;
    dirtyPreview = false;
    busy = true;
    const id = ++serial;
    const snapshot = JSON.stringify([
      file,
      source,
      fixture,
      data,
      viewport,
      seconds,
      playing,
      yaw,
      pitch,
      mapZoom,
    ]);
    try {
      const [width, height] = viewport.split(' × ').map(Number);
      const result = await bridge.preview({
        document: shaderBinding ? previewDocument : file,
        source: shaderBinding ? previewSource : source,
        sourceOverrides: shaderBinding ? { [file]: source } : {},
        fixture,
        data: JSON.parse(data),
        width,
        height,
        seconds,
        playing,
        yaw,
        pitch,
        zoom: mapZoom,
      });
      const url = await bridge.frame(result.revision);
      if (
        id === serial &&
        snapshot ===
          JSON.stringify([
            file,
            source,
            fixture,
            data,
            viewport,
            seconds,
            playing,
            yaw,
            pitch,
            mapZoom,
          ])
      ) {
        preview = result;
        replaceImage(url);
        error = '';
      } else URL.revokeObjectURL(url);
    } catch (e) {
      if (
        snapshot ===
        JSON.stringify([
          file,
          source,
          fixture,
          data,
          viewport,
          seconds,
          playing,
          yaw,
          pitch,
          mapZoom,
        ])
      )
        error = String(e);
    } finally {
      busy = false;
    }
  }
  $effect(() => {
    source;
    fixture;
    data;
    seconds;
    playing;
    yaw;
    pitch;
    mapZoom;
    viewport;
    dirtyPreview = true;
  });
  $effect(() => {
    void bridge.documentDirty(changed).catch((e) => {
      error = String(e);
    });
  });
  onMount(() => {
    if (bridge.desktop)
      void guarded(async () => {
        const initial = await bridge.startup();
        if (initial.minecraftJar) {
          jar = initial.minecraftJar;
          localStorage.setItem('dui-studio.minecraft', jar);
        }
        if (initial.project) {
          await openProject(initial.project);
          if (initial.document) await openFile(initial.document);
        }
      });
    let unlistenClose = () => {},
      disposed = false;
    void bridge
      .guardClose(async () => {
        if (saving || loading) return false;
        const confirmed = !changed || (await bridge.discardChanges());
        if (confirmed) await bridge.documentDirty(false);
        return confirmed;
      })
      .then((unlisten) => {
        if (disposed) unlisten();
        else unlistenClose = unlisten;
      })
      .catch((e) => {
        error = String(e);
      });
    const timer = window.setInterval(() => {
      if (playing && !busy && !loading) seconds = +(seconds + 1 / 20).toFixed(3);
      void render();
    }, 50);
    const keys = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key === 'o') {
        event.preventDefault();
        void openProject();
      }
      if ((event.metaKey || event.ctrlKey) && event.key === 's') {
        event.preventDefault();
        void save();
      }
      if (
        (event.metaKey || event.ctrlKey) &&
        event.key === 'z' &&
        !(event.target as HTMLElement).closest('.cm-editor')
      ) {
        event.preventDefault();
        source = event.shiftKey ? history.redo(source) : history.undo(source);
      }
      if ((event.metaKey || event.ctrlKey) && event.key === 'd' && selected.length) {
        event.preventDefault();
        mutate(duplicate(source, selected));
      }
    };
    const unload = (event: BeforeUnloadEvent) => {
      if (changed) event.preventDefault();
    };
    window.addEventListener('keydown', keys);
    window.addEventListener('beforeunload', unload);
    return () => {
      disposed = true;
      unlistenClose();
      clearInterval(timer);
      window.removeEventListener('keydown', keys);
      window.removeEventListener('beforeunload', unload);
      if (imageUrl) URL.revokeObjectURL(imageUrl);
    };
  });
  function select(n: Element, additive = false) {
    selection = additive
      ? selection.includes(n.path)
        ? selection.filter((p) => p !== n.path)
        : [...selection, n.path]
      : [n.path];
  }
  function setProperties(values: Record<string, string | null>) {
    try {
      let text = source;
      for (const n of [...selected].sort((a, b) => b.from - a.from))
        text = setAttributes(text, n, values);
      mutate(text);
      error = '';
    } catch (e) {
      error = String(e);
    }
  }
  function setProperty(name: string, value: string | null) {
    setProperties({ [name]: value });
  }
  function bounds(n: Element): Bounds | undefined {
    const id = attr(n, 'id');
    if (!id || id.includes('{{')) return;
    const nodes = preview?.nodes?.filter((v) => v.parent.split('/').includes(id));
    if (!nodes?.length) return;
    const x = Math.min(...nodes.map((v) => v.bounds.x)),
      y = Math.min(...nodes.map((v) => v.bounds.y));
    return {
      x,
      y,
      width: Math.max(...nodes.map((v) => v.bounds.x + v.bounds.width)) - x,
      height: Math.max(...nodes.map((v) => v.bounds.y + v.bounds.height)) - y,
    };
  }
  function pointer(event: PointerEvent) {
    if (shaderBinding || preview?.backend !== 'dialog') return;
    const r = (event.currentTarget as HTMLElement).getBoundingClientRect();
    const x = ((event.clientX - r.left) * preview.width) / r.width,
      y = ((event.clientY - r.top) * preview.height) / r.height;
    const node = [...elements].reverse().find((n) => {
      const b = bounds(n);
      return b && x >= b.x && y >= b.y && x < b.x + b.width && y < b.y + b.height;
    });
    if (node) {
      select(node, event.shiftKey);
      startDrag(event, node, false);
    } else if (!event.shiftKey) selection = [];
  }
  function startDrag(event: PointerEvent, n: Element, resize: boolean) {
    event.stopPropagation();
    const b = bounds(n);
    if (!b || !preview) return;
    const original = source,
      ratio =
        preview.width /
        (document.querySelector('.artboard')?.getBoundingClientRect().width ?? preview.width),
      startX = event.clientX,
      startY = event.clientY;
    let current = original;
    const move = (e: PointerEvent) => {
      const dx = Math.round((e.clientX - startX) * ratio),
        dy = Math.round((e.clientY - startY) * ratio);
      const values: Record<string, string> = resize
        ? {
            width: String(Math.max(1, b.width + dx)),
            height: String(Math.max(9, Math.round((b.height + dy) / 9) * 9)),
          }
        : { x: String(Number(attr(n, 'x', '0')) + dx), y: String(Number(attr(n, 'y', '0')) + dy) };
      if (
        Object.values(values).some((v) => v === 'NaN') ||
        [attr(n, 'x'), attr(n, 'y'), attr(n, 'width'), attr(n, 'height')].some((v) =>
          v.includes('{{'),
        )
      ) {
        error =
          'This geometry is bound to fixture data. Edit the binding or fixture rather than overwriting it.';
        return;
      }
      current = setAttributes(original, n, values);
      source = current;
    };
    const end = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', end);
      if (current !== original) history.push(original);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', end, { once: true });
  }
  function align(axis: 'x' | 'y') {
    const boxes = selected.map((n) => ({ n, b: bounds(n) })).filter((v) => v.b);
    if (boxes.length < 2) return;
    const edge = Math.min(...boxes.map((v) => v.b![axis]));
    let text = source;
    for (const { n, b } of boxes.sort((a, b) => b.n.from - a.n.from)) {
      if (attr(n, axis).includes('{{')) {
        error = 'Bound geometry must be edited in fixture data.';
        return;
      }
      text = setAttributes(text, n, {
        [axis]: String(Number(attr(n, axis, '0')) + edge - b![axis]),
      });
    }
    mutate(text);
  }
  function addComponent() {
    const parent = selected[0] ?? syntax.roots[0];
    if (!parent || parent.selfClosing) {
      error = 'Select a container element first.';
      return;
    }
    const id = component.replace(/^dui-/, '') + '_' + Date.now().toString(36);
    mutate(
      apply(source, [
        {
          from: parent.closeFrom,
          to: parent.closeFrom,
          text: `\n  ${componentMarkup(component, id, project!.profile.schema)}\n`,
        },
      ]),
    );
  }
  function groupSelection() {
    try {
      mutate(group(source, selected));
    } catch (e) {
      error = String(e);
    }
  }
  function mapPointer(e: PointerEvent) {
    if (preview?.backend !== 'world' || !e.buttons) return;
    yaw += e.movementX / 6;
    pitch = Math.max(-90, Math.min(90, pitch + e.movementY / 6));
  }
  async function minecraft() {
    await guarded(async () => {
      const path = await bridge.chooseMinecraft();
      if (!path) return;
      jar = path;
      localStorage.setItem('dui-studio.minecraft', path);
      if (project) {
        file = '';
        await openProject(project.root);
      }
    });
  }
</script>

<svelte:head><title>dui studio — Interface workshop</title></svelte:head>

<div class="app">
  <header class="appbar">
    <div class="brand">
      <span class="brand-symbol">d</span><strong>dui<span>studio</span></strong><i>WIP</i>
    </div>
    <div class="crumb">
      {project?.profile.name ?? 'Interface workshop'}<span>/</span>{file
        ? file.split('/').at(-1)
        : 'Start a project'}
    </div>
    <div class="appbar-actions">
      <button
        onclick={minecraft}
        title="Use your local Minecraft 26.3 assets"
        class:configured={!!jar}>◇ Minecraft assets</button
      ><button onclick={() => openProject()} disabled={!bridge.desktop || busy || loading || saving}
        >Open project <kbd>⌘O</kbd></button
      >{#if project}<button
          class="primary"
          onclick={save}
          disabled={!changed || assetFile || loading || saving}
          >{saving ? 'Saving…' : 'Save changes'} <kbd>⌘S</kbd></button
        >{/if}
    </div>
  </header>
  {#if !project}
    <main class="welcome">
      <div class="welcome-mark">d<span>✦</span></div>
      <p class="eyebrow">THE DUI INTERFACE WORKSHOP</p>
      <h1>From a canvas<br />to your world.</h1>
      <p class="welcome-copy">
        Design Minecraft interfaces in their own project.<br />Real DUI layouts. Native model
        previews. Your shaders.
      </p>
      <button
        class="primary large"
        onclick={() => openProject()}
        disabled={!bridge.desktop || busy || loading || saving}
        >{busy ? 'Starting native renderer…' : 'Open a project folder'} <span>↗</span></button
      >
      <div class="welcome-features">
        <div><b>01</b><strong>Dialogs</strong><span>Components, bindings & motion</span></div>
        <div><b>02</b><strong>World surfaces</strong><span>Maps, layers & screen HUDs</span></div>
        <div><b>03</b><strong>Live pixels</strong><span>Video & browser frame sources</span></div>
      </div>
      {#if rootInput && bridge.desktop}<button class="recent" onclick={() => openProject(rootInput)}
          >Continue in {rootInput.split('/').at(-1)} →</button
        >{/if}{#if !bridge.desktop}<p class="muted">
          Run the Tauri desktop app to access projects and the native Java renderer.
        </p>{/if}
    </main>
  {:else}
    <div class="workspace">
      <aside class="left-sidebar">
        <nav class="side-tabs">
          {#each ['Layers', 'Files', 'Components'] as name}<button
              class:active={sidebar === name}
              onclick={() => (sidebar = name)}>{name}</button
            >{/each}
        </nav>
        {#if sidebar === 'Files'}<div class="search">
            <input placeholder="Find a file…" bind:value={search} />
          </div>
          <div class="new-file">
            <input aria-label="New template path" bind:value={newPath} /><button
              onclick={newDocument}>+ New template</button
            >
          </div>
          <div class="file-list">
            {#each files as entry}<button
                class:active={file === entry.path}
                onclick={() => openFile(entry.path)}
                disabled={loading || saving}
                ><span class="file-glyph"
                  >{['html', 'xml'].includes(entry.kind)
                    ? '⌘'
                    : entry.kind === 'json'
                      ? '{}'
                      : '◇'}</span
                ><span title={entry.path}
                  >{entry.path.split('/').at(-1)}<small
                    >{entry.path.split('/').slice(0, -1).join('/')}</small
                  ></span
                ></button
              >{/each}
          </div>
        {:else if sidebar === 'Components'}<div class="section-label">
            DUI COMPONENTS <span>{palette.length}</span>
          </div>
          <p class="sidebar-help">
            Select a container, then insert a component. Project components come from your adapter.
          </p>
          <div class="component-list">
            {#each palette as name}<button
                onclick={() => {
                  component = name;
                  addComponent();
                }}><span>▧</span>{name}<i>+</i></button
              >{/each}
          </div>
          <div class="section-label">PROJECT COMPONENTS <span>{customPalette.length}</span></div>
          <div class="component-list">
            {#each customPalette as name}<button
                onclick={() => {
                  component = name;
                  addComponent();
                }}><span>◇</span>{name}<i>+</i></button
              >{/each}
          </div>
          <div class="section-label">PROJECT CONTRACTS</div>
          <pre class="contract-list">{JSON.stringify(
              project.profile.schema.components,
              null,
              2,
            )}</pre>
        {:else}<div class="section-label">DOCUMENT <span>{elements.length}</span></div>
          <div class="tree">
            {#each elements as n}<button
                class:active={selection.includes(n.path)}
                style:padding-left={`${12 + n.path.split('/').length * 12}px`}
                onclick={(event) => select(n, event.shiftKey)}
                ><span class="tree-glyph">{n.children.length ? '▾' : '◇'}</span><span
                  >{attr(n, 'id') || n.tag.replace('dui-', '')}</span
                >{#if n.attributes.some((a) => a.value.includes('{{'))}<i>↔</i>{/if}</button
              >{/each}
          </div>
          <div class="sidebar-bottom">
            <span class="status-dot"></span>Project owns its design
          </div>{/if}
      </aside>
      <main class="center">
        <div class="documentbar">
          <div class="document-name">
            <span class="file-glyph">⌘</span>{file.split('/').at(-1)}{#if changed}<span
                class="unsaved"
                title="Unsaved changes"
              ></span>{/if}
          </div>
          <div class="segmented">
            {#each ['Design', 'Split', 'Code'] as name}<button
                class:active={tab === name}
                onclick={() => (tab = name)}>{name}</button
              >{/each}
          </div>
          <button class="icon-button" title="Reload from disk" onclick={reload}>↻</button>
        </div>
        <div class="canvas-toolbar">
          <div class="toolgroup">
            <button onclick={() => (source = history.undo(source))} title="Undo">↶</button><button
              onclick={() => (source = history.redo(source))}
              title="Redo">↷</button
            ><span class="separator"></span><button
              onclick={() => mutate(duplicate(source, selected))}
              disabled={!selected.length}
              title="Duplicate selection">⧉</button
            ><button
              onclick={groupSelection}
              disabled={!selected.length}
              title="Group consecutive siblings">▣</button
            ><button onclick={() => align('x')} disabled={selected.length < 2} title="Align left"
              >⇤</button
            ><button onclick={() => align('y')} disabled={selected.length < 2} title="Align top"
              >↥</button
            >
          </div>
          <div class="toolgroup">
            <select aria-label="Preview viewport" bind:value={viewport}
              ><option>960 × 720</option><option>1280 × 720</option><option>1920 × 1080</option
              ><option>640 × 480</option></select
            ><button class:active={grid} onclick={() => (grid = !grid)} title="Pixel grid">▦</button
            ><select aria-label="Canvas zoom" bind:value={zoom}
              ><option value={100}>100%</option><option value={150}>150%</option><option value={200}
                >200%</option
              ><option value={300}>300%</option><option value={400}>400%</option></select
            >
          </div>
        </div>
        <div class="edit-space" class:split={tab === 'Split'}>
          {#if tab !== 'Code'}<div class="canvas-stage" class:grid>
              <div class="canvas-meta">
                <span>{preview?.backend ?? (assetFile ? 'Asset' : 'DUI layout')}</span><span
                  >{preview ? `${preview.width} × ${preview.height}` : 'Native preview'}</span
                >
              </div>
              {#if imageUrl}<div
                  class="artboard"
                  role="presentation"
                  style:width={`${((preview?.width ?? 400) * zoom) / 100}px`}
                  style:aspect-ratio={`${preview?.width ?? 4} / ${preview?.height ?? 3}`}
                  onpointerdown={pointer}
                  onpointermove={mapPointer}
                  onwheel={(event) => {
                    if (preview?.backend === 'world') {
                      event.preventDefault();
                      mapZoom = Math.max(
                        0,
                        Math.min(
                          preview.definition?.maxZoom ?? 31,
                          mapZoom + Math.sign(event.deltaY),
                        ),
                      );
                    }
                  }}
                >
                  <img
                    src={imageUrl}
                    alt="Native DUI project preview"
                    draggable="false"
                  />{#if preview?.backend === 'dialog'}{#each selected as n}{@const b =
                        bounds(n)}{#if b}<div
                          class="selection-box"
                          style:left={`${(b.x / preview.width) * 100}%`}
                          style:top={`${(b.y / preview.height) * 100}%`}
                          style:width={`${(b.width / preview.width) * 100}%`}
                          style:height={`${(b.height / preview.height) * 100}%`}
                        >
                          <span>{attr(n, 'id')}</span><button
                            class="resize-handle"
                            aria-label="Resize selected component"
                            onpointerdown={(e) => startDrag(e, n, true)}
                          ></button>
                        </div>{/if}{/each}{/if}
                </div>{:else}<div class="canvas-empty">
                  <span>▧</span>
                  <p>
                    {busy
                      ? 'Rendering your interface…'
                      : editable
                        ? 'Your native preview appears here.'
                        : 'Edit this project file in the source view.'}
                  </p>
                </div>{/if}
              {#if preview?.backend === 'world'}<div class="map-controls">
                  <label>Yaw <input type="number" bind:value={yaw} /></label><label
                    >Pitch <input type="number" bind:value={pitch} /></label
                  ><label
                    >Zoom <input
                      type="number"
                      min="0"
                      max={preview.definition?.maxZoom ?? 31}
                      bind:value={mapZoom}
                    /></label
                  ><span
                    >{preview.mapHit
                      ? `Target: ${preview.mapHit.id}`
                      : 'Drag to look · Scroll to zoom'}</span
                  >
                </div>{/if}
            </div>{/if}
          {#if tab !== 'Design' && !assetFile}{#key file}<CodeEditor
                value={source}
                {kind}
                onchange={mutate}
              />{/key}{/if}
        </div>
        <div class="timeline">
          <div class="timeline-top">
            <div>
              <span class="section-label">MOTION PREVIEW</span><span class="pill"
                >20 ticks / sec</span
              >
            </div>
            <div class="toolgroup">
              <button
                onclick={() => {
                  seconds = 0;
                  playing = false;
                }}
                title="Reset time">↤</button
              ><button class:active={playing} onclick={() => (playing = !playing)}
                >{playing ? 'Ⅱ Pause' : '▷ Play'}</button
              ><output>{seconds.toFixed(2)}s</output>
            </div>
          </div>
          <div class="timeline-track">
            <input
              aria-label="Animation time"
              type="range"
              min="0"
              max="20"
              step="0.05"
              bind:value={seconds}
            />
            <div class="timeline-labels">
              <span>0s</span><span>5s</span><span>10s</span><span>15s</span><span>20s</span>
            </div>
          </div>
        </div>
      </main>
      <aside class="inspector">
        <nav class="side-tabs">
          {#each ['Properties', 'Data', 'Diagnostics'] as name}<button
              class:active={inspector === name}
              onclick={() => (inspector = name)}>{name}</button
            >{/each}
        </nav>
        {#if inspector === 'Properties'}{#if mapDocument}<div class="section-label">MAP LAYERS</div>
            <div class="fixture-controls">
              <select aria-label="Map layer" bind:value={mapLayer}
                >{#each mapDocument.layers as layer, i}<option value={i}>{layer.id}</option
                  >{/each}</select
              >
            </div>
            <div class="section-label">LAYER GEOMETRY</div>
            <div class="properties">
              {#each Object.entries(mapDocument.layers[mapLayer]) as [key, value]}<label
                  ><span>{key}</span><input
                    value={String(value)}
                    onchange={(event) => mapProperty(key, event.currentTarget.value)}
                  /></label
                >{/each}
            </div>
            <p class="sidebar-help">
              Layer pixels and screen-relative geometry use the project definition. Changes preview
              before saving.
            </p>{:else if selected.length}<div class="inspector-title">
              <span class="component-badge">◇</span><strong
                >{selected.length > 1
                  ? `${selected.length} elements`
                  : selected[0].tag.replace('dui-', '')}</strong
              >
            </div>
            <div class="section-label">ELEMENT ATTRIBUTES</div>
            <div class="properties">
              {#each selected[0].attributes as a}<label
                  ><span
                    >{a.name}{#if a.value.includes('{{')}<i title="Dynamic binding">↔</i>{/if}</span
                  ><input
                    value={a.value}
                    onchange={(event) => setProperty(a.name, event.currentTarget.value)}
                  /></label
                >{/each}
            </div>
            <div class="add-property">
              <input placeholder="Attribute" bind:value={propertyName} /><input
                placeholder={'Value or {{binding}}'}
                bind:value={propertyValue}
              /><button
                onclick={() => {
                  setProperty(propertyName, propertyValue);
                  propertyName = propertyValue = '';
                }}
                disabled={!propertyName}>+ Add attribute</button
              >
            </div>
            {#if motionAvailable}<div class="section-label">NATIVE MOTION</div>
              <div class="motion-shortcuts">
                <button
                  onclick={() => {
                    setProperties({
                      'translate-y': '24',
                      'motion-duration': '20',
                      easing: 'ease_out',
                    });
                  }}>Slide in</button
                ><button
                  onclick={() => {
                    setProperties({
                      'opacity-from': '0',
                      'opacity-to': '1',
                      'motion-duration': '20',
                    });
                  }}>Fade in</button
                ><button
                  onclick={() => {
                    setProperties({
                      'scale-from': '0.5',
                      'scale-to': '1',
                      'motion-duration': '20',
                    });
                  }}>Scale in</button
                >
              </div>
              <p class="sidebar-help">
                Motion works on native items and effect components. Project animation bindings can
                sample Studio time in the adapter.
              </p>{/if}{:else}<div class="inspector-empty">
              <span>↖</span>
              <h3>Select an element</h3>
              <p>Pick a layer or click a component on the canvas to inspect its properties.</p>
            </div>{/if}
        {:else if inspector === 'Data'}<div class="section-label">PREVIEW FIXTURE</div>
          <div class="fixture-controls">
            <select aria-label="Preview fixture" bind:value={fixture}
              >{#each options as option}<option value={option.id}>{option.label}</option
                >{/each}</select
            >
            <p class="sidebar-help">
              Fixtures supply presentation data. Preview clicks don't execute server business rules.
            </p>
          </div>
          <div class="section-label">DATA OVERRIDES <span>JSON</span></div>
          <div class="data-editor">
            <CodeEditor value={data} kind="json" onchange={(value) => (data = value)} />
          </div>
          <div class="section-label">FIXTURE VALUES</div>
          <pre class="fixture-values">{JSON.stringify(
              options.find((f) => f.id === fixture)?.data,
              null,
              2,
            )}</pre>
        {:else}<div class="section-label">RENDERER</div>
          <dl>
            <dt>GPU</dt>
            <dd>{project.profile.gpu}</dd>
            <dt>Adapter</dt>
            <dd>{project.profile.adapter}</dd>
            <dt>Minecraft assets</dt>
            <dd>{project.profile.minecraftAssets ? 'Local 26.3 JAR' : 'Not configured'}</dd>
          </dl>
          <div class="section-label">BUDGET REPORT</div>
          <pre class="fixture-values">{JSON.stringify(preview?.report ?? {}, null, 2)}</pre>
          {#if syntax.errors.length}<p class="diagnostic warning">
              XML syntax errors at offsets {syntax.errors.join(', ')}
            </p>{/if}{#each preview?.warnings ?? [] as warning}<p class="diagnostic warning">
              {warning}
            </p>{/each}{#if !preview?.warnings?.length}<div class="diagnostic success">
              No preview warnings.
            </div>{/if}{/if}
      </aside>
    </div>
  {/if}
  {#if error}<div class="error-banner" role="alert">
      <span>!</span>
      <p>{error}</p>
      <button onclick={() => (error = '')}>×</button>
    </div>{/if}
  <footer>
    <div>
      <span class="status-dot" class:loading={busy}></span>{busy
        ? 'Rendering'
        : changed
          ? 'Unsaved changes'
          : project
            ? 'Ready'
            : 'Local-first workspace'}
    </div>
    <div>
      {project ? 'Java 25 · OpenGL · DUI core' : 'Tauri + Svelte'}<span>dui studio / 0.1 WIP</span>
    </div>
  </footer>
</div>
