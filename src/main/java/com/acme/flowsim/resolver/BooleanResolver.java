package com.acme.flowsim.resolver;

import com.acme.flowsim.schema.GenerationContext;
import com.acme.flowsim.schema.PropertyResolver;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;

public class BooleanResolver implements PropertyResolver {
  private final XSourceStrategies xs;
  public BooleanResolver(XSourceStrategies xs) { this.xs = xs; }
  @Override public Object resolve(JsonNode s, GenerationContext ctx, URI baseUri) {
    Object x = xs.apply(s, ctx);
    if (x instanceof Boolean b) return b;
    if (s.has("default")) return s.get("default").asBoolean(false);
    return ctx.random().nextBoolean();
  }
}
