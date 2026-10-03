package gg.kembel.dui.studio.engine;

import com.google.gson.*;
import gg.kembel.dui.core.*;
import gg.kembel.dui.studio.sdk.StudioProjectProvider.Appearance;
import java.awt.image.BufferedImage;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Minecraft model JSON and skin UVs become textured cuboids, not pre-rendered item icons. */
final class NativeModels {
  final Assets assets;
  final Gpu gpu;

  NativeModels(Assets assets, Gpu gpu) {
    this.assets = assets;
    this.gpu = gpu;
  }

  static float[] vector(JsonArray a, float[] fallback) {
    if (a == null) return fallback;
    return new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
  }

  JsonObject model(String id, Set<String> seen) throws Exception {
    if (!seen.add(id) || seen.size() > 20)
      throw new IllegalArgumentException("Cyclic model parent " + id);
    var child = assets.json(Assets.resource(id, "models", ".json"));
    var merged = new JsonObject();
    if (child.has("parent")
        && !Set.of(
                "minecraft:builtin/generated",
                "builtin/generated",
                "minecraft:builtin/entity",
                "builtin/entity")
            .contains(child.get("parent").getAsString()))
      merged = model(child.get("parent").getAsString(), seen).deepCopy();
    for (var e : child.entrySet()) {
      if ((e.getKey().equals("textures") || e.getKey().equals("display"))
          && merged.has(e.getKey())) {
        var values = merged.getAsJsonObject(e.getKey());
        e.getValue()
            .getAsJsonObject()
            .entrySet()
            .forEach(v -> values.add(v.getKey(), v.getValue()));
      } else merged.add(e.getKey(), e.getValue());
    }
    return merged;
  }

  String texture(JsonObject textures, String name) {
    Set<String> seen = new HashSet<>();
    while (name.startsWith("#")) {
      if (!seen.add(name) || !textures.has(name.substring(1)))
        throw new IllegalArgumentException("Missing model texture " + name);
      name = textures.get(name.substring(1)).getAsString();
    }
    return name;
  }

