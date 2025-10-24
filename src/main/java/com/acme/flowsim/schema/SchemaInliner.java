package com.acme.flowsim.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.*;
import java.util.function.Consumer;

@Component
public class SchemaInliner {

  private final RefResolver refResolver;
  private final ObjectMapper om;

  public SchemaInliner(RefResolver refResolver, ObjectMapper om) {
    this.refResolver = refResolver;
    this.om = om;
  }

  /** Retourne un schéma "effectif" avec toutes les références inlinées (y compris imbriquées). */
  public ObjectNode inline(JsonNode root, URI baseUri) {
    JsonNode deref = derefAndMergeIfLocalKeywords(root, baseUri, new HashSet<>());
    return inlineNode(deref, baseUri, new HashSet<>());
  }

  /* --- Résolution --- */

  private JsonNode derefAndMergeIfLocalKeywords(JsonNode node, URI base, Set<String> seen) {
    if (node == null || !node.isObject()) return node;

    ObjectNode obj = (ObjectNode) node;
    if (!obj.has("$ref")) return obj;

    String ref = obj.get("$ref").asText();
    // 1) Résoudre le $ref (gestion récursive déjà dans RefResolver)
    JsonNode resolved = refResolver.deref(obj, base);

    // 2) S'il existe d'autres mots-clés locaux à côté de $ref -> allOf implicite
    ObjectNode locals = obj.deepCopy();
    locals.remove("$ref");
    if (locals.size() == 0) {
      return resolved; // juste le $ref
    }

    // allOf = [ resolved, locals ]
    ArrayNode allOf = om.createArrayNode().add(resolved).add(locals);
    ObjectNode mergedCarrier = om.createObjectNode();
    mergedCarrier.set("allOf", allOf);

    // On laisse la fusion à mergeAllOf(...)
    return mergeAllOf(mergedCarrier, base, seen);
  }

  private ObjectNode inlineNode(JsonNode node, URI base, Set<String> seen) {
    node = derefAndMergeIfLocalKeywords(node, base, seen);
    if (!node.isObject()) return node.deepCopy();

    ObjectNode obj = (ObjectNode) node.deepCopy();

    // allOf / anyOf / oneOf -> on tente de fusionner allOf, et pour anyOf/oneOf on ne choisit pas arbitrairement.
    if (obj.has("allOf") && obj.get("allOf").isArray()) {
      obj = mergeAllOf(obj, base, seen);
    }

    // Inline récursif selon le type
    String type = obj.path("type").asText(null);
    if ("object".equals(type)) {
      // properties
      JsonNode props = obj.path("properties");
      if (props.isObject()) {
        ObjectNode inlinedProps = om.createObjectNode();
        props.fields().forEachRemaining(e -> {
          ObjectNode inlined = inlineNode(e.getValue(), base, seen);
          inlinedProps.set(e.getKey(), inlined);
        });
        obj.set("properties", inlinedProps);
      }
      // additionalProperties
      if (obj.has("additionalProperties")) {
        JsonNode ap = obj.get("additionalProperties");
        if (ap.isObject()) obj.set("additionalProperties", inlineNode(ap, base, seen));
      }
      // patternProperties
      if (obj.has("patternProperties")) {
        ObjectNode pp = om.createObjectNode();
        obj.get("patternProperties").fields().forEachRemaining(e ->
          pp.set(e.getKey(), inlineNode(e.getValue(), base, seen))
        );
        obj.set("patternProperties", pp);
      }
    } else if ("array".equals(type)) {
      if (obj.has("items")) {
        JsonNode items = obj.get("items");
        if (items.isObject()) {
          obj.set("items", inlineNode(items, base, seen));
        } else if (items.isArray()) {
          ArrayNode arr = om.createArrayNode();
          items.forEach(it -> arr.add(inlineNode(it, base, seen)));
          obj.set("items", arr);
        }
      }
      if (obj.has("contains") && obj.get("contains").isObject()) {
        obj.set("contains", inlineNode(obj.get("contains"), base, seen));
      }
    }

    return obj;
  }

