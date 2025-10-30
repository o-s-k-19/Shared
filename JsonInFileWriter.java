import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.*;

public class JsonWriter {
  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT);

  public static void writePojo(Object pojo, Path targetFile) throws Exception {
    Files.createDirectories(targetFile.getParent());        // crée le dossier si besoin
    MAPPER.writeValue(targetFile.toFile(), pojo);           // sérialise en JSON
  }

  public static void main(String[] args) throws Exception {
    Path out = Paths.get("/var/data/exports/paiement.json"); // HORS classpath
    writePojo(new Paiement("REF-0001", 199.99, "EUR"), out);
  }
}


import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

public class NdjsonWriter {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static <T> void appendNdjson(List<T> batch, Path file) throws Exception {
    Files.createDirectories(file.getParent());
    try (BufferedWriter w = Files.newBufferedWriter(file,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
      for (T item : batch) {
        try (JsonGenerator g = MAPPER.getFactory().createGenerator(w)) {
          MAPPER.writeValue(g, item); // écrit sur la ligne
        }
        w.newLine();
      }
    }
  }
}


import java.nio.file.*;
import static java.nio.file.StandardCopyOption.*;

public class AtomicFiles {
  public static void atomicWrite(byte[] bytes, Path target) throws Exception {
    Files.createDirectories(target.getParent());
    Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
    Files.write(tmp, bytes);
    try {
      Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, target, REPLACE_EXISTING); // fallback si FS ne supporte pas ATOMIC_MOVE
    }
  }
}


app:
  export-dir: /data/exports   # monte /data en volume Docker


import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProps(String exportDir) {}


import org.springframework.stereotype.Service;
import java.nio.file.*;

@Service
public class ExportService {
  private final Path baseDir;
  public ExportService(AppProps props) { this.baseDir = Path.of(props.exportDir()); }

  public Path writePayment(Paiement p) throws Exception {
    Path file = baseDir.resolve("paiements").resolve(p.reference() + ".json");
    Files.createDirectories(file.getParent());
    new com.fasterxml.jackson.databind.ObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(file.toFile(), p);
    return file;
  }
}

services:
  app:
    image: myorg/myapp:latest
    environment:
      - APP_EXPORT-DIR=/data/exports
    volumes:
      - ./exports:/data/exports   # HORS classpath → sur l’hôte


