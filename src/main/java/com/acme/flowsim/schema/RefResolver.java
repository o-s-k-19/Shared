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




package com.acme.flowsim.schema;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Résout $ref internes ("#/...") et externes ("other.json#/...") avec cache et anti-cycles. */
@Component
public class RefResolver {
  private final SchemaFetcher fetcher;
  private final ObjectMapper om;
  // Cache des documents chargés par URI effective (après résolution relative)
  private final Map<URI, JsonNode> docCache = new HashMap<>();

  public RefResolver(SchemaFetcher fetcher, ObjectMapper om) {
    this.fetcher = fetcher; this.om = om;
  }

  /** Résout un noeud qui peut contenir un $ref (direct), puis déréférence récursivement. */
  public JsonNode deref(JsonNode nodeWithRef, URI baseDoc) {
    return deref(nodeWithRef, baseDoc, new HashSet<>());
  }

  private JsonNode deref(JsonNode node, URI baseDoc, Set<String> seen) {
    if (node == null || !node.isObject() || !node.has("$ref")) return node;

    String ref = node.get("$ref").asText();
    String key = (baseDoc == null ? "" : baseDoc) + "::" + ref;
    if (!seen.add(key)) {
      throw new IllegalStateException("Cyclic $ref detected: " + key);
    }

    // Découper en partie document + fragment
    String docPart;
    String fragPart;
    int hash = ref.indexOf('#');
    if (hash >= 0) {
      docPart = ref.substring(0, hash);
      fragPart = ref.substring(hash + 1); // peut être vide
    } else {
      docPart = ref;
      fragPart = "";
    }

    // Résoudre l'URI du document cible :
    // - si docPart vide -> même document (référence interne)
    // - sinon -> resolve relative à baseDoc
    URI targetDoc = (docPart == null || docPart.isBlank())
        ? baseDoc
        : baseDoc == null ? URI.create(docPart) : baseDoc.resolve(docPart);

    // Charger le document (avec cache)
    JsonNode targetDocument = getDocument(targetDoc, baseDoc);

    // Aller au fragment : "" => racine ; sinon on interprète comme JSON Pointer
    JsonNode targetNode = fragPart.isBlank()
        ? targetDocument
        : targetDocument.at(asJsonPointer(fragPart));

    if (targetNode == null || targetNode.isMissingNode()) {
      throw new IllegalArgumentException("Cannot resolve $ref: " + ref + " in base " + baseDoc);
    }

    // Si le noeud lui-même contient un $ref, poursuivre récursivement
    return deref(targetNode, targetDoc, seen);
  }

  private JsonNode getDocument(URI effectiveDoc, URI baseDoc) {
    // Pour les références internes (#/...), effectiveDoc == baseDoc
    URI key = (effectiveDoc == null ? baseDoc : effectiveDoc);
    return docCache.computeIfAbsent(key, k -> fetcher.fetchDocument(k, baseDoc));
  }

  /** Convertit le fragment "definitions/UUID" en JSON Pointer "/definitions/UUID" avec gestion ~0,~1 si déjà encodés. */
  private JsonPointer asJsonPointer(String fragment) {
    // JSON Schema utilise JSON Pointer pour les fragments (cf. RFC 6901)
    // On s’assure de préfixer par "/" si nécessaire
    String pointer = fragment.startsWith("/") ? fragment : (fragment.isEmpty() ? "" : "/" + fragment);
    // JsonPointer.compile attend déjà les "~0" / "~1" si présents dans le schéma
    return JsonPointer.compile(pointer);
  }
}

