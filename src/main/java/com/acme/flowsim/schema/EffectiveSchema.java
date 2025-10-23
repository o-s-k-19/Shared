package com.acme.flowsim.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.net.URI;

@Component
public class EffectiveSchema {
  private final RefResolver refResolver;
  private final ObjectMapper om;
  public EffectiveSchema(RefResolver rr, ObjectMapper om) { this.refResolver = rr; this.om = om; }

  public JsonNode of(JsonNode schemaNode, URI baseDoc) {
    JsonNode s = refResolver.deref(schemaNode, baseDoc);
    if (s.has("allOf") && s.get("allOf").isArray()) return mergeAllOf((ArrayNode)s.get("allOf"), baseDoc);
    if (s.has("oneOf") && s.get("oneOf").isArray()) {
      for (JsonNode cand : s.get("oneOf")) {
        JsonNode eff = of(cand, baseDoc);
        if (eff.has("type")) return eff;
      }
    }
    if (s.has("anyOf") && s.get("anyOf").isArray()) {
      for (JsonNode cand : s.get("anyOf")) {
        JsonNode eff = of(cand, baseDoc);
        if (eff.has("type")) return eff;
      }
    }
    return s;
  }

  public String effectiveType(JsonNode schemaNode, URI baseDoc) {
    JsonNode eff = of(schemaNode, baseDoc);
    return eff.path("type").isTextual() ? eff.get("type").asText() : null;
  }

  private JsonNode mergeAllOf(ArrayNode all, URI base) {
    ObjectNode acc = om.createObjectNode();
    for (JsonNode part : all) {
      JsonNode eff = of(part, base);
      eff.fields().forEachRemaining(e -> acc.set(e.getKey(), e.getValue()));
    }
    return acc;
  }
}
