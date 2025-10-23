package com.acme.flowsim.schema;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;

@Component
public class RefResolver {
  private final SchemaFetcher fetcher;
  public RefResolver(SchemaFetcher fetcher) { this.fetcher = fetcher; }

  public JsonNode deref(JsonNode node, URI baseDoc) { return deref(node, baseDoc, new HashSet<>()); }

  private JsonNode deref(JsonNode node, URI baseDoc, Set<String> seen) {
    if (node == null || !node.isObject() || !node.has("$ref")) return node;
    String ref = node.get("$ref").asText();
    String key = (baseDoc == null ? "" : baseDoc) + "::" + ref;
    if (!seen.add(key)) throw new IllegalStateException("Cyclic $ref detected: " + key);

    String docPart; String fragPart;
    int hash = ref.indexOf('#');
    if (hash >= 0) { docPart = ref.substring(0, hash); fragPart = ref.substring(hash + 1); }
    else { docPart = ref; fragPart = ""; }

    URI targetDoc = docPart.isBlank() ? baseDoc : URI.create(docPart);
    JsonNode doc = fetcher.fetchDocument(targetDoc, baseDoc);
    JsonNode target = fragPart.isBlank() ? doc : doc.at(JsonPointer.compile(pointerFromFragment(fragPart)));
    if (target == null || target.isMissingNode()) throw new IllegalArgumentException("Cannot resolve $ref: " + ref + " in base " + baseDoc);

    JsonNode resolved = deref(target, targetDoc, seen);
    return resolved;
  }

  private String pointerFromFragment(String fragment) {
    if (fragment.isEmpty()) return "";
    return fragment.startsWith("/") ? fragment : "/" + fragment;
  }
}
