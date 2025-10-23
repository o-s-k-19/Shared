package com.acme.flowsim.schema;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
public interface PropertyResolver {
  Object resolve(JsonNode schemaNode, GenerationContext ctx, URI baseUri);
}
