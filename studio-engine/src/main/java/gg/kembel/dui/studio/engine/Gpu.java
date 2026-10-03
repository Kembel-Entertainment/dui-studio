package gg.kembel.dui.studio.engine;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

import gg.kembel.dui.core.*;
import gg.kembel.dui.pack.PackContribution;
import java.awt.image.BufferedImage;
import java.util.*;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

/**
 * Native desktop OpenGL preview, independent of Tauri's platform webview. All calls use the engine
 * thread.
 */
final class Gpu implements AutoCloseable {
  record Mesh(float[] vertices, BufferedImage texture) {}

  final long window;
  final String renderer;
  final int vao, vbo, fbo, depth, target;
  final Map<String, Integer> programs = new HashMap<>();
  final Map<BufferedImage, Integer> textures = new IdentityHashMap<>();
  final GLFWErrorCallback errors;
  int width, height;
  static final String VERTEX =
      "#version 330 core\n"
          + "layout(location=0)in vec3 position;layout(location=1)in vec2 uv;layout(location=2)in"
          + " vec4 tint;uniform mat4 matrix;out vec2 texCoord;out vec4 vertexTint;void"
          + " main(){gl_Position=matrix*vec4(position,1);texCoord=uv;vertexTint=tint;}";
  static final String FRAGMENT =
      "#version 330 core\n"
          + "in vec2 texCoord;in vec4 vertexTint;uniform sampler2D picture;out vec4 color;void"
          + " main(){color=texture(picture,texCoord)*vertexTint;if(color.a<.01)discard;}";

