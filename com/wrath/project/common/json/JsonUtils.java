package com.wrath.project.common.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.Set;

public final class JsonUtils {
   private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

   private JsonUtils() {
   }

   public static JsonNode parseObject(String value, Set<String> properties) {
      JsonNode node;
      try {
         node = OBJECT_MAPPER.readTree(value);
      } catch (JsonProcessingException | IllegalArgumentException exception) {
         throw new IllegalArgumentException("The application configuration JSON is invalid");
      }

      validateObject(node, properties);
      return node;
   }

   public static void validateObject(JsonNode node, Set<String> properties) {
      if (node != null && node.isObject() && node.size() == properties.size()) {
         Iterator<String> names = node.fieldNames();

         while (names.hasNext()) {
            if (!properties.contains(names.next())) {
               throw new IllegalArgumentException("The application configuration JSON is invalid");
            }
         }
      } else {
         throw new IllegalArgumentException("The application configuration JSON is invalid");
      }
   }

   public static JsonNode requiredObject(JsonNode parent, String property, Set<String> properties) {
      JsonNode node = parent.get(property);
      validateObject(node, properties);
      return node;
   }

   public static JsonNode requiredArray(JsonNode parent, String property) {
      JsonNode node = parent.get(property);
      if (node != null && node.isArray()) {
         return node;
      } else {
         throw new IllegalArgumentException("The application configuration JSON is invalid");
      }
   }

   public static String requiredText(JsonNode parent, String property) {
      JsonNode node = parent.get(property);
      if (node != null && node.isTextual()) {
         return node.textValue();
      } else {
         throw new IllegalArgumentException("The application configuration JSON is invalid");
      }
   }

   public static boolean requiredBoolean(JsonNode parent, String property) {
      JsonNode node = parent.get(property);
      if (node != null && node.isBoolean()) {
         return node.booleanValue();
      } else {
         throw new IllegalArgumentException("The application configuration JSON is invalid");
      }
   }

   public static int requiredInt(JsonNode parent, String property) {
      JsonNode node = parent.get(property);
      if (node != null && node.isIntegralNumber() && node.canConvertToInt()) {
         return node.intValue();
      } else {
         throw new IllegalArgumentException("The application configuration JSON is invalid");
      }
   }

   public static int requiredPositiveInt(JsonNode parent, String property) {
      int value = requiredInt(parent, property);
      if (value <= 0) {
         throw new IllegalArgumentException("The application configuration JSON is invalid");
      } else {
         return value;
      }
   }

   public static int requiredNonNegativeInt(JsonNode parent, String property) {
      int value = requiredInt(parent, property);
      if (value < 0) {
         throw new IllegalArgumentException("The application configuration JSON is invalid");
      } else {
         return value;
      }
   }

   public static long requiredLong(JsonNode parent, String property) {
      JsonNode node = parent.get(property);
      if (node != null && node.isIntegralNumber() && node.canConvertToLong()) {
         return node.longValue();
      } else {
         throw new IllegalArgumentException("The application configuration JSON is invalid");
      }
   }

   public static long requiredPositiveLong(JsonNode parent, String property) {
      long value = requiredLong(parent, property);
      if (value <= 0L) {
         throw new IllegalArgumentException("The application configuration JSON is invalid");
      } else {
         return value;
      }
   }

   public static long requiredNonNegativeLong(JsonNode parent, String property) {
      long value = requiredLong(parent, property);
      if (value < 0L) {
         throw new IllegalArgumentException("The application configuration JSON is invalid");
      } else {
         return value;
      }
   }
}
