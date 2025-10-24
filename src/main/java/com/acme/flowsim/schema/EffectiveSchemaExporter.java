package com.acme.flowsim.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.net.URI;

@Service
public class EffectiveSchemaExporter {

  private final ResourceLoader rl;
  private final ObjectMapper om;
  private final SchemaLoader loader;
  private final SchemaInliner inliner;

  public EffectiveSchemaExporter(ResourceLoader rl, ObjectMapper om, SchemaLoader loader, SchemaInliner inliner) {
    this.rl = rl; this.om = om; this.loader = loader; this.inliner = inliner;
  }

  /** Exporte le schéma effectif (toutes defs inlinées) dans outDir/<name>.effective.schema.json */
  public Path export(String name, Path outDir) {
    try {
      var loaded = loader.load(name);
      ObjectNode effective = inliner.inline(loaded.root(), loaded.baseUri());
      Files.createDirectories(outDir);
      Path out = outDir.resolve(name + ".effective.schema.json");
      om.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), effective);
      return out;
    } catch (IOException e) {
      throw new RuntimeException("Export failed for schema: " + name, e);
    }
  }
}