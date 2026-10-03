# Project adapters

Studio owns editing and preview infrastructure. DUI owns layout, component contracts and transport-neutral projections. The consuming project owns theme, widgets, shader bodies, image generation, fixtures and presentation sampling. The engine contains no references to demo classes or game rules.

Declare an adapter in `dui.studio.json`:

```json
{
  "schemaVersion": 1,
  "name": "My project",
  "adapter": {
    "class": "example.studio.MyProvider",
    "classpath": ["build/studio/my-provider.jar", "build/studio/my-ui.jar"]
  }
}
```

Classpath entries must be files within the selected project. To keep generated dependency paths out of Git, the tracked file can instead declare `"localProfile": "dui.studio.local.json"`. Build that ignored file with the same adapter object. Studio never runs Gradle, Maven, shell scripts, resource-pack downloads or network requests on opening a project.

Implement `gg.kembel.dui.studio.sdk.StudioProjectProvider` from `studio-sdk`. Develop with a Gradle composite build of Studio. Publish/install the SDK to your Maven repository for use without a composite. Its dependency is `gg.kembel.dui.studio:studio-sdk:0.1.0-SNAPSHOT`.

```java
public final class MyProvider implements StudioProjectProvider {
  public RenderEnvironment environment() { return MyWidgets.environment(); }
  public ComponentRegistry components() { return MyComponents.registry(); }
  public List<PackContribution> contributions() { return MyPack.contributions(); }
  public List<Fixture> fixtures(String document) {
    return List.of(Fixture.data("welcome", "Welcome", Map.of("name", "Explorer")));
  }
}
```

Use the same public `RenderEnvironment`, `ComponentRegistry` and `PackContribution` factories as the runtime. Parent-first loading keeps one copy of DUI and the SDK in the engine. Do not ship competing DUI versions in an adapter.

Fixtures contain data, raster images, native item identifier bindings and appearances. An appearance supplies a project-local skin PNG, classic/slim geometry and armor equipment identifiers keyed by `head`, `chest`, `legs` and `feet`. Native item identifiers name Minecraft or contributed item JSON resources. `skin:appearance_binding` supplies a native profile head. Keep gameplay side effects out of fixtures.

`sample(document, data, seconds, playing)` computes presentation bindings at a preview time. Use public `AnimationTrack.sample` or `AnimationTimeline` values; immutable sample data may replace fields such as opening progress. Pausing stops time; it must not settle a wager, modify inventory or save gameplay state.

`map(document, data)` returns a `WorldMapDefinition`, `WorldMapFrame` and image dictionary. A `.map.json` file directly serializes the public definition; unsaved geometry overrides are used during preview. `frame(document, seconds, playing)` returns immutable `VideoFrame` samples for `dui-video` templates. Start live producers only by deliberate adapter opt-in; release processes, threads and buffers in `close()`.

## dui-demo

The `studio-preview` module belongs to `dui-demo`. It uses the real demo View classes, `CasinoComponents`, widget theme and `DemoPackGenerator.contributions`. It supplies a classic skin, item, QR, shader, map and synthetic pixel fixtures without starting Paper, a browser, an emulator or a decoder.

```sh
./gradlew :studio-preview:prepareStudio \
  -PduiSource=../dui -PduiStudioSource=../dui-studio
```

Then open the `dui-demo` folder in Studio. Its map lives in `src/main/resources/worldmap/atlas.map.json`, targets in the neighboring `atlas.targets.json`, and Advent timing in `src/main/resources/animations/advent.timeline.json`. Those files belong to the demo and are read by its runtime too. Build the adapter again after Java or classpath resource changes. GLSL source files are declared by `sourceFiles()` so Studio can preview their saved or unsaved contents immediately, including consumer-provided wrapper suffixes and a sample document. Opening one of those files keeps the native template preview alongside the source and reports the actual shader compiler diagnostics.

## Editable shader sources

A provider can return `SourceFile(path, kind, id, suffix, previewDocument)` values. `kind` is `shader` or `module`; `id` must already be contributed. Paths and preview documents are project-relative. A suffix lets the consumer append its own typed wrapper to an unchanged source file. The engine reads declared files for every render, overlays unsaved text only for those paths, and reuses compiled programs by source content. The editor does not modify shader registration or transport IDs.

The adapter declares the editable relationship; Studio never guesses source ownership from filenames. Templates and GLSL save back to their original files. Adding a new shader registration or changing Java component contracts still requires rebuilding the adapter and using **Reload from disk**.