  static String standardLibrary() {
    try (var input =
        PackContribution.class.getResourceAsStream("/ui/shader/position_tex_color.fsh")) {
      String source =
          new String(
              Objects.requireNonNull(input).readAllBytes(),
              java.nio.charset.StandardCharsets.UTF_8);
      var match =
          java.util.regex.Pattern.compile("(?m)^float random\\(float n\\).*?\\}$").matcher(source);
      if (!match.find()) throw new IllegalStateException("Unsupported DUI shader utility version");
      return match.group() + "\n";
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  Gpu() {
    errors = GLFWErrorCallback.createPrint(System.err);
    errors.set();
    if (!glfwInit()) throw new IllegalStateException("OpenGL initialization failed");
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
    glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
    window = glfwCreateWindow(1, 1, "dui preview", 0, 0);
    if (window == 0) throw new IllegalStateException("OpenGL 3.3 is required");
    glfwMakeContextCurrent(window);
    GL.createCapabilities();
    renderer = glGetString(GL_RENDERER);
    vao = glGenVertexArrays();
    vbo = glGenBuffers();
    glBindVertexArray(vao);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    int stride = 9 * 4;
    for (int i = 0; i < 3; i++) {
      glEnableVertexAttribArray(i);
      glVertexAttribPointer(
          i,
          i == 0 ? 3 : i == 1 ? 2 : 4,
          GL_FLOAT,
          false,
          stride,
          (i == 0 ? 0 : i == 1 ? 3 : 5) * 4L);
    }
    fbo = glGenFramebuffers();
    target = glGenTextures();
    depth = glGenRenderbuffers();
    programs.put("texture", program(VERTEX, FRAGMENT));
  }

  int compile(int kind, String source) {
    int shader = glCreateShader(kind);
    glShaderSource(shader, source);
    glCompileShader(shader);
    if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
      String log = glGetShaderInfoLog(shader);
      glDeleteShader(shader);
      throw new IllegalArgumentException(log);
    }
    return shader;
  }

  int program(String vertex, String fragment) {
    int v = compile(GL_VERTEX_SHADER, vertex),
        f = compile(GL_FRAGMENT_SHADER, fragment),
        p = glCreateProgram();
    glAttachShader(p, v);
    glAttachShader(p, f);
    glLinkProgram(p);
    glDeleteShader(v);
    glDeleteShader(f);
    if (glGetProgrami(p, GL_LINK_STATUS) == GL_FALSE) {
      String error = glGetProgramInfoLog(p);
      glDeleteProgram(p);
      throw new IllegalArgumentException(error);
    }
    return p;
  }

  void begin(int w, int h) {
    if (w < 1 || h < 1 || w > 2048 || h > 1152)
      throw new IllegalArgumentException("Preview framebuffer budget");
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    if (width != w || height != h) {
      width = w;
      height = h;
      glBindTexture(GL_TEXTURE_2D, target);
      glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
      glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
      glBindRenderbuffer(GL_RENDERBUFFER, depth);
      glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, w, h);
      glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
      if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
        throw new IllegalStateException("Incomplete preview framebuffer");
    }
    glViewport(0, 0, w, h);
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glEnable(GL_BLEND);
    glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glDisable(GL_CULL_FACE);
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);
  }

  int texture(BufferedImage image) {
    Integer cached = textures.get(image);
    if (cached != null) return cached;
    BufferedImage b = image;
    if (textures.size() > 128) {
      textures.values().forEach(v -> glDeleteTextures(v));
      textures.clear();
    }
    int id = glGenTextures();
    glBindTexture(GL_TEXTURE_2D, id);
    var data = BufferUtils.createByteBuffer(b.getWidth() * b.getHeight() * 4);
    for (int y = 0; y < b.getHeight(); y++)
      for (int x = 0; x < b.getWidth(); x++) {
        int rgb = b.getRGB(x, y);
        data.put((byte) (rgb >> 16)).put((byte) (rgb >> 8)).put((byte) rgb).put((byte) (rgb >> 24));
      }
    data.flip();
    glTexImage2D(
        GL_TEXTURE_2D,
        0,
        GL_RGBA8,
        b.getWidth(),
        b.getHeight(),
        0,
        GL_RGBA,
        GL_UNSIGNED_BYTE,
        data);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    textures.put(image, id);
    return id;
  }

  void vertices(int p, float[] data, Matrix4f matrix) {
    glUseProgram(p);
    var mat = BufferUtils.createFloatBuffer(16);
    matrix.get(mat);
    glUniformMatrix4fv(glGetUniformLocation(p, "matrix"), false, mat);
    glBindVertexArray(vao);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, data, GL_STREAM_DRAW);
    glDrawArrays(GL_TRIANGLES, 0, data.length / 9);
  }

  BufferedImage mesh(List<Mesh> meshes, int w, int h, Matrix4f matrix) {
    begin(w, h);
    int p = programs.get("texture");
    for (var mesh : meshes) {
      glActiveTexture(GL_TEXTURE0);
      glBindTexture(GL_TEXTURE_2D, texture(mesh.texture()));
      vertices(p, mesh.vertices(), matrix);
    }
    return read();
  }

  BufferedImage effect(
      PackContribution.Shader shader,
      Map<String, String> modules,
      ShaderInvocation effect,
      double seconds,
      boolean live) {
    String key = shader.specification().hash() + ":" + shader.glsl() + ":" + new TreeMap<>(modules);
    if (programs.size() > 64) {
      programs
          .entrySet()
          .removeIf(
              e -> {
                if (e.getKey().equals("texture")) return false;
                glDeleteProgram(e.getValue());
                return true;
              });
    }
    int p =
        programs.computeIfAbsent(
            key,
            k -> {
              StringBuilder params = new StringBuilder("struct PreviewParams{\n");
              for (var v : shader.specification().parameters())
                params
                    .append(
                        switch (v.kind()) {
                          case RGB -> "vec3";
                          case BOOLEAN -> "bool";
                          case DECIMAL -> "float";
                          default -> "int";
                        })
                    .append(' ')
                    .append(v.name())
                    .append(";\n");
              if (shader.specification().parameters().isEmpty()) params.append("int unused;\n");
              params.append("};\nuniform PreviewParams parameters;\n");
              var source =
                  "#version 330 core\n"
                      + "in vec2 texCoord;out vec4 color;uniform vec2 size;uniform float"
                      + " age;uniform bool previewLive;\n"
                      + standardLibrary()
                      + new TreeMap<>(modules).values().stream().reduce("", (a, b) -> a + "\n" + b)
                      + "\n"
                      + params
                      + shader.glsl().replace("DUI_PARAMETERS", "PreviewParams")
                      + "\nvoid main(){color="
                      + shader.function()
                      + "(vec2(texCoord.x,1.0-texCoord.y)*size,size,parameters,age,previewLive);}";
              return program(VERTEX, source);
            });
    begin(effect.width(), effect.height());
    glDisable(GL_DEPTH_TEST);
    glUseProgram(p);
    glUniform2f(glGetUniformLocation(p, "size"), width, height);
    glUniform1f(glGetUniformLocation(p, "age"), (float) seconds);
    glUniform1i(glGetUniformLocation(p, "previewLive"), live ? 1 : 0);
    for (var definition : shader.specification().parameters()) {
      Object value = effect.parameters().get(definition.name());
      int location = glGetUniformLocation(p, "parameters." + definition.name());
      switch (definition.kind()) {
        case BOOLEAN -> glUniform1i(location, (boolean) value ? 1 : 0);
        case ENUM -> glUniform1i(location, definition.choices().indexOf(value));
        case DECIMAL -> glUniform1f(location, ((Number) value).floatValue());
        case RGB -> {
          int rgb = ((Number) value).intValue();
          glUniform3f(
              location, (rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f);
        }
        default -> glUniform1i(location, ((Number) value).intValue());
      }
    }
    vertices(
        p,
        new float[] {
          -1, -1, 0, 0, 0, 1, 1, 1, 1, 1, -1, 0, 1, 0, 1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, -1,
          -1, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, -1, 1, 0, 0, 1, 1, 1, 1, 1
        },
        new Matrix4f());
    return read();
  }

  BufferedImage read() {
    glfwPollEvents();
    var bytes = BufferUtils.createByteBuffer(width * height * 4);
    glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
    var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < height; y++)
      for (int x = 0; x < width; x++) {
        int at = ((height - y - 1) * width + x) * 4, a = bytes.get(at + 3) & 255;
        int r = bytes.get(at) & 255, g = bytes.get(at + 1) & 255, b = bytes.get(at + 2) & 255;
        if (a > 0 && a < 255) {
          r = Math.min(255, r * 255 / a);
          g = Math.min(255, g * 255 / a);
          b = Math.min(255, b * 255 / a);
        }
        image.setRGB(x, y, a << 24 | r << 16 | g << 8 | b);
      }
    return image;
  }

  public void close() {
    textures.values().forEach(v -> glDeleteTextures(v));
    programs.values().forEach(p -> glDeleteProgram(p));
    glDeleteBuffers(vbo);
    glDeleteVertexArrays(vao);
    glDeleteFramebuffers(fbo);
    glDeleteRenderbuffers(depth);
    glDeleteTextures(target);
    glfwDestroyWindow(window);
    glfwTerminate();
    errors.free();
  }
}
