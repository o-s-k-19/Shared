package com.acme.flowsim.resolver;

import com.acme.flowsim.schema.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.*;

public class ObjectResolver implements PropertyResolver {
  private final ResolverRegistry registry;
  private final XSourceStrategies xs;
  private final EffectiveSchema eff;

  public ObjectResolver(ResolverRegistry registry, com.fasterxml.jackson.databind.ObjectMapper om, XSourceStrategies xs, EffectiveSchema eff) {
    this.registry = registry; this.xs = xs; this.eff = eff;
  }

  @Override public Object resolve(JsonNode s, GenerationContext ctx, URI baseUri) {
    Object x = xs.apply(s, ctx);
    if (x != null) return x;

    Map<String,Object> out = new LinkedHashMap<>();
    JsonNode props = s.path("properties");
    if (props.isObject()) {
      var it = props.fieldNames();
      List<String> exprKeys = new ArrayList<>();
      while (it.hasNext()) {
        String key = it.next();
        JsonNode ps = props.get(key);
        if (isExpression(ps)) { exprKeys.add(key); continue; }
        JsonNode effProp = eff.of(ps, baseUri);
        String type = effProp.path("type").asText("object");
        var r = registry.byType(type);
        Object val = r == null ? null : r.resolve(effProp, ctx, baseUri);
        out.put(key, val);
        ctx.setVar(key, val);
      }
      for (String key : exprKeys) {
        JsonNode ps = props.get(key);
        Object val = xs.apply(ps, ctx);
        out.put(key, val);
        ctx.setVar(key, val);
      }
    }
    return out;
  }

  private boolean isExpression(JsonNode prop) {
    return prop.has("x-source") && "expression".equals(prop.get("x-source").path("strategy").asText());
  }
}
