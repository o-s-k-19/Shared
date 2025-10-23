package com.acme.flowsim.resolver;

import com.acme.flowsim.schema.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class ArrayResolver implements PropertyResolver {
  private final ResolverRegistry registry;
  private final XSourceStrategies xs;
  private final EffectiveSchema eff;

  public ArrayResolver(ResolverRegistry registry, com.fasterxml.jackson.databind.ObjectMapper om, XSourceStrategies xs, EffectiveSchema eff) {
    this.registry = registry; this.xs = xs; this.eff = eff;
  }

  @Override public Object resolve(JsonNode s, GenerationContext ctx, URI baseUri) {
    Object x = xs.apply(s, ctx);
    if (x instanceof List<?> l) return l;
    if (x != null) return x;

    int min = s.path("minItems").asInt(1);
    int max = s.has("maxItems") ? s.get("maxItems").asInt(min) : min;
    if (max < min) max = min;
    int n = min + ctx.random().nextInt(Math.max(1, (max - min + 1)));

    List<Object> out = new ArrayList<>(n);
    JsonNode itemSchema = eff.of(s.path("items"), baseUri);
    String itemType = itemSchema.path("type").asText("object");
    var r = registry.byType(itemType);
    for (int i=0;i<n;i++) out.add(r == null ? null : r.resolve(itemSchema, ctx, baseUri));
    if (s.path("uniqueItems").asBoolean(false)) out = out.stream().distinct().toList();
    return out;
  }
}