  /* --- Fusion de allOf --- */
  private ObjectNode mergeAllOf(ObjectNode nodeWithAllOf, URI base, Set<String> seen) {
    ArrayNode all = (ArrayNode) nodeWithAllOf.get("allOf");
    List<ObjectNode> parts = new ArrayList<>();
    for (JsonNode part : all) {
      ObjectNode eff = inlineNode(part, base, seen);
      parts.add(eff);
    }
    // Fusion naïve mais utile : type, properties, required, items, constraints scalaires (locals priment)
    ObjectNode acc = om.createObjectNode();
    for (ObjectNode p : parts) {
      mergeInto(acc, p);
    }
    // Les autres champs de nodeWithAllOf (hors allOf) priment aussi
    nodeWithAllOf.fields().forEachRemaining(e -> {
      if (!"allOf".equals(e.getKey())) acc.set(e.getKey(), e.getValue());
    });
    return acc;
  }

  @SuppressWarnings("unchecked")
  private void mergeInto(ObjectNode target, ObjectNode src) {
    // 1) type: si absent sur target -> copier
    if (src.has("type") && !target.has("type")) {
      target.set("type", src.get("type"));
    }

    // 2) object: properties + required
    if ("object".equals(src.path("type").asText(null))) {
      // properties
      if (src.has("properties")) {
        ObjectNode tgtProps = target.has("properties") && target.get("properties").isObject()
          ? (ObjectNode) target.get("properties") : om.createObjectNode();
        ObjectNode srcProps = (ObjectNode) src.get("properties");
        srcProps.fields().forEachRemaining(e -> {
          if (tgtProps.has(e.getKey()) && tgtProps.get(e.getKey()).isObject() && e.getValue().isObject()) {
            ObjectNode merged = om.createObjectNode();
            mergeInto(merged, (ObjectNode) tgtProps.get(e.getKey()));
            mergeInto(merged, (ObjectNode) e.getValue());
            tgtProps.set(e.getKey(), merged);
          } else {
            tgtProps.set(e.getKey(), e.getValue());
          }
        });
        target.set("properties", tgtProps);
      }
      // required (union)
      if (src.has("required") && src.get("required").isArray()) {
        Set<String> union = new LinkedHashSet<>();
        Consumer<ArrayNode> addAll = arr -> arr.forEach(n -> union.add(n.asText()));
        if (target.has("required") && target.get("required").isArray()) addAll.accept((ArrayNode) target.get("required"));
        addAll.accept((ArrayNode) src.get("required"));
        ArrayNode mergedReq = om.createArrayNode();
        union.forEach(mergedReq::add);
        target.set("required", mergedReq);
      }
      // additionalProperties : la valeur la plus spécifique/locale prime
      if (src.has("additionalProperties")) target.set("additionalProperties", src.get("additionalProperties"));
      // patternProperties
      if (src.has("patternProperties")) {
        ObjectNode tgt = target.has("patternProperties") && target.get("patternProperties").isObject()
          ? (ObjectNode) target.get("patternProperties") : om.createObjectNode();
        ((ObjectNode) src.get("patternProperties")).fields().forEachRemaining(e -> tgt.set(e.getKey(), e.getValue()));
        target.set("patternProperties", tgt);
      }
    }

    // 3) array: items + contains
    if ("array".equals(src.path("type").asText(null))) {
      if (src.has("items")) target.set("items", src.get("items"));
      if (src.has("contains")) target.set("contains", src.get("contains"));
      copyIfPresent(src, target, "minItems", "maxItems", "uniqueItems");
    }

    // 4) scalaires (number/integer/string/boolean): on laisse src écraser si conflit
    if (src.has("format")) target.set("format", src.get("format"));
    copyIfPresent(src, target, "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minLength", "maxLength", "pattern", "enum", "const", "default");
  }

  private void copyIfPresent(ObjectNode src, ObjectNode tgt, String... keys) {
    for (String k : keys) if (src.has(k)) tgt.set(k, src.get(k));
  }
}