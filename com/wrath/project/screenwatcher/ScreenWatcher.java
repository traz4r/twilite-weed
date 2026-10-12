package com.wrath.project.screenwatcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wrath.project.common.input.identity.Identity;
import com.wrath.project.common.json.JsonUtils;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

public final class ScreenWatcher {
   static final int MAX_IMAGE_BYTES = 26214400;
   private static boolean DEBUG = false;
   private static final Set<String> APPLICATION_PROPERTIES = Set.of("watchUrl", "discoveryUrl", "publicKeyUrl", "requestTimeoutSeconds", "frequency");
   private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
   private static ScreenWatcher.RuntimeContext runtimeContext;

   private ScreenWatcher() {
   }

   public static void main(String[] ignoredArgs) throws JsonProcessingException {
      DEBUG = true;
      Identity identity = new Identity("1409657515410456737", "workstation-1", "replace-with-api-token", "example-session-id");
      ScreenWatcher.ApplicationSettings settings = new ScreenWatcher.ApplicationSettings(
         "http://localhost:8087/weed/4", "http://localhost:8087/weed/5", "http://localhost:8087/weed/public-key", 30L, 5L
      );
      String[] runArguments = new String[]{OBJECT_MAPPER.writeValueAsString(identity), OBJECT_MAPPER.writeValueAsString(settings)};
      runUntilStopped(runArguments, ScreenWatcher::run);
   }

   static void runUntilStopped(String[] args, ScreenWatcher.Runner runner) {
      Objects.requireNonNull(args, "args");
      Objects.requireNonNull(runner, "runner");

      while (runner.run(args)) {
      }
   }

   public static boolean run(String[] args) {
      try {
         ScreenWatcher.Settings settings = ScreenWatcher.Settings.fromArguments(args);
         return runCaptureCycle(settings, () -> {
            DesktopScreenCapturer capturer = new DesktopScreenCapturer();
            List<DesktopScreenCapturer.ScreenGeometry> geometry = capturer.discoverScreens();
            ScreenWatcher.RuntimeContext context = runtimeContext(settings, geometry, ScreenWatcher::createApi);
            List<DesktopScreenCapturer.CapturedScreen> screens = capturer.captureAll(geometry);

            for (DesktopScreenCapturer.CapturedScreen screen : screens) {
               context.api().upload(screen.screenId(), encodePng(screen.image()));
            }

            if (DEBUG) {
               System.out.println(screens.size() + " screenshots sent to Weed Watch.");
            }
         }, TimeUnit.SECONDS::sleep);
      } catch (Throwable throwable) {
         if (DEBUG) {
            throwable.printStackTrace();
         }

         return false;
      }
   }

   static boolean runCaptureCycle(ScreenWatcher.Settings settings, ScreenWatcher.CaptureCycle captureCycle, ScreenWatcher.Sleeper sleeper) throws Exception {
      Objects.requireNonNull(settings, "settings");
      Objects.requireNonNull(captureCycle, "captureCycle");
      Objects.requireNonNull(sleeper, "sleeper");
      if (settings.frequency() == 0L) {
         captureCycle.run();
         return false;
      }

      try {
         captureCycle.run();
      } catch (Exception exception) {
         if (DEBUG) {
            exception.printStackTrace();
         }
      }

      try {
         sleeper.sleep(settings.frequency());
         return true;
      } catch (InterruptedException exception) {
         Thread.currentThread().interrupt();
         if (DEBUG) {
            exception.printStackTrace();
         }

         return false;
      }
   }

   static byte[] encodePng(BufferedImage image) throws IOException {
      Objects.requireNonNull(image, "image");
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      if (!ImageIO.write(image, "png", output)) {
         throw new IOException("No PNG encoder is available.");
      }

      byte[] png = output.toByteArray();
      validateImageSize(png);
      return png;
   }

   static void validateImageSize(byte[] image) throws IOException {
      Objects.requireNonNull(image, "image");
      if (image.length == 0 || image.length > 26214400) {
         throw new IOException("The captured image exceeds the 25 MiB Weed Watch limit.");
      }
   }

   static URI appendSessionId(URI endpoint, UUID sessionId) {
      Objects.requireNonNull(endpoint, "endpoint");
      Objects.requireNonNull(sessionId, "sessionId");
      String value = endpoint.toString();

      while (value.endsWith("/")) {
         value = value.substring(0, value.length() - 1);
      }

      return URI.create(value + "/" + sessionId);
   }

   static synchronized ScreenWatcher.RuntimeContext runtimeContext(
      ScreenWatcher.Settings settings, List<DesktopScreenCapturer.ScreenGeometry> screens, ScreenWatcher.ApiFactory factory
   ) throws Exception {
      Objects.requireNonNull(screens, "screens");
      ScreenWatcher.RuntimeKey key = ScreenWatcher.RuntimeKey.from(settings);
      if (runtimeContext == null || !runtimeContext.key().equals(key)) {
         UUID sessionId = UUID.randomUUID();
         ScreenWatcher.WatchApi api = factory.create(settings, sessionId);
         api.discover(screens);
         runtimeContext = new ScreenWatcher.RuntimeContext(key, sessionId, api);
      }

      return runtimeContext;
   }

