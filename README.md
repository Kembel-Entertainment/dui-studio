# dui studio

**Work in progress (WIP).** A standalone desktop editor for [DUI](https://github.com/Kembel-Entertainment/dui) projects, built with Tauri 2 and Svelte 5. APIs, adapters and preview behavior can change. Check the result in Minecraft before shipping a UI.

Studio opens your project folder, edits its templates and previews them through the actual DUI layout APIs. It saves directly to your original files. It has no proprietary export format and does not generate a Paper plugin or execute gameplay actions.

## Features

- Template/JSON source editing, component contracts, layers and a property inspector.
- Selection, dragging, resizing, grouping, duplication, alignment and undo/redo for static geometry.
- Native OpenGL previews of images, supported Minecraft items, player skins/equipment and project GLSL.
- Fixtures, bindings, diagnostics and a scrubbable animation timeline.
- World-map pan/zoom, HUDs and synthetic pixel-surface previews.
- Atomic saving, external-edit conflict detection and unsaved-document protection.

The consuming project owns its theme, components, shaders, assets and fixtures. Studio handles editing and preview; DUI owns layout and platform contracts. See [adapters](docs/adapters.md), [rendering limits](docs/rendering.md) and [verification](docs/verification.md).

## Run from source

Install **Node 24 or newer**, **pnpm 12.8.1**, **Rust** (the repository pins its toolchain), and **OpenJDK 25**. Set `JAVA_HOME` to that JDK and install the [Tauri prerequisites](https://v2.tauri.app/start/prerequisites/) for your operating system. Native previews need desktop OpenGL 3.3.

```sh
git clone https://github.com/Kembel-Entertainment/dui-studio.git
cd dui-studio
pnpm install --frozen-lockfile
pnpm setup:dui
pnpm desktop
```

`dui-version.json` pins the reviewed DUI 0.3 / Minecraft 26.3 source revision. `setup:dui` downloads that exact revision into ignored `.cache/dui`. For local development, keep a DUI 0.3 checkout beside Studio as `../dui`, or set `DUI_SOURCE` to its path before `setup:dui` and the desktop command; the local source takes precedence. The desktop command automatically builds the Java renderer and local runtime before starting Tauri. Generated resources are ignored by Git.

1. Open a trusted project folder; `examples/starter` works without a custom adapter.
2. Optionally select **your own local Minecraft Java 26.3 client JAR** for fonts and models.
3. Select a document and fixture, edit, then save.

Minecraft assets, consumer projects, ROMs and videos are not included. Java adapters are executable local code: only use adapters you trust. Studio does not upload project files or send telemetry. See [security and local data](SECURITY.md).

This repository currently publishes source only. There are no automated release workflows or supported installer downloads. Cross-platform support is a development target; a successful macOS run does not verify Windows/Linux.

## Save → reload in Minecraft

Your Paper plugin must read the files Studio edits for hot reload. In `dui-demo`, set `templates.source-directory` to the absolute `src/main/resources/ui` directory in your local server config and restart once. Then **Save in Studio → `/dui reload`** updates the templates without rebuilding the plugin.

This is a consumer/plugin feature, not an automatic deploy command in Studio. New pack assets, shader code/registrations and Java component behavior still need the appropriate rebuild. Preview does not verify Minecraft transport or gameplay behavior.

## Development checks

```sh
pnpm format:check
pnpm check
pnpm test
pnpm exec playwright install chromium webkit
pnpm test:browser
pnpm run runtime
./gradlew test -PduiSource=.cache/dui
cargo test --locked --manifest-path src-tauri/Cargo.toml
python3 scripts/engine-test.py
```

The engine check exercises the real GPU protocol against the starter. Add `--project /path/to/project --minecraft-jar /path/to/26.3-client.jar --all` to check consumer fixtures. Browser tests simulate Tauri IPC; native desktop review is separate.

See [contributing](CONTRIBUTING.md). Studio uses the same [MIT license](LICENSE) as DUI and dui-demo; dependencies retain their [own licenses](THIRD_PARTY_NOTICES.md). This is an independent project, not an official Minecraft product.
