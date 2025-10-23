package com.acme.flowsim.resolver;

import com.acme.flowsim.schema.GenerationContext;
import com.acme.flowsim.schema.PropertyResolver;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public class StringResolver implements PropertyResolver {
  private final XSourceStrategies xs;
  public StringResolver(XSourceStrategies xs) { this.xs = xs; }
  @Override public Object resolve(JsonNode s, GenerationContext ctx, URI baseUri) {
    Object x = xs.apply(s, ctx);
    if (x instanceof String str) return str;
    if (x != null) return String.valueOf(x);
    if (s.has("enum") && s.get("enum").isArray() && s.get("enum").size() > 0) return s.get("enum").get(0).asText();
    String fmt = s.path("format").asText("");
    return switch (fmt) { case "uuid" -> UUID.randomUUID().toString(); case "date-time" -> Instant.now().toString(); case "email" -> "user@example.com"; default -> "text"; };
  }
}