   static synchronized void resetRuntimeContext() {
      runtimeContext = null;
   }

   private static ScreenWatcher.WatchApi createApi(ScreenWatcher.Settings settings, UUID sessionId) throws IOException {
      Duration timeout = Duration.ofSeconds(settings.requestTimeoutSeconds());
      HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
      return new WatchApiClient(
         httpClient,
         settings.publicKeyUrl(),
         appendSessionId(settings.discoveryUrl(), sessionId),
         appendSessionId(settings.watchUrl(), sessionId),
         settings.identity(),
         sessionId,
         timeout
      );
   }

   @FunctionalInterface
   interface ApiFactory {
      ScreenWatcher.WatchApi create(ScreenWatcher.Settings var1, UUID var2) throws Exception;
   }

   record ApplicationSettings(String watchUrl, String discoveryUrl, String publicKeyUrl, long requestTimeoutSeconds, long frequency) {
   }

   @FunctionalInterface
   interface CaptureCycle {
      void run() throws Exception;
   }

   @FunctionalInterface
   interface Runner {
      boolean run(String[] var1);
   }

   record RuntimeContext(ScreenWatcher.RuntimeKey key, UUID sessionId, ScreenWatcher.WatchApi api) {
      RuntimeContext {
         Objects.requireNonNull(key, "key");
         Objects.requireNonNull(sessionId, "sessionId");
         Objects.requireNonNull(api, "api");
      }
   }

   record RuntimeKey(Identity identity, URI watchUrl, URI discoveryUrl, URI publicKeyUrl, long requestTimeoutSeconds) {
      static ScreenWatcher.RuntimeKey from(ScreenWatcher.Settings settings) {
         return new ScreenWatcher.RuntimeKey(
            settings.identity(), settings.watchUrl(), settings.discoveryUrl(), settings.publicKeyUrl(), settings.requestTimeoutSeconds()
         );
      }
   }

   record Settings(Identity identity, URI watchUrl, URI discoveryUrl, URI publicKeyUrl, long requestTimeoutSeconds, long frequency) {
      Settings(Identity identity, URI watchUrl, URI discoveryUrl, URI publicKeyUrl, long requestTimeoutSeconds, long frequency) {
         Objects.requireNonNull(identity, "identity");
         validateEndpoint(watchUrl);
         validateEndpoint(discoveryUrl);
         validateEndpoint(publicKeyUrl);
         if (requestTimeoutSeconds > 0L && frequency >= 0L) {
            this.identity = identity;
            this.watchUrl = watchUrl;
            this.discoveryUrl = discoveryUrl;
            this.publicKeyUrl = publicKeyUrl;
            this.requestTimeoutSeconds = requestTimeoutSeconds;
            this.frequency = frequency;
         } else {
            throw new IllegalArgumentException("The application configuration JSON is invalid");
         }
      }

      static ScreenWatcher.Settings fromArguments(String[] args) {
         if (args != null && args.length >= 2 && args[0] != null && args[1] != null) {
            Identity identity = Identity.fromJson(args[0]);
            JsonNode configuration = JsonUtils.parseObject(args[1], ScreenWatcher.APPLICATION_PROPERTIES);
            return new ScreenWatcher.Settings(
               identity,
               parseEndpoint(JsonUtils.requiredText(configuration, "watchUrl")),
               parseEndpoint(JsonUtils.requiredText(configuration, "discoveryUrl")),
               parseEndpoint(JsonUtils.requiredText(configuration, "publicKeyUrl")),
               JsonUtils.requiredPositiveLong(configuration, "requestTimeoutSeconds"),
               JsonUtils.requiredNonNegativeLong(configuration, "frequency")
            );
         } else {
            throw new IllegalArgumentException("The application configuration JSON is invalid");
         }
      }

      private static URI parseEndpoint(String value) {
         try {
            URI endpoint = new URI(value.trim());
            validateEndpoint(endpoint);
            return endpoint;
         } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("The application configuration JSON is invalid");
         }
      }

      private static void validateEndpoint(URI endpoint) {
         if (endpoint == null
            || endpoint.getHost() == null
            || endpoint.getQuery() != null
            || endpoint.getFragment() != null
            || !"http".equalsIgnoreCase(endpoint.getScheme()) && !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("The application configuration JSON is invalid");
         }
      }
   }

   @FunctionalInterface
   interface Sleeper {
      void sleep(long var1) throws InterruptedException;
   }

   interface WatchApi {
      void discover(List<DesktopScreenCapturer.ScreenGeometry> var1) throws IOException;

      void upload(String var1, byte[] var2) throws IOException;
   }
}
