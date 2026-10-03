package gg.kembel.dui.studio.engine;

import gg.kembel.dui.core.*;
import gg.kembel.dui.studio.sdk.StudioProjectProvider;
import java.util.*;

/**
 * Studio's explicit preview skin. Exported templates never acquire an implicit application design.
 */
final class DefaultProject implements StudioProjectProvider {
  final RenderEnvironment environment;

  DefaultProject(Assets assets) {
    var skins = new HashMap<String, WidgetSkinRegistry.Skin>();
    for (String type : ComponentSchemas.all().keySet())
      skins.put(
          type,
          new WidgetSkinRegistry.Skin(
              18,
              0,
              0,
              18,
              (context, part) -> {
                var n = context.node();
                var c = context.canvas();
                int x = context.x(), y = context.y(), w = context.width(), h = context.height();
                int color = color(n.s("color", "#EEF0FF")),
                    fill = color(n.s("fill", "#292C40")),
                    border = color(n.s("border", "#626781"));
                switch (n.type()) {
                  case "text", "heading" ->
                      TextLayout.draw(
                          c,
                          n.s("label", ""),
                          x,
                          y,
                          w,
                          h,
                          n.s("align", "left"),
                          n.b("wrap"),
                          Math.max(1, h / 9),
                          color);
                  case "panel", "card" -> c.panel(x, y, w, h, fill, border);
                  case "divider" -> c.rect(x, y + Math.min(3, h - 1), w, 1, border);
                  case "progress" -> {
                    c.rect(x, y, w, h, fill);
                    double v =
                        Double.parseDouble(n.s("value", "0"))
                            / Math.max(1, Double.parseDouble(n.s("max", "100")));
                    c.rect(x, y, (int) (Math.clamp(v, 0, 1) * w), h, border);
                  }
                  default -> {
                    c.panel(x, y, w, h, fill, border);
                    String label = n.s("label", n.s("value", n.type()));
                    if (n.type().equals("checkbox") || n.type().equals("toggle"))
                      label = (n.b("checked") ? "[x] " : "[ ] ") + label;
                    c.text(
                        x + Math.min(3, w / 4),
                        y + Math.max(0, (h - 9) / 2),
                        Math.max(1, w - 6),
                        label,
                        color);
                  }
                }
              },
              (n, w, h) ->
                  n.type().equals("choice")
                      ? Map.of(
                          "previous",
                          new WidgetSkinRegistry.Part(0, 0, Math.min(18, w), h),
                          "next",
                          new WidgetSkinRegistry.Part(Math.max(0, w - 18), 0, Math.min(18, w), h))
                      : Map.of()));
    environment =
        new RenderEnvironment(assets.font(), ThemeTokens.EMPTY, new WidgetSkinRegistry(skins));
  }

  static int color(String value) {
    if (!value.matches("#[0-9a-fA-F]{6}"))
      throw new IllegalArgumentException("Use explicit colors or configure project tokens");
    return Integer.parseInt(value.substring(1), 16);
  }

  public RenderEnvironment environment() {
    return environment;
  }
}
