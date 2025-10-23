package com.acme.flowsim.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class SchemaGenerator {
  private final SchemaLoader loader;
  private final ResolverRegistry registry;
  private final EffectiveSchema effective;
  private final ObjectMapper om;

  public SchemaGenerator(SchemaLoader loader, ResolverRegistry registry, EffectiveSchema effective, ObjectMapper om) {
    this.loader = loader; this.registry = registry; this.effective = effective; this.om = om;
  }

  public Map<String,Object> generate(String schemaName) {
    var loaded = loader.load(schemaName);
    var ctx = new GenerationContext();
    var effRoot = effective.of(loaded.root(), loaded.baseUri());
    String type = effRoot.path("type").asText("object");
    var resolver = registry.byType(type);
    Object obj = resolver.resolve(effRoot, ctx, loaded.baseUri());
    return om.convertValue(obj, Map.class);
  }
}
