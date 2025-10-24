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





package com.acme.flowsim.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.*;

@Service
public class EffectiveSchemaExporter {

  private final ResourceLoader rl;
  private final ObjectMapper om;
  private final SchemaLoader loader;
  private final SchemaInliner inliner;
  private final String schemasBase;     // ex: "classpath:/schemas/"
  private final Path fallbackDir;       // ex: "./out/effective-schemas"

  public EffectiveSchemaExporter(ResourceLoader rl,
                                 ObjectMapper om,
                                 SchemaLoader loader,
                                 SchemaInliner inliner,
                                 @Value("${simulator.schemas.basePath:classpath:/schemas/}") String schemasBase,
                                 @Value("${simulator.schemas.fallbackDir:./out/effective-schemas}") String fallbackDir
  ) {
    this.rl = rl; this.om = om; this.loader = loader; this.inliner = inliner;
    this.schemasBase = schemasBase.endsWith("/") ? schemasBase : schemasBase + "/";
    this.fallbackDir = Paths.get(fallbackDir);
  }

  /** Exporte <name>.effective.schema.json dans le même répertoire que <name>.schema.json si possible,
   *  sinon dans fallbackDir. */
  public Path export(String name) {
    try {
      // 1) Construire le schéma effectif
      var loaded = loader.load(name);
      ObjectNode effective = inliner.inline(loaded.root(), loaded.baseUri());

      // 2) Résoudre l'emplacement du schéma d’origine
      String schemaRes = schemasBase + name + ".schema.json";
      Resource r = rl.getResource(schemaRes);
      URL url = r.getURL(); // peut être "file:" ou "jar:"

      Path outDir;
      if ("file".equalsIgnoreCase(url.getProtocol())) {
        // OK: on peut écrire à côté du schéma source
        Path schemaPath = Paths.get(url.toURI());
        outDir = schemaPath.getParent();
      } else {
        // Pas un fichier disque (e.g. jar:) -> fallback
        outDir = fallbackDir;
      }

      Files.createDirectories(outDir);
      Path out = outDir.resolve(name + ".effective.schema.json");
      om.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), effective);
      return out;

    } catch (IOException e) {
      throw new RuntimeException("Export failed for schema: " + name, e);
    } catch (Exception e) {
      throw new RuntimeException("Cannot resolve physical path for schema: " + name, e);
    }
  }

public Path exportToDirectory(String name, Path outDir) {
  try {
    var loaded = loader.load(name); // charge depuis classpath:/schemas/<name>.schema.json
    var effective = inliner.inlineNoRefs(loaded.root(), loaded.baseUri());
    Files.createDirectories(outDir);
    Path out = outDir.resolve(name + ".effective.schema.json");
    om.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), effective);
    return out;
  } catch (Exception e) {
    throw new RuntimeException("Export failed for schema: " + name, e);
  }
}




}