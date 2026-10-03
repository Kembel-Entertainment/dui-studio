package gg.kembel.dui.studio.engine;

import com.google.gson.*;
import gg.kembel.dui.core.*;
import gg.kembel.dui.core.Canvas;
import gg.kembel.dui.core.video.*;
import gg.kembel.dui.core.world.*;
import gg.kembel.dui.pack.*;
import gg.kembel.dui.studio.sdk.StudioProjectProvider;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Framed protocol over stdio; the native OpenGL context stays on the Java main thread. */
public final class Main implements AutoCloseable {
  static final Gson JSON = new Gson();
  Assets assets;
  Gpu gpu;
  StudioProjectProvider provider;
  URLClassLoader loader;
  NativeModels models;
  final Map<String, PackContribution.Shader> shaders = new HashMap<>();
  final Map<String, String> modules = new TreeMap<>();
  final Map<String, PlayerRenderSpec> players = new HashMap<>();
  final Map<String, RasterImage> images = new HashMap<>();
  final List<String> warnings = new ArrayList<>();
  long revision;
  BufferedImage resultImage;

  public static void main(String[] args) throws Exception {
    var protocol = new DataOutputStream(new BufferedOutputStream(System.out));
    System.setOut(System.err);
    try (var engine = new Main();
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = input.readLine()) != null) {
        var response = new JsonObject();
        byte[] image = new byte[0];
        try {
          if (line.length() > 2_000_000)
            throw new IllegalArgumentException("Request exceeds budget");
          var request = JsonParser.parseString(line).getAsJsonObject();
          response.add("id", request.get("id"));
          engine.resultImage = null;
          var result =
              engine.request(
                  request.get("method").getAsString(), request.getAsJsonObject("params"));
          response.add("result", JSON.toJsonTree(result));
          if (engine.resultImage != null) {
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(engine.resultImage, "png", bytes);
            image = bytes.toByteArray();
          }
        } catch (Exception error) {
          response.addProperty(
              "error", error.getClass().getSimpleName() + ": " + error.getMessage());
          error.printStackTrace(System.err);
        }
        var json = JSON.toJson(response).getBytes(StandardCharsets.UTF_8);
        protocol.writeInt(json.length);
        protocol.write(json);
        protocol.writeInt(image.length);
        protocol.write(image);
        protocol.flush();
      }
    }
  }

  Object request(String method, JsonObject p) throws Exception {
    return switch (method) {
      case "open" ->
          open(
              Path.of(p.get("root").getAsString()),
              p.has("minecraftJar") && !p.get("minecraftJar").isJsonNull()
                  ? Path.of(p.get("minecraftJar").getAsString())
                  : null);
      case "describe" -> describe();
      case "fixtures" ->
          provider.fixtures(string(p, "document", "")).stream()
              .map(f -> Map.of("id", f.id(), "label", f.label(), "data", f.data()))
              .toList();
      case "refresh" -> {
        Path root = assets.root;
        Path jar = assets.vanilla == null ? null : Path.of(assets.vanilla.getName());
        yield open(root, jar);
      }
      case "render", "live" -> render(p);
      default -> throw new IllegalArgumentException("Unknown operation " + method);
    };
  }

  Object open(Path root, Path jar) throws Exception {
    close();
    warnings.clear();
    shaders.clear();
    modules.clear();
    players.clear();
    images.clear();
    assets = new Assets(root.toRealPath(), jar);
    var config =
        Files.exists(root.resolve("dui.studio.json"))
            ? assets.json("dui.studio.json")
            : new JsonObject();
    if (config.has("localProfile")) {
      var local = assets.json(config.get("localProfile").getAsString());
      local.entrySet().forEach(e -> config.add(e.getKey(), e.getValue()));
    }
    if (config.has("adapter")) {
      var adapter = config.getAsJsonObject("adapter");
      var paths = new ArrayList<URL>();
      for (var entry : adapter.getAsJsonArray("classpath")) {
        Path path = assets.local(entry.getAsString());
        if (!Files.isRegularFile(path))
          throw new IllegalArgumentException("Build your Studio adapter first: " + path);
        paths.add(path.toUri().toURL());
      }
      loader =
          new URLClassLoader(
              paths.toArray(URL[]::new), StudioProjectProvider.class.getClassLoader());
      provider =
          (StudioProjectProvider)
              Class.forName(adapter.get("class").getAsString(), true, loader)
                  .getConstructor()
                  .newInstance();
      provider.initialize(new StudioProjectProvider.Context(assets.root, jar));
    } else provider = new DefaultProject(assets);
    for (var contribution : provider.contributions()) {
      assets.contributed.putAll(contribution.resources());
      contribution
          .shaders()
          .forEach(
              s -> {
                if (shaders.putIfAbsent(s.specification().id(), s) != null)
                  throw new IllegalArgumentException("Duplicate shader " + s.specification().id());
              });
      modules.putAll(contribution.shaderModules());
      contribution.playerRenderers().forEach(s -> players.put(s.id(), s));
      for (var glyph : contribution.glyphs()) {
        var image = ImageIO.read(new ByteArrayInputStream(glyph.png()));
        images.put(glyph.specification().id(), Assets.raster(image));
      }
    }
    players.putIfAbsent("dui:player", PlayerRenderSpec.standard());
    gpu = new Gpu();
    models = new NativeModels(assets, gpu);
    var profile = new LinkedHashMap<String, Object>();
    profile.put("name", string(config, "name", root.getFileName().toString()));
    profile.put(
        "adapter",
        provider instanceof DefaultProject
            ? "Studio preview skin (configure a project adapter for your design)"
            : provider.getClass().getName());
    profile.put("minecraftAssets", jar != null);
    profile.put("gpu", gpu.renderer);
    profile.put("schema", describe());
    return profile;
  }

  Object describe() {
    return Map.of(
        "builtins",
        ComponentSchemas.all(),
        "components",
        provider.components().describe(),
        "shaders",
        shaders.values().stream().map(s -> s.specification()).toList(),
        "playerRenderers",
        players.values(),
        "sources",
        provider.sourceFiles());
  }

  void refreshSources(JsonObject overrides) throws Exception {
    var known = new HashSet<String>();
    for (var binding : provider.sourceFiles()) {
      known.add(binding.path());
      String source =
          overrides.has(binding.path())
              ? overrides.get(binding.path()).getAsString()
              : Files.readString(assets.local(binding.path()));
      if (source.length() > 32768)
        throw new IllegalArgumentException("Shader source budget: " + binding.path());
      if (binding.kind().equals("module")) {
        if (!modules.containsKey(binding.id()))
          throw new IllegalArgumentException("Unknown module " + binding.id());
        modules.put(binding.id(), source + binding.suffix());
      } else {
        var original =
            Objects.requireNonNull(shaders.get(binding.id()), "Unknown shader " + binding.id());
        shaders.put(
            binding.id(),
            new PackContribution.Shader(
                original.specification(), original.function(), source + binding.suffix()));
      }
    }
    for (String path : overrides.keySet())
      if (!known.contains(path))
        throw new IllegalArgumentException("Undeclared shader source " + path);
    for (var binding : provider.sourceFiles())
      if (binding.kind().equals("shader") && overrides.has(binding.path())) {
        var shader = shaders.get(binding.id());
        var values = new LinkedHashMap<String, Object>();
        for (var parameter : shader.specification().parameters())
          values.put(parameter.name(), parameter.decode(0));
        try {
          gpu.effect(
              shader,
              modules,
              new ShaderInvocation(
                  "studio_compile_check", shader.specification(), 0, 0, 18, 18, values, 0),
              0,
              false);
        } catch (Exception error) {
          warnings.add("Shader source " + binding.path() + ": " + error.getMessage());
        }
      }
  }

  Map<String, Object> render(JsonObject p) throws Exception {
    warnings.clear();
    String document = string(p, "document", "");
    String source = string(p, "source", "");
    String fixtureId = string(p, "fixture", "default");
    refreshSources(
        p.has("sourceOverrides") ? p.getAsJsonObject("sourceOverrides") : new JsonObject());
    var fixtures = provider.fixtures(document);
    var fixture =
        fixtures.stream()
            .filter(f -> f.id().equals(fixtureId))
            .findFirst()
            .orElseGet(
                () ->
                    fixtures.isEmpty()
                        ? StudioProjectProvider.Fixture.data("default", "Default", Map.of())
                        : fixtures.getFirst());
    var data = new LinkedHashMap<String, Object>(fixture.data());
    if (p.has("data")) data.putAll(JSON.fromJson(p.get("data"), Map.class));
    double seconds = number(p, "seconds", 0);
    data.put("studio", Map.of("seconds", seconds, "ticks", seconds * 20));
    assets.seconds = seconds;
    var rasters = new HashMap<>(images);
    rasters.putAll(fixture.images());
    boolean live = p.has("playing") && p.get("playing").getAsBoolean();
    data.putAll(provider.sample(document, data, seconds, live));
    int width = (int) number(p, "width", 960), height = (int) number(p, "height", 720);
    if (width < 120 || height < 90 || width > 2048 || height > 1152)
      throw new IllegalArgumentException("Preview viewport budget");
    var map = provider.map(document, data);
    var metadata = new LinkedHashMap<String, Object>();
    if (source.stripLeading().startsWith("{") && document.endsWith(".map.json")) {
      var definition = JSON.fromJson(source, WorldMapDefinition.class);
      var allowed = definition.layers().stream().map(WorldMapDefinition.Layer::id).toList();
      var visible =
          map.map(m -> m.frame().visibleLayers().stream().filter(allowed::contains).toList())
              .orElse(allowed);
      var frame =
          new WorldMapFrame(
              "preview",
              visible,
              map.map(
                      m ->
                          m.frame().regions().stream()
                              .filter(r -> visible.contains(r.layer()))
                              .toList())
                  .orElse(List.of()),
              map.map(m -> m.frame().hud()).orElse(WorldHud.EMPTY));
      resultImage =
          map(
              new StudioProjectProvider.MapPreview(
                  definition,
                  frame,
                  map.map(StudioProjectProvider.MapPreview::images).orElse(rasters)),
              p,
              width,
              height,
              seconds);
      metadata.put("backend", "world");
      metadata.put("definition", definition);
      mapMetadata(metadata, definition, frame, p);
    } else if (map.isPresent()) {
      resultImage = map(map.get(), p, width, height, seconds);
      metadata.put("backend", "world");
      metadata.put("definition", map.get().definition());
      mapMetadata(metadata, map.get().definition(), map.get().frame(), p);
    } else if (source.contains("<dui-video")) {
      var presentation =
          VideoSurfaceTemplate.parse(
                  provider.normalize(source),
                  provider.environment(),
                  provider.components(),
                  document)
              .render(data);
      resultImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
      var g = graphics(resultImage);
      g.setColor(new Color(presentation.specification().backgroundRgb()));
      g.fillRect(0, 0, width, height);
      var frame = provider.frame(document, seconds, live);
      if (frame.isPresent()) {
        var f = frame.get();
        var image = new BufferedImage(f.width(), f.height(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < f.height(); y++)
          for (int x = 0; x < f.width(); x++) {
            int rgb = f.pixel(x, y);
            if (f.format() == PixelFormat.BGR555)
              rgb =
                  ((rgb & 31) * 255 / 31 << 16)
                      | ((rgb >> 5 & 31) * 255 / 31 << 8)
                      | ((rgb >> 10 & 31) * 255 / 31);
            image.setRGB(x, y, rgb);
          }
        var v = presentation.specification().viewport();
        int vw = (int) ((v.right() - v.left()) * width),
            vh = (int) ((v.bottom() - v.top()) * height);
        double scale = Math.min(vw / (double) image.getWidth(), vh / (double) image.getHeight());
        if (presentation.specification().integerScaling() && scale >= 1) scale = Math.floor(scale);
        int w = (int) (image.getWidth() * scale), h = (int) (image.getHeight() * scale);
        g.drawImage(
            image,
            (int) (v.left() * width) + (vw - w) / 2,
            (int) (v.top() * height) + (vh - h) / 2,
            w,
            h,
            null);
      } else
        warnings.add(
            "No live source in this fixture. Supply immutable VideoFrame samples in the project"
                + " adapter.");
      hud(g, presentation.hud(), width, height);
      g.dispose();
      metadata.put("backend", "pixels");
      metadata.put("specification", presentation.specification());
    } else if (source.contains("<dui-hud")) {
      var hud =
          WorldHudTemplate.parse(
                  provider.normalize(source),
                  provider.environment(),
                  provider.components(),
                  document)
              .render(data, rasters, provider.environment().tokens());
      resultImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
      var g = graphics(resultImage);
      hud(g, hud, width, height);
      g.dispose();
      metadata.put("backend", "hud");
    } else {
      Canvas canvas =
          MenuTemplate.parse(
                  provider.normalize(source),
                  provider.environment(),
                  provider.components(),
                  document)
              .render(data, rasters);
      var scene = Scene.of(canvas.renderPlan());
      resultImage = canvas(canvas, scene, fixture, seconds, live);
      metadata.put("backend", "dialog");
      metadata.put(
          "nodes",
          scene.nodes().stream()
              .map(
                  n ->
                      Map.of(
                          "id",
                          n.id(),
                          "parent",
                          n.parent(),
                          "backend",
                          n.backend(),
                          "bounds",
                          n.canvasBounds(),
                          "layer",
                          n.layer()))
              .toList());
      metadata.put("hits", scene.hits());
      metadata.put("report", RenderReport.of(canvas));
    }
    metadata.put("revision", ++revision);
    metadata.put("width", resultImage.getWidth());
    metadata.put("height", resultImage.getHeight());
    metadata.put("warnings", List.copyOf(warnings));
    return metadata;
  }

  BufferedImage canvas(
      Canvas c, Scene scene, StudioProjectProvider.Fixture fixture, double seconds, boolean live) {
    var target = new BufferedImage(c.width, c.height, BufferedImage.TYPE_INT_ARGB);
    var g = graphics(target);
    for (var image : scene.images()) if (image.background()) image(g, image);
    for (var paint : scene.paints()) paint(g, paint);
    for (var image : scene.images()) if (!image.background()) image(g, image);
    for (var head : scene.heads())
      try {
        var a = fixture.appearances().get(head.source());
        if (a == null)
          throw new IllegalArgumentException("Missing appearance binding " + head.source());
        var skin =
            assets.image(assets.root.relativize(a.skin().toAbsolutePath().normalize()).toString());
        g.drawImage(skin, head.x(), head.y(), head.x() + 8, head.y() + 8, 8, 8, 16, 16, null);
        if (head.hat())
          g.drawImage(skin, head.x(), head.y(), head.x() + 8, head.y() + 8, 40, 8, 48, 16, null);
      } catch (Exception e) {
        unsupported(g, head.x(), head.y(), 8, 8, e);
      }
    for (var node : scene.playerModels())
      try {
        var a = fixture.appearances().get(node.source());
        if (a == null) throw new IllegalArgumentException("Missing appearance " + node.source());
        var picture =
            models.player(
                a,
                node,
                players.getOrDefault(node.renderer(), PlayerRenderSpec.standard()),
                seconds);
        g.drawImage(
            picture,
            node.x() + (node.width() - picture.getWidth()) / 2,
            node.y() + (node.height() - picture.getHeight()) / 2,
            null);
      } catch (Exception e) {
        unsupported(g, node.x(), node.y(), node.width(), node.height(), e);
      }
    for (var item : scene.items())
      try {
        if (c.animation != null && c.animation.itemId().equals(item.id()) && item.size() == 1)
          continue;
        String id = fixture.items().getOrDefault(item.id(), item.id());
        var picture =
            id.startsWith("skin:")
                ? models.head(fixture.appearances().get(id.substring(5)), item.size())
                : models.item(id, item.size());
        drawMotion(
            g,
            picture,
            item.x(),
            item.y(),
            c.motions.get(item.id()),
            seconds,
            c.clips.get(item.id()));
      } catch (Exception e) {
        unsupported(g, item.x(), item.y(), item.size(), item.size(), e);
      }
    for (var effect : scene.effects())
      try {
        var shader = shaders.get(effect.shader().id());
        if (shader == null)
          throw new IllegalArgumentException("Missing project shader " + effect.shader().id());
        var picture =
            gpu.effect(
                shader,
                modules,
                effect,
                Math.max(0, seconds - c.animationStart / 20.),
                c.motionEnabled);
        drawMotion(
            g, picture, effect.x(), effect.y(), c.effectMotions.get(effect.id()), seconds, null);
      } catch (Exception e) {
        unsupported(g, effect.x(), effect.y(), effect.width(), effect.height(), e);
      }
    if (!c.primitives.isEmpty())
      warnings.add(
          "Custom render primitives require a Studio adapter projection; these are not simulated.");
    g.dispose();
    return target;
  }

  void unsupported(Graphics2D g, int x, int y, int w, int h, Exception e) {
    warnings.add(e.getMessage());
    g.setColor(new Color(0xc95073));
    g.drawRect(x, y, w - 1, h - 1);
    g.drawLine(x, y, x + w - 1, y + h - 1);
  }

  static Graphics2D graphics(BufferedImage image) {
    var g = image.createGraphics();
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
    return g;
  }

  void image(Graphics2D g, Canvas.Image image) {
    g.drawImage(
        Assets.raster(image.raster()), image.x(), image.y(), image.width(), image.height(), null);
  }

  void paint(Graphics2D g, Canvas.Paint p) {
    if (p.text() != null) {
      int x = p.x();
      for (int cp : p.text().codePoints().toArray()) {
        var letter = assets.letters.get(cp);
        if (letter != null) {
          var colored =
              new BufferedImage(letter.getWidth(), letter.getHeight(), BufferedImage.TYPE_INT_ARGB);
          for (int y = 0; y < letter.getHeight(); y++)
            for (int xx = 0; xx < letter.getWidth(); xx++) {
              int argb = letter.getRGB(xx, y);
              colored.setRGB(
                  xx, y, argb & 0xff000000 | NativeModels.shade(p.color(), (argb & 255) / 255f));
            }
          g.drawImage(colored, x, p.y(), null);
        } else {
          g.setColor(new Color(p.color()));
          g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
          g.drawString(Character.toString(cp), x, p.y() + 7);
          if (assets.vanilla == null
              && !warnings.contains(
                  "Select a local Minecraft 26.2 JAR for native font and item fidelity."))
            warnings.add("Select a local Minecraft 26.2 JAR for native font and item fidelity.");
        }
        x += provider.environment().font().width(Character.toString(cp));
      }
    } else if (p.icon() != null) {
      var raster = images.get(p.icon());
      if (raster != null) g.drawImage(Assets.raster(raster), p.x(), p.y(), null);
      else warnings.add("Missing glyph pixels " + p.icon());
    } else {
      g.setColor(new Color(p.color()));
      g.fillRect(p.x(), p.y(), p.width(), p.height());
    }
  }

  static double ease(double t, Motion.Easing easing) {
    t = Math.clamp(t, 0, 1);
    return switch (easing) {
      case LINEAR -> t;
      case EASE_OUT -> 1 - Math.pow(1 - t, 3);
      case EASE_IN_OUT -> t < .5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
      case BACK_OUT -> 1 + 2.70158 * Math.pow(t - 1, 3) + 1.70158 * Math.pow(t - 1, 2);
    };
  }

  void drawMotion(
      Graphics2D output,
      BufferedImage image,
      int x,
      int y,
      Motion motion,
      double seconds,
      ItemClip clip) {
    var g = (Graphics2D) output.create();
    if (clip != null) g.clipRect(clip.x(), clip.y(), clip.width(), clip.height());
    if (motion != null) {
      double t =
          motion.enabled()
              ? ease(
                  (seconds * 20 - motion.startedAt() - motion.delay()) / motion.duration(),
                  motion.easing())
              : 1;
      double scale = motion.scaleFrom() + (motion.scaleTo() - motion.scaleFrom()) * t,
          angle = motion.rotateFrom() + (motion.rotateTo() - motion.rotateFrom()) * t,
          opacity =
              Math.clamp(
                  motion.opacityFrom() + (motion.opacityTo() - motion.opacityFrom()) * t, 0, 1);
      double px = image.getWidth() * motion.pivotX(), py = image.getHeight() * motion.pivotY();
      g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) opacity));
      g.translate(x + motion.tx() * (1 - t) + px, y + motion.ty() * (1 - t) + py);
      g.rotate(Math.toRadians(angle));
      g.scale(scale, scale);
      g.translate(-px, -py);
      g.drawImage(image, 0, 0, null);
    } else g.drawImage(image, x, y, null);
    g.dispose();
  }

  void mapMetadata(
      Map<String, Object> metadata,
      WorldMapDefinition definition,
      WorldMapFrame frame,
      JsonObject p) {
    var cursor = WorldMapGeometry.cursor(definition, number(p, "yaw", 0), number(p, "pitch", 0), 0);
    var hit = WorldMapGeometry.hit(definition, frame, cursor);
    metadata.put("cursor", cursor);
    metadata.put("regions", frame.regions());
    if (hit != null) metadata.put("mapHit", hit);
  }

  BufferedImage map(
      StudioProjectProvider.MapPreview map, JsonObject p, int w, int h, double seconds) {
    var d = map.definition();
    var frame = map.frame();
    frame.validate(d);
    int zoom = (int) Math.clamp(number(p, "zoom", d.defaultZoom()), 0, d.maxZoom());
    var cursor = WorldMapGeometry.cursor(d, number(p, "yaw", 0), number(p, "pitch", 0), 0);
    var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    var g = graphics(image);
    double age = seconds * 20, t = ease(age / d.opening().ticks(), d.opening().easing());
    double scale = d.opening().scaleFrom() + (1 - d.opening().scaleFrom()) * t,
        alpha =
            d.opening().opacityFrom() + (d.opening().opacityTo() - d.opening().opacityFrom()) * t;
    for (var layer : d.layers()) {
      if (!frame.visibleLayers().contains(layer.id())) continue;
      try {
        BufferedImage raster =
            map.images().containsKey(layer.image())
                ? Assets.raster(map.images().get(layer.image()))
                : assets.image(layer.image());
        double x, y, lw, lh;
        if (layer.space() == WorldMapDefinition.Space.MAP) {
          var state = frame.layerStates().get(layer.id());
          var point =
              WorldMapGeometry.screen(
                  d,
                  new WorldMapGeometry.Point(
                      state == null ? layer.x() : state.x(), state == null ? layer.y() : state.y()),
                  cursor,
                  zoom,
                  w,
                  h);
          x = point.x();
          y = point.y();
          double projection = h / d.visibleHeight(zoom);
          lw = (state == null ? layer.width() : state.width()) * projection;
          lh = (state == null ? layer.height() : state.height()) * projection;
        } else {
          x = layer.x() * w;
          y = layer.y() * h;
          lw = layer.width() * w;
          lh = layer.height() * h;
        }
        var runtime = frame.layerStates().get(layer.id());
        double opacity =
            (runtime == null ? 1 : runtime.opacity())
                * (layer.opacityMin()
                    + (layer.opacityMax() - layer.opacityMin())
                        * (.5 + .5 * Math.sin(age * layer.pulseRadiansPerTick())));
        g.setComposite(
            AlphaComposite.getInstance(
                AlphaComposite.SRC_OVER, (float) Math.clamp(alpha * opacity, 0, 1)));
        g.drawImage(
            raster,
            (int) (w / 2 + (x - w / 2) * scale - lw * scale / 2),
            (int) (h / 2 + (y - h / 2) * scale - lh * scale / 2),
            (int) (lw * scale),
            (int) (lh * scale),
            null);
      } catch (Exception e) {
        warnings.add("Map layer " + layer.id() + ": " + e.getMessage());
      }
    }
    g.setComposite(AlphaComposite.SrcOver);
    hud(g, frame.hud(), w, h);
    g.setColor(Color.WHITE);
    g.drawLine(w / 2 - 4, h / 2, w / 2 + 4, h / 2);
    g.drawLine(w / 2, h / 2 - 4, w / 2, h / 2 + 4);
    g.dispose();
    return image;
  }

  void hud(Graphics2D output, WorldHud hud, int w, int h) {
    int scale = Math.max(1, (int) Math.min(w / 480., h / 360.));
    for (var s : hud.surfaces()) {
      var g = (Graphics2D) output.create();
      g.translate(
          s.horizontal().ordinal() * w / 2 + s.x(0) * scale,
          s.vertical().ordinal() * h / 2 + s.y(0) * scale);
      g.scale(scale, scale);
      g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, s.opacity() / 255f));
      s.paints().forEach(v -> paint(g, v));
      s.images().forEach(v -> image(g, v));
      g.dispose();
    }
  }

  static String string(JsonObject p, String key, String fallback) {
    return p.has(key) ? p.get(key).getAsString() : fallback;
  }

  static double number(JsonObject p, String key, double fallback) {
    double result = p.has(key) ? p.get(key).getAsDouble() : fallback;
    if (!Double.isFinite(result)) throw new IllegalArgumentException("Nonfinite " + key);
    return result;
  }

  public void close() throws Exception {
    if (provider != null) {
      provider.close();
      provider = null;
    }
    if (loader != null) {
      loader.close();
      loader = null;
    }
    if (gpu != null) {
      gpu.close();
      gpu = null;
    }
    if (assets != null) {
      assets.close();
      assets = null;
    }
  }
}
