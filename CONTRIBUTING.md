# Contributing to dui studio

Studio is WIP. Open an issue describing the document, expected result and smallest reproducible example before a large change. Never attach private ROMs, videos, downloaded game assets, secrets or another project's proprietary resources.

Keep ownership clear: Studio handles editing, project discovery, preview and packaging. DUI owns layout/platform contracts. Project adapters own themes, domain components, shaders, assets and fixture bindings. Do not add demo-specific game code to Studio or DUI.

Follow the source build steps in the README. Use the pinned tools, both lockfiles and `dui-version.json`. Run formatting, `pnpm check`, unit/browser tests, engine tests and locked Cargo tests for the code you change. Renderer changes also need a native GPU fixture check and a final Minecraft comparison. Document unsupported cases instead of substituting an unrelated illustration.

Source edits must preserve comments/bindings and external file changes. Prefer meaningful regression tests for save conflicts, asynchronous document transitions and editor behavior. Do not claim Windows/Linux verification from a macOS run.

Keep generated files out of commits. Do not include `src-tauri/resources`, build output, local adapter manifests, the selected Minecraft JAR or private media. Update notices whenever dependencies change. Contributions are under the project's MIT license. Maintainers review changes before committing and publishing.