  BufferedImage item(String id, int size) throws Exception {
    var descriptor = assets.json(Assets.resource(id, "items", ".json")).getAsJsonObject("model");
    if (descriptor.get("type").getAsString().equals("minecraft:special"))
      return special(descriptor, size);
    if (!descriptor.get("type").getAsString().equals("minecraft:model"))
      throw new IllegalArgumentException(
          "Unsupported native item dispatch: " + descriptor.get("type").getAsString());
    var model = model(descriptor.get("model").getAsString(), new HashSet<>());
    var textures = model.getAsJsonObject("textures");
    var meshes = new ArrayList<Gpu.Mesh>();
    if (!model.has("elements")) {
      if (textures == null || !textures.has("layer0"))
        throw new IllegalArgumentException(
            "Native special model requires a project preview adapter: " + id);
      for (int layer = 0; textures.has("layer" + layer); layer++) {
        var image = assets.texture(texture(textures, "#layer" + layer));
        var vertices = new ArrayList<Float>();
        quad(
            vertices,
            new float[][] {
              {-8, -8, layer * .01f},
              {8, -8, layer * .01f},
              {8, 8, layer * .01f},
              {-8, 8, layer * .01f}
            },
            new float[] {0, 1, 1, 0},
            0xffffff,
            new Matrix4f(),
            0);
        meshes.add(mesh(vertices, image));
      }
      return gpu.mesh(meshes, size, size, new Matrix4f().ortho(-8, 8, -8, 8, -100, 100));
    }
    for (var element : model.getAsJsonArray("elements")) {
      var e = element.getAsJsonObject();
      float[] from = vector(e.getAsJsonArray("from"), null),
          to = vector(e.getAsJsonArray("to"), null);
      var transform = new Matrix4f().translate(-8, -8, -8);
      if (e.has("rotation")) {
        var r = e.getAsJsonObject("rotation");
        var o = vector(r.getAsJsonArray("origin"), new float[] {8, 8, 8});
        float angle = (float) Math.toRadians(r.get("angle").getAsDouble());
        var axis = r.get("axis").getAsString();
        transform
            .translate(o[0], o[1], o[2])
            .rotate(
                angle,
                axis.equals("x") ? 1 : 0,
                axis.equals("y") ? 1 : 0,
                axis.equals("z") ? 1 : 0);
        if (r.has("rescale") && r.get("rescale").getAsBoolean()) {
          float s = 1 / (float) Math.cos(angle);
          transform.scale(
              axis.equals("x") ? 1 : s, axis.equals("y") ? 1 : s, axis.equals("z") ? 1 : s);
        }
        transform.translate(-o[0], -o[1], -o[2]);
      }
      for (var face : e.getAsJsonObject("faces").entrySet()) {
        var f = face.getValue().getAsJsonObject();
        var image = assets.texture(texture(textures, f.get("texture").getAsString()));
        float[] uv;
        if (f.has("uv")) {
          var a = f.getAsJsonArray("uv");
          uv =
              new float[] {
                a.get(0).getAsFloat() / 16,
                a.get(1).getAsFloat() / 16,
                a.get(2).getAsFloat() / 16,
                a.get(3).getAsFloat() / 16
              };
        } else uv = defaultUv(face.getKey(), from, to);
        int tint = 0xffffff;
        if (f.has("tintindex") && descriptor.has("tints")) {
          var t =
              descriptor
                  .getAsJsonArray("tints")
                  .get(f.get("tintindex").getAsInt())
                  .getAsJsonObject();
          String type = t.get("type").getAsString();
          if (type.equals("minecraft:grass")) tint = 0x91bd59;
          else if (type.equals("minecraft:constant")) tint = t.get("value").getAsInt();
          else throw new IllegalArgumentException("Unsupported item tint " + type);
        }
        float shade =
            switch (face.getKey()) {
              case "up" -> 1;
              case "down" -> .5f;
              case "north", "south" -> .8f;
              default -> .6f;
            };
        if ((e.has("shade") && !e.get("shade").getAsBoolean())
            || (model.has("gui_light") && model.get("gui_light").getAsString().equals("front")))
          shade = 1;
        tint = shade(tint, shade);
        var vertices = new ArrayList<Float>();
        quad(
            vertices,
            face(face.getKey(), from, to),
            uv,
            tint,
            transform,
            f.has("rotation") ? f.get("rotation").getAsInt() : 0);
        meshes.add(mesh(vertices, image));
      }
    }
    var camera = new Matrix4f().ortho(-8, 8, -8, 8, -100, 100);
    var view = new Matrix4f();
    if (model.has("display") && model.getAsJsonObject("display").has("gui")) {
      var gui = model.getAsJsonObject("display").getAsJsonObject("gui");
      float[] r = vector(gui.getAsJsonArray("rotation"), new float[3]),
          t = vector(gui.getAsJsonArray("translation"), new float[3]),
          s = vector(gui.getAsJsonArray("scale"), new float[] {1, 1, 1});
      view.translate(t[0], t[1], t[2])
          .rotateY((float) Math.toRadians(r[1]))
          .rotateX((float) Math.toRadians(r[0]))
          .rotateZ((float) Math.toRadians(r[2]))
          .scale(s[0], s[1], s[2]);
    }
    return gpu.mesh(meshes, size, size, camera.mul(view));
  }

  static int shade(int rgb, float s) {
    return (int) ((rgb >> 16 & 255) * s) << 16
        | (int) ((rgb >> 8 & 255) * s) << 8
        | (int) ((rgb & 255) * s);
  }

