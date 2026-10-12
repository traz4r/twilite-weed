package com.wrath.project.common.weed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Base64.Decoder;
import java.util.Base64.Encoder;
import java.util.function.Supplier;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.PSource.PSpecified;

public final class WeedRequestEncryptor {
   private static final String ALGORITHM = "RSA-OAEP-256";
   private static final String ENCRYPTION = "A256GCM";
   private static final String TYPE = "twilite-weed+jwe";
   private static final String CONTENT_TYPE = "application/json";
   private static final int RSA_BITS = 3072;
   private static final int CEK_BYTES = 32;
   private static final int IV_BYTES = 12;
   private static final int TAG_BYTES = 16;
   private static final byte[] BASE64_URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".getBytes(StandardCharsets.US_ASCII);
   private static final Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
   private static final Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
   private static final OAEPParameterSpec OAEP_SHA_256 = new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSpecified.DEFAULT);
   private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
   private final PublicKey publicKey;
   private final String keyId;
   private final SecureRandom random;

   private WeedRequestEncryptor(PublicKey publicKey, String keyId, SecureRandom random) {
      this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
      this.keyId = Objects.requireNonNull(keyId, "keyId");
      this.random = Objects.requireNonNull(random, "random");
   }

   public static WeedRequestEncryptor fetch(HttpClient httpClient, URI publicKeyUri, Duration requestTimeout) throws IOException {
      Objects.requireNonNull(httpClient, "httpClient");
      Objects.requireNonNull(publicKeyUri, "publicKeyUri");
      Objects.requireNonNull(requestTimeout, "requestTimeout");
      HttpRequest request = HttpRequest.newBuilder(publicKeyUri).header("Accept", "application/jwk+json").timeout(requestTimeout).GET().build();

      HttpResponse<byte[]> response;
      try {
         response = httpClient.send(request, BodyHandlers.ofByteArray());
      } catch (InterruptedException exception) {
         Thread.currentThread().interrupt();
         throw new IOException("The public key request was interrupted.", exception);
      }

      if (response.statusCode() >= 200 && response.statusCode() < 300) {
         return fromJwk(response.body());
      } else {
         throw new IOException("The public key API returned HTTP " + response.statusCode() + ".");
      }
   }

   static WeedRequestEncryptor fromJwk(byte[] jwk) throws IOException {
      try {
         JsonNode root = OBJECT_MAPPER.readTree(jwk);
         if (root != null && root.isObject() && "RSA".equals(text(root, "kty")) && "enc".equals(text(root, "use")) && "RSA-OAEP-256".equals(text(root, "alg"))) {
            String keyId = requiredText(root, "kid");
            byte[] modulusBytes = decode(requiredText(root, "n"));
            byte[] exponentBytes = decode(requiredText(root, "e"));
            if (modulusBytes.length != 0 && exponentBytes.length != 0) {
               BigInteger modulus = new BigInteger(1, modulusBytes);
               BigInteger exponent = new BigInteger(1, exponentBytes);
               if (modulus.bitLength() == 3072 && exponent.compareTo(BigInteger.ONE) > 0) {
                  PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
                  return new WeedRequestEncryptor(publicKey, keyId, new SecureRandom());
               } else {
                  throw invalidKey();
               }
            } else {
               throw invalidKey();
            }
         } else {
            throw invalidKey();
         }
      } catch (IOException exception) {
         throw exception;
      } catch (GeneralSecurityException | IllegalArgumentException exception) {
         throw invalidKey(exception);
      }
   }

   public InputStream encrypt(String endpoint, UUID sessionId, String authorization, Supplier<InputStream> payloadSupplier) throws IOException {
      Objects.requireNonNull(endpoint, "endpoint");
      Objects.requireNonNull(sessionId, "sessionId");
      Objects.requireNonNull(authorization, "authorization");
      Objects.requireNonNull(payloadSupplier, "payloadSupplier");

      try {
         ObjectNode header = OBJECT_MAPPER.createObjectNode();
         header.put("alg", "RSA-OAEP-256");
         header.put("enc", "A256GCM");
         header.put("typ", "twilite-weed+jwe");
         header.put("cty", "application/json");
         header.put("kid", this.keyId);
         header.put("ep", endpoint);
         header.put("sid", sessionId.toString());
         String protectedHeader = encode(OBJECT_MAPPER.writeValueAsBytes(header));
         byte[] cek = new byte[32];
         byte[] iv = new byte[12];
         this.random.nextBytes(cek);
         this.random.nextBytes(iv);
         Cipher keyCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
         keyCipher.init(1, this.publicKey, OAEP_SHA_256);
         String encryptedKey = encode(keyCipher.doFinal(cek));
         Cipher payloadCipher = Cipher.getInstance("AES/GCM/NoPadding");
         payloadCipher.init(1, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, iv));
         payloadCipher.updateAAD(protectedHeader.getBytes(StandardCharsets.US_ASCII));
         byte[] envelopePrefix = ("{\"authorization\":" + OBJECT_MAPPER.writeValueAsString(authorization) + ",\"payload\":").getBytes(StandardCharsets.UTF_8);
         InputStream payload = Objects.requireNonNull(payloadSupplier.get(), "payload");
         InputStream plaintext = sequence(new ByteArrayInputStream(envelopePrefix), payload, new ByteArrayInputStream("}".getBytes(StandardCharsets.UTF_8)));
         WeedRequestEncryptor.TagSeparatingInputStream ciphertext = new WeedRequestEncryptor.TagSeparatingInputStream(
            new WeedRequestEncryptor.CipherTransformInputStream(plaintext, payloadCipher), 16
         );
         InputStream encodedCiphertext = new WeedRequestEncryptor.Base64UrlInputStream(ciphertext);
         byte[] compactPrefix = (protectedHeader + "." + encryptedKey + "." + encode(iv) + ".").getBytes(StandardCharsets.US_ASCII);
         return sequence(new ByteArrayInputStream(compactPrefix), encodedCiphertext, new WeedRequestEncryptor.TagSuffixInputStream(ciphertext));
      } catch (GeneralSecurityException exception) {
         throw new IOException("Could not encrypt the API request.", exception);
      }
   }

   private static InputStream sequence(InputStream... streams) {
      return new SequenceInputStream(Collections.enumeration(List.of(streams)));
   }

   private static String text(JsonNode node, String field) {
      JsonNode value = node.get(field);
      return value != null && value.isTextual() ? value.textValue() : null;
   }

   private static String requiredText(JsonNode node, String field) throws IOException {
      String value = text(node, field);
      if (value != null && !value.isBlank()) {
         return value;
      } else {
         throw invalidKey();
      }
   }

   private static byte[] decode(String value) throws IOException {
      try {
         return BASE64_URL_DECODER.decode(value);
      } catch (IllegalArgumentException exception) {
         throw invalidKey(exception);
      }
   }

   private static String encode(byte[] value) {
      return BASE64_URL_ENCODER.encodeToString(value);
   }

   private static IOException invalidKey() {
      return new IOException("The public key API returned an invalid Weed encryption key.");
   }

   private static IOException invalidKey(Exception cause) {
      return new IOException("The public key API returned an invalid Weed encryption key.", cause);
   }

   private static final class Base64UrlInputStream extends InputStream {
      private final InputStream source;
      private final byte[] raw = new byte[3];
      private final byte[] encoded = new byte[4];
      private int encodedOffset;
      private int encodedLength;
      private boolean exhausted;

      private Base64UrlInputStream(InputStream source) {
         this.source = Objects.requireNonNull(source, "source");
      }

      @Override
      public int read() throws IOException {
         return !this.ensureEncoded() ? -1 : Byte.toUnsignedInt(this.encoded[this.encodedOffset++]);
      }

      @Override
      public int read(byte[] bytes, int offset, int length) throws IOException {
         Objects.checkFromIndexSize(offset, length, bytes.length);
         if (length == 0) {
            return 0;
         }

         int written = 0;

         while (written < length && this.ensureEncoded()) {
            int count = Math.min(length - written, this.encodedLength - this.encodedOffset);
            System.arraycopy(this.encoded, this.encodedOffset, bytes, offset + written, count);
            this.encodedOffset += count;
            written += count;
         }

         return written == 0 ? -1 : written;
      }

      private boolean ensureEncoded() throws IOException {
         if (this.encodedOffset < this.encodedLength) {
            return true;
         }

         if (this.exhausted) {
            return false;
         }

         int rawLength = 0;

         while (rawLength < this.raw.length) {
            int count = this.source.read(this.raw, rawLength, this.raw.length - rawLength);
            if (count < 0) {
               this.exhausted = true;
               break;
            }

            rawLength += count;
         }

         if (rawLength == 0) {
            return false;
         }

         this.encoded[0] = WeedRequestEncryptor.BASE64_URL_ALPHABET[(this.raw[0] & 255) >>> 2];
         this.encoded[1] = WeedRequestEncryptor.BASE64_URL_ALPHABET[(this.raw[0] & 3) << 4 | (rawLength > 1 ? (this.raw[1] & 255) >>> 4 : 0)];
         this.encodedLength = 2;
         if (rawLength > 1) {
            this.encoded[2] = WeedRequestEncryptor.BASE64_URL_ALPHABET[(this.raw[1] & 15) << 2 | (rawLength > 2 ? (this.raw[2] & 255) >>> 6 : 0)];
            this.encodedLength = 3;
         }

         if (rawLength > 2) {
            this.encoded[3] = WeedRequestEncryptor.BASE64_URL_ALPHABET[this.raw[2] & 63];
            this.encodedLength = 4;
         }

         this.encodedOffset = 0;
         return true;
      }

      @Override
      public void close() throws IOException {
         this.source.close();
      }
   }

   private static final class CipherTransformInputStream extends InputStream {
      private static final int SOURCE_BUFFER_BYTES = 8192;
      private final InputStream source;
      private final Cipher cipher;
      private final byte[] sourceBuffer = new byte[8192];
      private byte[] transformed = new byte[0];
      private int transformedOffset;
      private boolean finalized;

      private CipherTransformInputStream(InputStream source, Cipher cipher) {
         this.source = Objects.requireNonNull(source, "source");
         this.cipher = Objects.requireNonNull(cipher, "cipher");
      }

      @Override
      public int read() throws IOException {
         byte[] one = new byte[1];
         int count = this.read(one, 0, 1);
         return count < 0 ? -1 : Byte.toUnsignedInt(one[0]);
      }

      @Override
      public int read(byte[] bytes, int offset, int length) throws IOException {
         Objects.checkFromIndexSize(offset, length, bytes.length);
         if (length == 0) {
            return 0;
         }

         int written = 0;

         while (written < length && this.ensureTransformed()) {
            int count = Math.min(length - written, this.transformed.length - this.transformedOffset);
            System.arraycopy(this.transformed, this.transformedOffset, bytes, offset + written, count);
            this.transformedOffset += count;
            written += count;
         }

         return written == 0 ? -1 : written;
      }

      private boolean ensureTransformed() throws IOException {
         while (this.transformedOffset == this.transformed.length) {
            if (this.finalized) {
               return false;
            }

            int count = this.source.read(this.sourceBuffer);
            if (count < 0) {
               try {
                  this.transformed = this.cipher.doFinal();
               } catch (BadPaddingException | IllegalBlockSizeException exception) {
                  throw new IOException("Could not finalize request encryption.", exception);
               }

               this.finalized = true;
            } else if (count > 0) {
               this.transformed = this.cipher.update(this.sourceBuffer, 0, count);
               if (this.transformed == null) {
                  this.transformed = new byte[0];
               }
            }

            this.transformedOffset = 0;
         }

         return true;
      }

      @Override
      public void close() throws IOException {
         this.source.close();
      }
   }

   private static final class TagSeparatingInputStream extends InputStream {
      private static final int SOURCE_BUFFER_BYTES = 8192;
      private final InputStream source;
      private final byte[] tail;
      private final byte[] sourceBuffer = new byte[8192];
      private int tailStart;
      private int tailSize;
      private int sourceOffset;
      private int sourceLength;
      private boolean exhausted;

      private TagSeparatingInputStream(InputStream source, int tagBytes) {
         this.source = Objects.requireNonNull(source, "source");
         this.tail = new byte[tagBytes];
      }

      @Override
      public int read() throws IOException {
         byte[] one = new byte[1];
         int count = this.read(one, 0, 1);
         return count < 0 ? -1 : Byte.toUnsignedInt(one[0]);
      }

      @Override
      public int read(byte[] bytes, int offset, int length) throws IOException {
         Objects.checkFromIndexSize(offset, length, bytes.length);
         if (length == 0) {
            return 0;
         }

         if (this.exhausted) {
            return -1;
         }

         int written = 0;

         while (written < length) {
            if (this.sourceOffset == this.sourceLength) {
               this.sourceLength = this.source.read(this.sourceBuffer);
               this.sourceOffset = 0;
               if (this.sourceLength < 0) {
                  this.exhausted = true;
                  break;
               }

               if (this.sourceLength == 0) {
                  int value = this.source.read();
                  if (value < 0) {
                     this.exhausted = true;
                     break;
                  }

                  this.sourceBuffer[0] = (byte)value;
                  this.sourceLength = 1;
               }
            }

            while (this.sourceOffset < this.sourceLength && written < length) {
               byte value = this.sourceBuffer[this.sourceOffset++];
               if (this.tailSize < this.tail.length) {
                  this.tail[(this.tailStart + this.tailSize) % this.tail.length] = value;
                  this.tailSize++;
               } else {
                  bytes[offset + written++] = this.tail[this.tailStart];
                  this.tail[this.tailStart] = value;
                  this.tailStart = (this.tailStart + 1) % this.tail.length;
               }
            }
         }

         return written == 0 && this.exhausted ? -1 : written;
      }

      private byte[] tag() throws IOException {
         if (this.exhausted && this.tailSize == this.tail.length) {
            byte[] tag = new byte[this.tail.length];

            for (int index = 0; index < this.tail.length; index++) {
               tag[index] = this.tail[(this.tailStart + index) % this.tail.length];
            }

            return tag;
         } else {
            throw new IOException("The encrypted request did not produce a complete authentication tag.");
         }
      }

      @Override
      public void close() throws IOException {
         this.source.close();
      }
   }

   private static final class TagSuffixInputStream extends InputStream {
      private final WeedRequestEncryptor.TagSeparatingInputStream ciphertext;
      private ByteArrayInputStream suffix;

      private TagSuffixInputStream(WeedRequestEncryptor.TagSeparatingInputStream ciphertext) {
         this.ciphertext = Objects.requireNonNull(ciphertext, "ciphertext");
      }

      @Override
      public int read() throws IOException {
         this.initialize();
         return this.suffix.read();
      }

      @Override
      public int read(byte[] bytes, int offset, int length) throws IOException {
         this.initialize();
         return this.suffix.read(bytes, offset, length);
      }

      private void initialize() throws IOException {
         if (this.suffix == null) {
            this.suffix = new ByteArrayInputStream(("." + WeedRequestEncryptor.encode(this.ciphertext.tag())).getBytes(StandardCharsets.US_ASCII));
         }
      }
   }
}
