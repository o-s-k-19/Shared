package com.acme.flowsim.sink;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class FileSink {
  private final Path baseDir;
  private final ObjectMapper om;

  public FileSink(@Value("${simulator.output.baseDir:./out}") String baseDir, ObjectMapper om) {
    this.baseDir = Paths.get(baseDir); this.om = om;
  }

  public void writeNdjson(String jobId, Object event) {
    try {
      Path dir = baseDir.resolve(jobId);
      Files.createDirectories(dir);
      Path file = dir.resolve("events.ndjson");
      try (var out = new BufferedWriter(new FileWriter(file.toFile(), true))) {
        out.write(om.writeValueAsString(event));
        out.write("\n");
      }
    } catch (IOException e) { throw new UncheckedIOException(e); }
  }
}