  /**
   * Vanilla special renderers use local textures; metadata variants remain explicitly unsupported.
   */
  BufferedImage special(JsonObject descriptor, int size) throws Exception {
    var spec = descriptor.getAsJsonObject("model");
    String type = spec.get("type").getAsString();
    if (type.equals("minecraft:banner")) return banner(spec.get("color").getAsString(), size);
    var meshes = new ArrayList<Gpu.Mesh>();
    if (type.equals("minecraft:shulker_box")) {
      String id = spec.get("texture").getAsString();
      int separator = id.indexOf(':');
      String namespace = separator < 0 ? "minecraft" : id.substring(0, separator),
          path = separator < 0 ? id : id.substring(separator + 1);
      var texture = assets.texture(namespace + ":entity/shulker/" + path);
      cuboid(meshes, texture, -8, -8, -8, 16, 8, 16, 0, 28, 0, new Matrix4f());
      cuboid(meshes, texture, -8, 0, -8, 16, 12, 16, 0, 0, 0, new Matrix4f());
      return gpu.mesh(
          meshes,
          size,
          size,
          new Matrix4f()
              .ortho(-13, 13, -13, 13, -100, 100)
              .rotateX((float) Math.toRadians(30))
              .rotateY((float) Math.toRadians(45)));
    }
    if (type.equals("minecraft:decorated_pot")) {
      var side = assets.texture("minecraft:entity/decorated_pot/decorated_pot_side");
      var base = assets.texture("minecraft:entity/decorated_pot/decorated_pot_base");
      var vertices = new ArrayList<Float>();
      for (String face : List.of("north", "south", "east", "west"))
        quad(
            vertices,
            face(face, new float[] {-7, -8, -7}, new float[] {7, 8, 7}),
            new float[] {1 / 16f, 1 / 16f, 15 / 16f, 1},
            0xffffff,
            new Matrix4f(),
            0);
      meshes.add(mesh(vertices, side));
      cuboid(meshes, base, -7, -8, -7, 14, 1, 14, 0, 0, 0, new Matrix4f());
      cuboid(meshes, base, -4, 8, -4, 8, 3, 8, 0, 0, 0, new Matrix4f());
      return gpu.mesh(
          meshes,
          size,
          size,
          new Matrix4f()
              .ortho(-13, 13, -13, 13, -100, 100)
              .rotateX((float) Math.toRadians(30))
              .rotateY((float) Math.toRadians(45)));
    }
    throw new IllegalArgumentException("Unsupported native special renderer: " + type);
  }

