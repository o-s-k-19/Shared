package com.acme.flowsim.resolver;

import com.acme.flowsim.schema.GenerationContext;
import com.acme.flowsim.schema.PropertyResolver;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;

public class NumberResolver implements PropertyResolver {
  private final XSourceStrategies xs;
  public NumberResolver(XSourceStrategies xs) { this.xs = xs; }
  @Override public Object resolve(JsonNode s, GenerationContext ctx, URI baseUri) {
    Object x = xs.apply(s, ctx);
    if (x instanceof Number n) return n.doubleValue();
    double min = s.path("minimum").asDouble(0);
    double max = s.has("maximum") ? s.get("maximum").asDouble(min + 100) : (min + 100);
    double v = min + ctx.random().nextDouble() * (max - min);
    if (s.has("multipleOf")) { double m = s.get("multipleOf").asDouble(1.0); v = Math.round(v / m) * m; }
    return v;
  }
}
