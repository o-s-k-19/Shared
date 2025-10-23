package com.acme.flowsim.resolver;

import com.acme.flowsim.schema.GenerationContext;
import com.acme.flowsim.schema.PropertyResolver;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;

public class IntegerResolver implements PropertyResolver {
  private final XSourceStrategies xs;
  public IntegerResolver(XSourceStrategies xs) { this.xs = xs; }
  @Override public Object resolve(JsonNode s, GenerationContext ctx, URI baseUri) {
    Object x = xs.apply(s, ctx);
    if (x instanceof Number n) return n.intValue();
    int min = s.path("minimum").asInt(0);
    int max = s.has("maximum") ? s.get("maximum").asInt(min + 100) : (min + 100);
    int bound = Math.max(1, (max - min + 1));
    int v = min + ctx.random().nextInt(bound);
    if (s.has("multipleOf")) { int m = s.get("multipleOf").asInt(1); v = Math.round(v / (float)m) * m; }
    return v;
  }
}
