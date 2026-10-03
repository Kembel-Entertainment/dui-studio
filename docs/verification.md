# Verification

Automated layers cover source-preserving edit roundtrips, grouping and duplication, undo/redo, path traversal, ignored runtime folders, asset precedence, alpha roundtrips, motion endpoints and native renderer protocol fixtures. Chromium and WebKit editor tests cover native-image presentation with a simulated IPC transport, property edits, saving conflicts, template/component insertion and map geometry edits. Those tests do not claim to exercise Tauri's real IPC. The separate native engine run tests actual GPU rendering, unsaved GLSL overrides, compile failures and recovery. The native engine check writes review PNGs under ignored `build/preview-checks` and fails on warnings or invalid responses.

```sh
python3 scripts/engine-test.py \
  --project ../dui-demo --minecraft-jar /path/to/26.2-client.jar --all
```

The app must also be reviewed in its native webview: open a project, select Minecraft assets, change a static property, drag and resize a component, undo/redo, save and reload, and cause an external edit to verify the conflict message. Check paused/scrubbed shaders, body rotation/armor, wide/compact fixtures, map pan/zoom and pixel surfaces.

Keep native desktop checks distinct from Minecraft parity. Compare supported fixture screenshots with matching in-game state and geometry before claiming exact fidelity. Windows/Linux artifacts and GPU behavior need platform-specific runs; a successful macOS build does not verify those environments.
