package com.wrath.project.screenwatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wrath.project.common.input.identity.Identity;
import com.wrath.project.common.weed.WeedRequestEncryptor;
import java.awt.Rectangle;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

final class WatchApiClient implements ScreenWatcher.WatchApi {
   private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
   private final HttpClient httpClient;
   private final URI discoveryUri;
   private final URI watchUri;
   private final Identity identity;
   private final UUID sessionId;
   private final Duration requestTimeout;
   private final WeedRequestEncryptor encryptor;
   private volatile byte[] discoveryPayload;

   WatchApiClient(HttpClient httpClient, URI publicKeyUri, URI discoveryUri, URI watchUri, Identity identity, UUID sessionId, Duration requestTimeout) throws IOException {
      this(httpClient, discoveryUri, watchUri, identity, sessionId, requestTimeout, WeedRequestEncryptor.fetch(httpClient, publicKeyUri, requestTimeout));
   }

   WatchApiClient(
      HttpClient httpClient, URI discoveryUri, URI watchUri, Identity identity, UUID sessionId, Duration requestTimeout, WeedRequestEncryptor encryptor
   ) {
      this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
      this.discoveryUri = Objects.requireNonNull(discoveryUri, "discoveryUri");
      this.watchUri = Objects.requireNonNull(watchUri, "watchUri");
      this.identity = Objects.requireNonNull(identity, "identity");
      this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
      this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
      this.encryptor = Objects.requireNonNull(encryptor, "encryptor");
   }

   @Override
   public void discover(List<DesktopScreenCapturer.ScreenGeometry> screens) throws IOException {
      Objects.requireNonNull(screens, "screens");
      List<WatchApiClient.ScreenMetadata> metadata = List.copyOf(screens).stream().map(WatchApiClient.ScreenMetadata::from).toList();
      byte[] payload = OBJECT_MAPPER.writeValueAsBytes(
         new WatchApiClient.DiscoveryRequest(
            this.identity.discordId(),
            this.identity.machineId(),
            operatingSystemValue(System.getProperty("os.name")),
            operatingSystemValue(System.getProperty("os.version")),
            operatingSystemValue(System.getProperty("os.arch")),
            metadata
         )
      );
      this.requireSuccessful(this.send(this.buildRequest(this.discoveryUri, "5", () -> new ByteArrayInputStream(payload))));
      this.discoveryPayload = (byte[])payload.clone();
   }

   static String operatingSystemValue(String value) {
      return value != null && !value.isBlank() ? value.trim() : "-";
   }

   @Override
   public void upload(String screenId, byte[] png) throws IOException {
      Objects.requireNonNull(screenId, "screenId");
      ScreenWatcher.validateImageSize(png);
      byte[] payload = OBJECT_MAPPER.writeValueAsBytes(
         new WatchApiClient.WatchImageRequest(this.identity.discordId(), this.identity.machineId(), screenId, png)
      );
      HttpResponse<Void> response = this.send(this.buildRequest(this.watchUri, "4", () -> new ByteArrayInputStream(payload)));
      byte[] cachedDiscovery = this.discoveryPayload;
      if (response.statusCode() == 409 && cachedDiscovery != null) {
         this.requireSuccessful(this.send(this.buildRequest(this.discoveryUri, "5", () -> new ByteArrayInputStream(cachedDiscovery))));
      } else {
         this.requireSuccessful(response);
      }
   }

   HttpRequest buildRequest(URI uri, String endpoint, Supplier<InputStream> payloadSupplier) {
      BodyPublisher body = BodyPublishers.ofInputStream(() -> {
         try {
            return this.encryptor.encrypt(endpoint, this.sessionId, "Bearer " + this.identity.discordId(), payloadSupplier);
         } catch (IOException exception) {
            throw new UncheckedIOException(exception);
         }
      });
      return HttpRequest.newBuilder(uri).header("Content-Type", "application/jose").timeout(this.requestTimeout).POST(body).build();
   }

   private HttpResponse<Void> send(HttpRequest request) throws IOException {
      try {
         return this.httpClient.send(request, BodyHandlers.discarding());
      } catch (InterruptedException exception) {
         Thread.currentThread().interrupt();
         throw new IOException("The Weed Watch request was interrupted.", exception);
      }
   }

   private void requireSuccessful(HttpResponse<Void> response) throws IOException {
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
         throw new IOException("The Weed Watch API returned HTTP " + response.statusCode() + ".");
      }
   }

   private record DiscoveryRequest(
      String discordId, String machineId, String osName, String osVersion, String osArch, List<WatchApiClient.ScreenMetadata> screens
   ) {
   }

   private record ScreenMetadata(String screenId, int width, int height, int topLeftX, int topLeftY) {
      private static WatchApiClient.ScreenMetadata from(DesktopScreenCapturer.ScreenGeometry screen) {
         Rectangle bounds = screen.bounds();
         return new WatchApiClient.ScreenMetadata(screen.screenId(), bounds.width, bounds.height, bounds.x, bounds.y);
      }
   }

   private record WatchImageRequest(String discordId, String machineId, String screenId, byte[] image) {
   }
}
