<script lang="ts">
  import { onMount } from 'svelte';
  import { EditorView, keymap } from '@codemirror/view';
  import { EditorState } from '@codemirror/state';
  import { basicSetup } from 'codemirror';
  import { xml } from '@codemirror/lang-xml';
  import { json } from '@codemirror/lang-json';
  import { javascript } from '@codemirror/lang-javascript';
  let {
    value,
    kind = 'xml',
    onchange,
  }: { value: string; kind?: string; onchange: (value: string) => void } = $props();
  let host: HTMLDivElement;
  let editor: EditorView | undefined;
  onMount(() => {
    editor = new EditorView({
      parent: host,
      state: EditorState.create({
        doc: value,
        extensions: [
          basicSetup,
          kind === 'json' ? json() : kind === 'glsl' ? javascript() : xml(),
          EditorView.theme(
            {
              '&': {
                height: '100%',
                backgroundColor: '#15171c',
                color: '#c7cdd8',
                fontSize: '12px',
              },
              '.cm-content': { fontFamily: 'monospace', padding: '16px 0' },
              '.cm-gutters': { backgroundColor: '#15171c', color: '#5d6472', borderRight: '0' },
              '.cm-activeLine': { backgroundColor: '#ffffff06' },
              '.cm-scroller': { overflow: 'auto' },
            },
            { dark: true },
          ),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) onchange(update.state.doc.toString());
          }),
          keymap.of([{ key: 'Mod-s', run: () => true }]),
        ],
      }),
    });
    return () => editor?.destroy();
  });
  $effect(() => {
    const text = value;
    if (editor && editor.state.doc.toString() !== text)
      editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: text } });
  });
</script>

<div class="code-editor" bind:this={host}></div>

<style>
  .code-editor {
    height: 100%;
    min-height: 0;
    overflow: hidden;
  }
</style>
