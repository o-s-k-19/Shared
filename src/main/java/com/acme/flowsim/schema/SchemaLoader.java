package com.acme.flowsim.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

@Component
public class SchemaLoader {
  private final ResourceLoader rl; private final ObjectMapper om;
  public SchemaLoader(ResourceLoader rl, ObjectMapper om) { this.rl = rl; this.om = om; }

  public LoadedSchema load(String name) {
    try {
      String path = "classpath:/schemas/" + name + ".schema.json";
      Resource r = rl.getResource(path);
      return new LoadedSchema(om.readTree(r.getInputStream()), java.net.URI.create(path));
    } catch (Exception e) {
      throw new IllegalArgumentException("Schema not found: " + name, e);
    }
  }

  public record LoadedSchema(com.fasterxml.jackson.databind.JsonNode root, java.net.URI baseUri) {}
}