  BufferedImage banner(String dye, int size) throws Exception {
    var original = assets.texture("minecraft:entity/banner/banner_base");
    var cloth =
        new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_ARGB);
    int color =
        switch (dye) {
          case "blue" -> 0x3c44aa;
          case "red" -> 0xb02e26;
          case "green" -> 0x5e7c16;
          case "black" -> 0x1d1d21;
          case "yellow" -> 0xfed83d;
          case "white" -> 0xf9fffe;
          default -> throw new IllegalArgumentException("Unsupported banner dye " + dye);
        };
    for (int y = 0; y < cloth.getHeight(); y++)
      for (int x = 0; x < cloth.getWidth(); x++) {
        int pixel = original.getRGB(x, y);
        cloth.setRGB(x, y, pixel & 0xff000000 | shade(color, (pixel & 255) / 255f));
      }
    var meshes = new ArrayList<Gpu.Mesh>();
    cuboid(meshes, cloth, -10, -15, -1, 20, 40, 1, 0, 0, 0, new Matrix4f());
    return gpu.mesh(
        meshes,
        size,
        size,
        new Matrix4f()
            .ortho(-18, 18, -18, 18, -100, 100)
            .rotateX((float) Math.toRadians(15))
            .rotateY((float) Math.toRadians(20))
            .scale(.72f)
            .translate(0, -5, 0));
  }

  static float[] defaultUv(String side, float[] f, float[] t) {
    float[] uv =
        switch (side) {
          case "up", "down" -> new float[] {f[0], f[2], t[0], t[2]};
          case "east", "west" -> new float[] {f[2], 16 - t[1], t[2], 16 - f[1]};
          default -> new float[] {f[0], 16 - t[1], t[0], 16 - f[1]};
        };
    for (int i = 0; i < 4; i++) uv[i] /= 16;
    return uv;
  }

  static float[][] face(String side, float[] f, float[] t) {
    float x = f[0], y = f[1], z = f[2], X = t[0], Y = t[1], Z = t[2];
    return switch (side) {
      case "south" -> new float[][] {{x, y, Z}, {X, y, Z}, {X, Y, Z}, {x, Y, Z}};
      case "north" -> new float[][] {{X, y, z}, {x, y, z}, {x, Y, z}, {X, Y, z}};
      case "east" -> new float[][] {{X, y, Z}, {X, y, z}, {X, Y, z}, {X, Y, Z}};
      case "west" -> new float[][] {{x, y, z}, {x, y, Z}, {x, Y, Z}, {x, Y, z}};
      case "up" -> new float[][] {{x, Y, Z}, {X, Y, Z}, {X, Y, z}, {x, Y, z}};
      default -> new float[][] {{x, y, z}, {X, y, z}, {X, y, Z}, {x, y, Z}};
    };
  }

  static void quad(
      List<Float> out, float[][] corners, float[] uv, int color, Matrix4f transform, int rotation) {
    float[][] uvs = {{uv[0], uv[3]}, {uv[2], uv[3]}, {uv[2], uv[1]}, {uv[0], uv[1]}};
    for (int n : new int[] {0, 1, 2, 0, 2, 3}) {
      var v = transform.transformPosition(new Vector3f(corners[n]));
      out.add(v.x);
      out.add(v.y);
      out.add(v.z);
      var st = uvs[Math.floorMod(n + rotation / 90, 4)];
      out.add(st[0]);
      out.add(st[1]);
      out.add((color >> 16 & 255) / 255f);
      out.add((color >> 8 & 255) / 255f);
      out.add((color & 255) / 255f);
      out.add(1f);
    }
  }

  static Gpu.Mesh mesh(List<Float> vertices, BufferedImage texture) {
    float[] array = new float[vertices.size()];
    for (int i = 0; i < array.length; i++) array[i] = vertices.get(i);
    return new Gpu.Mesh(array, texture);
  }

  BufferedImage head(Appearance appearance, int size) throws Exception {
    if (appearance == null) throw new IllegalArgumentException("Missing native head appearance");
    var skin =
        assets.image(
            assets.root.relativize(appearance.skin().toAbsolutePath().normalize()).toString());
    var meshes = new ArrayList<Gpu.Mesh>();
    cuboid(meshes, skin, -4, -4, -4, 8, 8, 8, 0, 0, 0, new Matrix4f());
    cuboid(meshes, skin, -4, -4, -4, 8, 8, 8, 32, 0, .5f, new Matrix4f());
    return gpu.mesh(
        meshes,
        size,
        size,
        new Matrix4f()
            .ortho(-8, 8, -8, 8, -100, 100)
            .rotateX((float) Math.toRadians(20))
            .rotateY((float) Math.toRadians(35)));
  }

  static void cuboid(
      List<Gpu.Mesh> meshes,
      BufferedImage texture,
      float x,
      float y,
      float z,
      float w,
      float h,
      float d,
      int u,
      int v,
      float inflate,
      Matrix4f transform) {
    float[] f = {x - inflate, y - inflate, z - inflate},
        t = {x + w + inflate, y + h + inflate, z + d + inflate};
    var vertices = new ArrayList<Float>();
    String[] names = {"up", "down", "west", "south", "east", "north"};
    float[][] rects = {
      {u + d, v, u + d + w, v + d},
      {u + d + w, v, u + d + w + w, v + d},
      {u, v + d, u + d, v + d + h},
      {u + d, v + d, u + d + w, v + d + h},
      {u + d + w, v + d, u + d + w + d, v + d + h},
      {u + d + w + d, v + d, u + d + w + d + w, v + d + h}
    };
    for (int i = 0; i < names.length; i++) {
      float[] uv = rects[i];
      uv =
          new float[] {
            uv[0] / texture.getWidth(),
            uv[1] / texture.getHeight(),
            uv[2] / texture.getWidth(),
            uv[3] / texture.getHeight()
          };
      quad(vertices, face(names[i], f, t), uv, 0xffffff, transform, 0);
    }
    meshes.add(mesh(vertices, texture));
  }

  BufferedImage player(
      Appearance appearance, Canvas.PlayerModel node, PlayerRenderSpec spec, double seconds)
      throws Exception {
    var skin =
        assets.image(
            assets.root.relativize(appearance.skin().toAbsolutePath().normalize()).toString());
    if (skin.getWidth() != 64 || (skin.getHeight() != 64 && skin.getHeight() != 32))
      throw new IllegalArgumentException("Skin must be 64x64 or legacy 64x32");
    var pose = spec.poses().get(Math.floorMod(node.facing(), spec.poses().size()));
    var viewport = spec.viewports().get(node.viewportIndex());
    var meshes = new ArrayList<Gpu.Mesh>();
    float arm = appearance.slim() ? 3 : 4;
    float[][] parts = {
      {-4, 24, -4, 8, 8, 8, 0, 0},
      {-4, 12, -2, 8, 12, 4, 16, 16},
      {-4 - arm, 12, -2, arm, 12, 4, 40, 16},
      {4, 12, -2, arm, 12, 4, 32, 48},
      {-4, 0, -2, 4, 12, 4, 0, 16},
      {0, 0, -2, 4, 12, 4, 16, 48}
    };
    int[][] outer = {{32, 0}, {16, 32}, {40, 32}, {48, 48}, {0, 32}, {0, 48}};
    for (int i = 0; i < parts.length; i++) {
      float[] p = parts[i];
      int u = (int) p[6], v = (int) p[7];
      if (skin.getHeight() == 32 && v >= 32) {
        u = i == 3 ? 40 : 0;
        v = 16;
      }
      float angle = (float) Math.toRadians(pose.limbPitch().get(i));
      var transform =
          new Matrix4f()
              .translate((p[0] + p[3] / 2), p[1] + p[4], 0)
              .rotateX(angle)
              .translate(-(p[0] + p[3] / 2), -(p[1] + p[4]), 0);
      cuboid(meshes, skin, p[0], p[1], p[2], p[3], p[4], p[5], u, v, 0, transform);
      if (node.outerLayer() && (skin.getHeight() == 64 || i == 0))
        cuboid(
            meshes,
            skin,
            p[0],
            p[1],
            p[2],
            p[3],
            p[4],
            p[5],
            outer[i][0],
            outer[i][1],
            i == 0 ? .5f : .25f,
            transform);
    }
    for (var slot : appearance.armor().entrySet()) {
      String material = slot.getValue();
      var equipment =
          assets.json(Assets.resource(material, "equipment", ".json")).getAsJsonObject("layers");
      String family = slot.getKey().equals("legs") ? "humanoid_leggings" : "humanoid";
      if (!equipment.has(family))
        throw new IllegalArgumentException("Armor has no " + family + " layer: " + material);
      for (var layer : equipment.getAsJsonArray(family)) {
        String name = layer.getAsJsonObject().get("texture").getAsString();
        var texture =
            assets.image(Assets.resource(name, "textures/entity/equipment/" + family, ".png"));
        int[] indices =
            switch (slot.getKey()) {
              case "head" -> new int[] {0};
              case "chest" -> new int[] {1, 2, 3};
              case "legs" -> new int[] {1, 4, 5};
              case "feet" -> new int[] {4, 5};
              default -> throw new IllegalArgumentException("Unknown armor slot " + slot.getKey());
            };
        for (int i : indices) {
          float[] p = parts[i];
          cuboid(
              meshes,
              texture,
              p[0],
              p[1],
              p[2],
              p[3],
              p[4],
              p[5],
              i == 0 ? 0 : i == 1 ? 16 : i < 4 ? 40 : 0,
              i == 0 ? 0 : 16,
              slot.getKey().equals("legs") ? .5f : 1,
              new Matrix4f());
        }
      }
    }
    float h = (float) pose.worldHeight(), w = h * viewport.width() / viewport.height();
    float idle =
        (float) (node.idle() ? Math.sin(seconds * pose.idleSpeed()) * pose.idleDegrees() : 0);
    var matrix =
        new Matrix4f()
            .ortho(-w / 2, w / 2, -h / 2, h / 2, -100, 100)
            .rotateX((float) Math.toRadians(pose.pitchDegrees()))
            .rotateY((float) Math.toRadians(pose.yawDegrees() + idle))
            .translate(0, -16, 0);
    return gpu.mesh(meshes, viewport.width(), viewport.height(), matrix);
  }
}
