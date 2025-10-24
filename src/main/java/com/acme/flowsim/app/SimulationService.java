package com.acme.flowsim.app;

import com.acme.flowsim.api.SimulationRequest;
import com.acme.flowsim.sink.FileSink;
import com.acme.flowsim.sink.KafkaSink;
import com.acme.flowsim.schema.SchemaGenerator;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class SimulationService {
  private final SchemaGenerator generator;
  private final FileSink fileSink;
  private final KafkaSink kafkaSink;

  public SimulationService(SchemaGenerator generator, FileSink fileSink, KafkaSink kafkaSink) {
    this.generator = generator; this.fileSink = fileSink; this.kafkaSink = kafkaSink;
  }

  public String run(SimulationRequest req) {
    String jobId = UUID.randomUUID().toString();
    int rate = Math.max(req.rate(), 1);
    long pauseMs = 1000L / rate;

    for (int i=0; i<req.count(); i++) {
      Map<String,Object> payload = generator.generate(req.schema());
      Map<String,Object> envelope = Map.of(
        "type", req.schema(),
        "id", UUID.randomUUID().toString(),
        "timestamp", Instant.now().toString(),
        "data", payload
      );

      switch (req.destination()) {
        case FILE -> fileSink.writeNdjson(jobId, envelope);
        case KAFKA -> { if (req.topic() != null) kafkaSink.send(req.topic(), req.schema()+":"+UUID.randomUUID(), envelope); }
        case BOTH -> {
          fileSink.writeNdjson(jobId, envelope);
          if (req.topic() != null) kafkaSink.send(req.topic(), req.schema()+":"+UUID.randomUUID(), envelope);
        }
        default -> {}
      }
      try { Thread.sleep(pauseMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
    return jobId;
  }
}



package com.acme.flowsim.tools;

import com.acme.flowsim.schema.EffectiveSchemaExporter;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class SchemaExportCli {
  public static void main(String[] args) throws Exception {
    // args attend : --sourceDir=src/main/resources/schemas --outputDir=src/main/resources/effective-schemas
    String sourceDir = argValue(args, "--sourceDir=", "src/main/resources/schemas");
    String outputDir = argValue(args, "--outputDir=", "src/main/resources/effective-schemas");

    try (ConfigurableApplicationContext ctx =
        new SpringApplicationBuilder(com.acme.flowsim.Application.class)
            .web(WebApplicationType.NONE)
            .run(args)) {

      EffectiveSchemaExporter exporter = ctx.getBean(EffectiveSchemaExporter.class);

      Path src = Path.of(sourceDir);
      Path out = Path.of(outputDir);

      List<String> names = discoverSchemaNames(src); // ex: order, transaction…

      for (String name : names) {
        var path = exporter.exportToDirectory(name, out);
        System.out.println("Effective schema generated: " + path.toAbsolutePath());
      }
    }
  }

  private static List<String> discoverSchemaNames(Path src) throws IOException {
    List<String> names = new ArrayList<>();
    try (var s = Files.walk(src)) {
      s.filter(p -> p.getFileName().toString().endsWith(".schema.json"))
       .forEach(p -> {
         String file = p.getFileName().toString();
         names.add(file.substring(0, file.length() - ".schema.json".length())); // "order.schema.json" -> "order"
       });
    }
    return names;
  }

  private static String argValue(String[] args, String key, String def) {
    for (String a : args) if (a.startsWith(key)) return a.substring(key.length());
    return def;
  }
}
