package com.acme.flowsim.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import java.net.URI;

@Component
public class SchemaFetcher {
  private final ResourceLoader rl;
  private final ObjectMapper om;
  public SchemaFetcher(ResourceLoader rl, ObjectMapper om) { this.rl = rl; this.om = om; }
  public JsonNode fetchDocument(URI docUri, URI base) {
    try {
      URI effective = base == null ? docUri : base.resolve(docUri);
      String path = effective.toString();
      if (!path.startsWith("classpath:")) path = "classpath:/schemas/" + path;
      Resource r = rl.getResource(path);
      return om.readTree(r.getInputStream());
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot fetch schema document: " + docUri + " base=" + base, e);
    }
  }
}
