package gg.kembel.dui.studio.engine;

import com.google.gson.*;
import gg.kembel.dui.core.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/**
 * Only local project assets and a user-selected Minecraft JAR. Nothing is downloaded or
 * redistributed.
 */
final class Assets implements AutoCloseable {
  final Path root;
  final ZipFile vanilla;
  final Map<String, byte[]> contributed = new HashMap<>();
  final Map<String, BufferedImage> pictures = new HashMap<>();
  final Map<Integer, BufferedImage> letters = new HashMap<>();
  final Map<String, Integer> widths = new HashMap<>();
  double seconds;
  final Map<String, JsonObject> animations = new HashMap<>();
  final Set<String> stillTextures = new HashSet<>();

  Assets(Path root, Path jar) throws Exception {
    this.root = root;
    vanilla = jar == null ? null : new ZipFile(jar.toFile());
    if (vanilla != null) {
      var version = vanilla.getEntry("version.json");
      if (version != null
          && !JsonParser.parseString(new String(vanilla.getInputStream(version).readAllBytes()))
              .getAsJsonObject()
              .get("id")
              .getAsString()
              .equals("26.3")) throw new IllegalArgumentException("Select Minecraft 26.3 assets");
      loadFont("assets/minecraft/font/include/default.json");
    }
    widths.put(" ", 4);
  }

  static String resource(String id, String folder, String suffix) {
    String[] p = id.split(":", 2);
    String file = p.length == 2 ? p[1] : p[0];
    return "assets/"
        + (p.length == 2 ? p[0] : "minecraft")
        + "/"
        + folder
        + "/"
        + file
        + (file.endsWith(suffix) ? "" : suffix);
  }

  Path local(String name) throws IOException {
    var p = root.resolve(name).normalize();
    if (!p.startsWith(root) || name.contains("..")) throw new IOException("Asset leaves project");
    if (Files.exists(p) && !p.toRealPath().startsWith(root.toRealPath()))
      throw new IOException("Asset symlink leaves project");
    return p;
  }

  byte[] bytes(String name) throws IOException {
    if (contributed.containsKey(name)) return contributed.get(name);
    Path p = local(name);
    if (Files.isRegularFile(p)) {
      if (Files.size(p) > 32_000_000) throw new IOException("Asset too large");
      return Files.readAllBytes(p);
    }
    if (vanilla != null) {
      var e = vanilla.getEntry(name);
      if (e != null) {
        if (e.getSize() > 32_000_000) throw new IOException("Asset too large");
        try (var in = vanilla.getInputStream(e)) {
          return in.readAllBytes();
        }
      }
    }
    throw new FileNotFoundException(name);
  }

  JsonObject json(String name) throws IOException {
    return JsonParser.parseString(new String(bytes(name), java.nio.charset.StandardCharsets.UTF_8))
        .getAsJsonObject();
  }

  BufferedImage image(String name) throws IOException {
    var known = pictures.get(name);
    if (known != null) return known;
    var image = ImageIO.read(new ByteArrayInputStream(bytes(name)));
    if (image == null || (long) image.getWidth() * image.getHeight() > 16_777_216)
      throw new IOException("Invalid/oversized image " + name);
    if (pictures.size() >= 128) pictures.clear();
    pictures.put(name, image);
    return image;
  }

  BufferedImage texture(String id) throws IOException {
    String name = resource(id, "textures", ".png");
    var full = image(name);
    if (!stillTextures.contains(name) && !animations.containsKey(name)) {
      try {
        animations.put(name, json(name + ".mcmeta").getAsJsonObject("animation"));
      } catch (FileNotFoundException e) {
        stillTextures.add(name);
      }
    }
    var animation = animations.get(name);
    if (animation == null) return full;
    int width = animation.has("width") ? animation.get("width").getAsInt() : full.getWidth(),
        height = animation.has("height") ? animation.get("height").getAsInt() : width,
        count = full.getWidth() / width * (full.getHeight() / height),
        duration = animation.has("frametime") ? animation.get("frametime").getAsInt() : 1;
    var frames = animation.getAsJsonArray("frames");
    var ids = new ArrayList<Integer>();
    var times = new ArrayList<Integer>();
    if (frames == null) {
      for (int i = 0; i < count; i++) {
        ids.add(i);
        times.add(duration);
      }
    } else
      for (var f : frames) {
        ids.add(f.isJsonObject() ? f.getAsJsonObject().get("index").getAsInt() : f.getAsInt());
        times.add(
            f.isJsonObject() && f.getAsJsonObject().has("time")
                ? f.getAsJsonObject().get("time").getAsInt()
                : duration);
      }
    long age = (long) (seconds * 20) % times.stream().mapToInt(Integer::intValue).sum();
    int frame = 0;
    while (age >= times.get(frame)) {
      age -= times.get(frame++);
    }
    int index = ids.get(frame);
    String key = name + "#frame_" + index;
    var existing = pictures.get(key);
    if (existing != null) return existing;
    var result =
        full.getSubimage(
            index % (full.getWidth() / width) * width,
            index / (full.getWidth() / width) * height,
            width,
            height);
    if (pictures.size() >= 128) pictures.clear();
    pictures.put(key, result);
    return result;
  }

  void loadFont(String name) throws Exception {
    for (var element : json(name).getAsJsonArray("providers")) {
      var provider = element.getAsJsonObject();
      if (!provider.get("type").getAsString().equals("bitmap")) continue;
      var image = texture(provider.get("file").getAsString());
      var rows = provider.getAsJsonArray("chars");
      int columns = rows.get(0).getAsString().codePointCount(0, rows.get(0).getAsString().length()),
          cw = image.getWidth() / columns,
          ch = image.getHeight() / rows.size();
      for (int y = 0; y < rows.size(); y++) {
        int[] codes = rows.get(y).getAsString().codePoints().toArray();
        for (int x = 0; x < codes.length; x++) {
          if (codes[x] == 0) continue;
          var letter = image.getSubimage(x * cw, y * ch, cw, ch);
          int right = 0;
          for (int xx = 0; xx < cw; xx++)
            for (int yy = 0; yy < ch; yy++)
              if ((letter.getRGB(xx, yy) >>> 24) > 0) right = Math.max(right, xx + 1);
          letters.put(codes[x], letter);
          widths.put(Character.toString(codes[x]), right + 1);
        }
      }
    }
  }

  GlyphFont font() {
    var result = new HashMap<>(widths);
    for (int cp = 32; cp < 127; cp++) result.putIfAbsent(Character.toString(cp), 6);
    return new GlyphFont(result);
  }

  static BufferedImage raster(RasterImage r) {
    var image = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < r.height; y++)
      for (int x = 0; x < r.width; x++) image.setRGB(x, y, r.argb(x, y));
    return image;
  }

  static RasterImage raster(BufferedImage b) {
    return RasterImage.argb(
        b.getWidth(),
        b.getHeight(),
        b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth()));
  }

  public void close() throws IOException {
    if (vanilla != null) vanilla.close();
    pictures.clear();
  }
}
