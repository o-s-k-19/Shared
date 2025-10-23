package com.acme.flowsim.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Repository;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Repository
public class DatasetRepository {
  private final ResourceLoader loader;
  private final String basePath;
  private final ObjectMapper om;

  public DatasetRepository(ResourceLoader loader,
                           ObjectMapper om,
                           @Value("${simulator.datasets.basePath:classpath:/datasets}") String basePath) {
    this.loader = loader; this.om = om;
    this.basePath = basePath.endsWith("/") ? basePath : basePath + "/";
  }

  public List<Map<String,String>> loadCsv(String name, char sep) {
    String path = basePath + name;
    try {
      Resource r = loader.getResource(path);
      try (var br = new BufferedReader(new InputStreamReader(r.getInputStream(), StandardCharsets.UTF_8))) {
        String header = br.readLine();
        if (header == null) return List.of();
        String[] cols = header.split(String.valueOf(sep));
        List<Map<String,String>> rows = new ArrayList<>();
        for (String line; (line = br.readLine()) != null; ) {
          String[] parts = line.split(String.valueOf(sep), -1);
          Map<String,String> m = new LinkedHashMap<>();
          for (int i=0;i<cols.length && i<parts.length;i++) m.put(cols[i], parts[i]);
          rows.add(m);
        }
        return rows;
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed loading CSV: " + path, e);
    }
  }
}
