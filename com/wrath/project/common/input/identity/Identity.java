package com.wrath.project.common.input.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.wrath.project.common.json.JsonUtils;
import java.util.Set;

public record Identity(String discordId, String machineId, String apiToken, String sessionId) {
   private static final Set<String> PROPERTIES = Set.of("discordId", "machineId", "apiToken", "sessionId");

   public Identity {
      discordId = requireTrimmed(discordId);
      machineId = requireTrimmed(machineId);
      apiToken = requireTrimmed(apiToken);
      sessionId = requireTrimmed(sessionId);
   }

   public static Identity fromJson(String json) {
      if (json == null) {
         throw invalidIdentity();
      }

      try {
         JsonNode configuration = JsonUtils.parseObject(json, PROPERTIES);
         return new Identity(
            JsonUtils.requiredText(configuration, "discordId"),
            JsonUtils.requiredText(configuration, "machineId"),
            JsonUtils.requiredText(configuration, "apiToken"),
            JsonUtils.requiredText(configuration, "sessionId")
         );
      } catch (IllegalArgumentException exception) {
         throw invalidIdentity();
      }
   }

   private static String requireTrimmed(String value) {
      if (value != null && !value.trim().isEmpty()) {
         return value.trim();
      } else {
         throw invalidIdentity();
      }
   }

   private static IllegalArgumentException invalidIdentity() {
      return new IllegalArgumentException("The module identity JSON is invalid.");
   }
}
