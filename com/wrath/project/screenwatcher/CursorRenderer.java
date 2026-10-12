package com.wrath.project.screenwatcher;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Objects;

final class CursorRenderer {
   private CursorRenderer() {
   }

   static void draw(BufferedImage image, Rectangle screenBounds, Point pointerLocation) {
      Objects.requireNonNull(image, "image");
      Objects.requireNonNull(screenBounds, "screenBounds");
      Objects.requireNonNull(pointerLocation, "pointerLocation");
      if (!screenBounds.isEmpty() && image.getWidth() > 0 && image.getHeight() > 0) {
         double scaleX = image.getWidth() / screenBounds.getWidth();
         double scaleY = image.getHeight() / screenBounds.getHeight();
         int x = clamp((int)Math.floor((pointerLocation.x - screenBounds.x) * scaleX), 0, image.getWidth() - 1);
         int y = clamp((int)Math.floor((pointerLocation.y - screenBounds.y) * scaleY), 0, image.getHeight() - 1);
         Polygon arrow = new Polygon(new int[]{x, x, x + 5, x + 9, x + 13, x + 9, x + 17}, new int[]{y, y + 20, y + 15, y + 24, y + 22, y + 14, y + 14}, 7);
         Graphics2D graphics = image.createGraphics();

         try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillPolygon(arrow);
            graphics.setColor(Color.BLACK);
            graphics.setStroke(new BasicStroke(2.0F, 1, 1));
            graphics.drawPolygon(arrow);
         } finally {
            graphics.dispose();
         }
      }
   }

   private static int clamp(int value, int minimum, int maximum) {
      return Math.max(minimum, Math.min(maximum, value));
   }
}
