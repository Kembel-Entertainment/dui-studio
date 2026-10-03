package gg.kembel.dui.studio.sdk;

import gg.kembel.dui.core.*;
import gg.kembel.dui.core.video.VideoFrame;
import gg.kembel.dui.core.world.*;
import gg.kembel.dui.pack.PackContribution;
import java.nio.file.Path;
import java.util.*;

/** Project-owned, portable presentation adapter. Never run server business actions in a preview. */
public interface StudioProjectProvider extends AutoCloseable {
  record Context(Path root, Path minecraftJar) {}

  record Fixture(
      String id,
      String label,
      Map<String, Object> data,
      Map<String, RasterImage> images,
      Map<String, String> items,
      Map<String, Appearance> appearances) {
    public Fixture {
      data = Map.copyOf(data);
      images = Map.copyOf(images);
      items = Map.copyOf(items);
      appearances = Map.copyOf(appearances);
    }

    public static Fixture data(String id, String label, Map<String, Object> data) {
      return new Fixture(id, label, data, Map.of(), Map.of(), Map.of());
    }
  }

  record Appearance(Path skin, boolean slim, Map<String, String> armor) {
    public Appearance {
      armor = Map.copyOf(armor);
    }
  }

  record MapPreview(
      WorldMapDefinition definition, WorldMapFrame frame, Map<String, RasterImage> images) {}

  /** The consumer declares how editable local GLSL becomes its contributed shader/module. */
  record SourceFile(String path, String kind, String id, String suffix, String previewDocument) {
    public SourceFile {
      if (!Set.of("shader", "module").contains(kind))
        throw new IllegalArgumentException("Source kind");
      Objects.requireNonNull(path);
      Objects.requireNonNull(id);
      Objects.requireNonNull(suffix);
      Objects.requireNonNull(previewDocument);
    }
  }

  default List<SourceFile> sourceFiles() {
    return List.of();
  }

  default void initialize(Context context) throws Exception {}

  RenderEnvironment environment();

  default ComponentRegistry components() {
    return ComponentRegistry.EMPTY;
  }

  default List<PackContribution> contributions() throws Exception {
    return List.of();
  }

  default List<Fixture> fixtures(String document) {
    return List.of(Fixture.data("default", "Default", Map.of()));
  }

  default String normalize(String source) {
    return source;
  }

  /** Pure presentation sampling. Return additional bindings; do not invoke live server actions. */
  default Map<String, Object> sample(
      String document, Map<String, Object> data, double seconds, boolean playing) {
    return data;
  }

  default Optional<MapPreview> map(String document, Map<String, Object> data) throws Exception {
    return Optional.empty();
  }

  /** Live sources are opt-in, return immutable frames, and release all producers in close(). */
  default Optional<VideoFrame> frame(String document, double seconds, boolean live)
      throws Exception {
    return Optional.empty();
  }

  default void close() {}
}
