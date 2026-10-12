package com.wrath.project.screenwatcher;

import java.awt.AWTException;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class DesktopScreenCapturer {
   List<DesktopScreenCapturer.ScreenGeometry> discoverScreens() {
      GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
      if (environment.isHeadlessInstance()) {
         throw new HeadlessException("Screen capture requires a graphical desktop.");
      } else {
         return activeScreens(environment.getScreenDevices());
      }
   }

   List<DesktopScreenCapturer.CapturedScreen> captureAll(List<DesktopScreenCapturer.ScreenGeometry> screens) throws AWTException {
      Objects.requireNonNull(screens, "screens");
      screens = List.copyOf(screens);
      if (screens.isEmpty()) {
         throw new IllegalArgumentException("At least one screen is required.");
      }

      PointerInfo pointer = MouseInfo.getPointerInfo();
      Point pointerLocation = pointer == null ? null : pointer.getLocation();
      String pointerDeviceId = pointer != null && pointer.getDevice() != null ? pointer.getDevice().getIDstring() : null;
      int pointerScreen = selectPointerScreen(screens, pointerDeviceId, pointerLocation);
      Robot robot = new Robot();
      List<DesktopScreenCapturer.CapturedScreen> captured = new ArrayList<>(screens.size());

      for (int index = 0; index < screens.size(); index++) {
         DesktopScreenCapturer.ScreenGeometry screen = screens.get(index);
         BufferedImage image = robot.createScreenCapture(screen.bounds());
         if (index == pointerScreen && pointerLocation != null) {
            CursorRenderer.draw(image, screen.bounds(), pointerLocation);
         }

         captured.add(new DesktopScreenCapturer.CapturedScreen(screen.screenId(), image));
      }

      return List.copyOf(captured);
   }

   static int selectPointerScreen(List<DesktopScreenCapturer.ScreenGeometry> screens, String pointerDeviceId, Point pointerLocation) {
      Objects.requireNonNull(screens, "screens");
      if (pointerDeviceId != null) {
         for (int index = 0; index < screens.size(); index++) {
            if (pointerDeviceId.equals(screens.get(index).screenId())) {
               return index;
            }
         }
      }

      if (pointerLocation != null) {
         for (int index = 0; index < screens.size(); index++) {
            if (screens.get(index).bounds().contains(pointerLocation)) {
               return index;
            }
         }
      }

      return -1;
   }

   private static List<DesktopScreenCapturer.ScreenGeometry> activeScreens(GraphicsDevice[] devices) {
      List<DesktopScreenCapturer.ScreenGeometry> screens = new ArrayList<>();
      if (devices != null) {
         for (GraphicsDevice device : devices) {
            if (device != null) {
               GraphicsConfiguration configuration = device.getDefaultConfiguration();
               String id = device.getIDstring();
               if (configuration != null && id != null && !id.trim().isEmpty()) {
                  String screenId = id.trim();
                  Rectangle bounds = configuration.getBounds();
                  if (bounds != null && !bounds.isEmpty() && screenId.length() <= 255) {
                     screens.add(new DesktopScreenCapturer.ScreenGeometry(screenId, bounds));
                  }
               }
            }
         }
      }

      if (screens.isEmpty()) {
         throw new IllegalStateException("No active screens were found.");
      } else {
         return List.copyOf(screens);
      }
   }

   record CapturedScreen(String screenId, BufferedImage image) {
      CapturedScreen {
         Objects.requireNonNull(screenId, "screenId");
         Objects.requireNonNull(image, "image");
      }
   }

   record ScreenGeometry(String screenId, Rectangle bounds) {
      ScreenGeometry {
         Objects.requireNonNull(screenId, "screenId");
         Objects.requireNonNull(bounds, "bounds");
         bounds = new Rectangle(bounds);
      }

      public Rectangle bounds() {
         return new Rectangle(this.bounds);
      }
   }
}
