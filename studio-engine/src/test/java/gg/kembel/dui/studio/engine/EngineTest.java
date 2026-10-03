package gg.kembel.dui.studio.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineTest {
  @TempDir Path root;

  @Test
  void resourceNamesAcceptFontFilesAndItemIds() {
    assertEquals(
        "assets/minecraft/textures/font/ascii.png",
        Assets.resource("minecraft:font/ascii.png", "textures", ".png"));
    assertEquals(
        "assets/example/models/widget.json", Assets.resource("example:widget", "models", ".json"));
  }

  @Test
  void assetsRejectEscapesAndHonorContributionPrecedence() throws Exception {
    Files.writeString(root.resolve("sample.json"), "{}");
    try (var assets = new Assets(root, null)) {
      assertThrows(IOException.class, () -> assets.local("../secret"));
      assets.contributed.put("sample.json", "{\"project\":true}".getBytes());
      assertTrue(assets.json("sample.json").get("project").getAsBoolean());
    }
  }

  @Test
  void motionEndpointsAndBackOutOvershoot() {
    for (var easing : gg.kembel.dui.core.Motion.Easing.values()) {
      assertEquals(0, Main.ease(0, easing), 1e-6);
      assertEquals(1, Main.ease(1, easing), 1e-6);
    }
    assertTrue(Main.ease(.65, gg.kembel.dui.core.Motion.Easing.BACK_OUT) > 1);
  }

  @Test
  void pixelsRetainAlphaAndOrientation() {
    var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, 0x80ff0000);
    image.setRGB(1, 1, 0xff00ff00);
    var round = Assets.raster(Assets.raster(image));
    assertEquals(image.getRGB(0, 0), round.getRGB(0, 0));
    assertEquals(image.getRGB(1, 1), round.getRGB(1, 1));
  }
}
