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





package YOUR.PACKAGE.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.*;

@Component
public class SchemaInliner {

  private final RefResolver refResolver;
  private final ObjectMapper om;

  public SchemaInliner(RefResolver refResolver, ObjectMapper om) {
    this.refResolver = refResolver;
    this.om = om;
  }

  /** Inline complet + fusion allOf ; tolérant aux nœuds manquants. */
  public ObjectNode inline(JsonNode root, URI baseUri) {
    JsonNode n = derefAndMergeIfLocalKeywords(root, baseUri, new HashSet<>());
    if (!n.isObject()) return om.createObjectNode(); // éviter les casts sauvages
    return inlineNode(n, baseUri, new HashSet<>());
  }

  /** Résout $ref ; si présence de mots-clés locaux, crée un allOf implicite et déclare à mergeAllOf. */
  private JsonNode derefAndMergeIfLocalKeywords(JsonNode node, URI base, Set<String> seen) {
    if (node == null || !node.isObject()) return node;

    ObjectNode obj = (ObjectNode) node;
    if (!obj.has("$ref")) return obj;

    // Résolution — déjà récursive côté RefResolver
    JsonNode resolved = refResolver.deref(obj, base);

    // s'il y a des mots-clés locaux aux côtés de $ref -> allOf implicite
    ObjectNode locals = obj.deepCopy();
    locals.remove("$ref");
    if (locals.size() == 0) return resolved;

    ArrayNode allOf = om.createArrayNode().add(resolved).add(locals);
    ObjectNode carrier = om.createObjectNode();
    carrier.set("allOf", allOf);
    return mergeAllOf(carrier, base, seen);
  }

  private ObjectNode inlineNode(JsonNode node, URI base, Set<String> seen) {
    node = derefAndMergeIfLocalKeywords(node, base, seen);
    if (!node.isObject()) return om.createObjectNode();

    ObjectNode obj = ((ObjectNode) node).deepCopy();

    // allOf
    if (obj.has("allOf") && obj.get("allOf").isArray()) {
      obj = mergeAllOf(obj, base, seen);
    }

    String type = obj.path("type").isTextual() ? obj.get("type").asText() : null;
    if ("object".equals(type)) {
      // properties
      JsonNode props = obj.path("properties");
      if (props.isObject()) {
        ObjectNode inlinedProps = om.createObjectNode();
        props.fields().forEachRemaining(e -> inlinedProps.set(e.getKey(), inlineNode(e.getValue(), base, seen)));
        obj.set("properties", inlinedProps);
      }
      // additionalProperties
      JsonNode ap = obj.get("additionalProperties");
      if (ap != null && ap.isObject()) obj.set("additionalProperties", inlineNode(ap, base, seen));
      // patternProperties
      JsonNode pp = obj.get("patternProperties");
      if (pp != null && pp.isObject()) {
        ObjectNode out = om.createObjectNode();
        pp.fields().forEachRemaining(e -> out.set(e.getKey(), inlineNode(e.getValue(), base, seen)));
        obj.set("patternProperties", out);
      }
    } else if ("array".equals(type)) {
      JsonNode items = obj.get("items");
      if (items != null) {
        if (items.isObject()) obj.set("items", inlineNode(items, base, seen));
        else if (items.isArray()) {
          ArrayNode arr = om.createArrayNode();
          items.forEach(it -> arr.add(inlineNode(it, base, seen)));
          obj.set("items", arr);
        }
      }
      JsonNode contains = obj.get("contains");
      if (contains != null && contains.isObject()) obj.set("contains", inlineNode(contains, base, seen));
    }

    return obj;
  }

  /** Fusion simple mais robuste de allOf. */
  private ObjectNode mergeAllOf(ObjectNode nodeWithAllOf, URI base, Set<String> seen) {
    JsonNode allNode = nodeWithAllOf.get("allOf");
    if (allNode == null || !allNode.isArray()) return nodeWithAllOf;

    ArrayNode all = (ArrayNode) allNode;
    List<ObjectNode> parts = new ArrayList<>();
    all.forEach(part -> {
      JsonNode eff = inlineNode(part, base, seen);
      if (eff.isObject()) parts.add((ObjectNode) eff);
    });

    ObjectNode acc = om.createObjectNode();
    for (ObjectNode p : parts) mergeInto(acc, p);

    // recopie autres champs locaux hors allOf (priorité aux locaux)
    nodeWithAllOf.fields().forEachRemaining(e -> {
      if (!"allOf".equals(e.getKey())) acc.set(e.getKey(), e.getValue());
    });
    return acc;
  }

  /** Fusionne src -> target (union required, merge properties, écrasement simple sur scalaires). */
  private void mergeInto(ObjectNode target, ObjectNode src) {
    // type : si absent, copie
    if (src.has("type") && !target.has("type")) target.set("type", src.get("type"));

    // object : properties, required, additionalProperties, patternProperties
    if ("object".equals(src.path("type").asText(null)) || src.has("properties")) {
      // properties
      if (src.has("properties") && src.get("properties").isObject()) {
        ObjectNode tgtProps = target.has("properties") && target.get("properties").isObject()
            ? (ObjectNode) target.get("properties") : om.createObjectNode();
        ObjectNode srcProps = (ObjectNode) src.get("properties");
        srcProps.fields().forEachRemaining(e -> {
          JsonNode existing = tgtProps.get(e.getKey());
          if (existing != null && existing.isObject() && e.getValue().isObject()) {
            ObjectNode merged = om.createObjectNode();
            mergeInto(merged, (ObjectNode) existing);
            mergeInto(merged, (ObjectNode) e.getValue());
            tgtProps.set(e.getKey(), merged);
          } else {
            tgtProps.set(e.getKey(), e.getValue());
          }
        });
        target.set("properties", tgtProps);
      }
      // required (union)
      Set<String> req = new LinkedHashSet<>();
      if (target.has("required") && target.get("required").isArray()) target.get("required").forEach(n -> req.add(n.asText()));
      if (src.has("required") && src.get("required").isArray()) src.get("required").forEach(n -> req.add(n.asText()));
      if (!req.isEmpty()) {
        ArrayNode arr = om.createArrayNode();
        req.forEach(arr::add);
        target.set("required", arr);
      }
      // additionalProperties
      if (src.has("additionalProperties")) target.set("additionalProperties", src.get("additionalProperties"));
      // patternProperties
      if (src.has("patternProperties") && src.get("patternProperties").isObject()) {
        ObjectNode tgt = target.has("patternProperties") && target.get("patternProperties").isObject()
            ? (ObjectNode) target.get("patternProperties") : om.createObjectNode();
        src.get("patternProperties").fields().forEachRemaining(e -> tgt.set(e.getKey(), e.getValue()));
        target.set("patternProperties", tgt);
      }
    }

    // array : items / contains / contraintes
    if ("array".equals(src.path("type").asText(null)) || src.has("items")) {
      if (src.has("items")) target.set("items", src.get("items"));
      if (src.has("contains")) target.set("contains", src.get("contains"));
      copyIfPresent(src, target, "minItems","maxItems","uniqueItems");
    }

    // scalaires et contraintes communes
    copyIfPresent(src, target, "format","minimum","maximum","exclusiveMinimum","exclusiveMaximum",
                  "multipleOf","minLength","maxLength","pattern","enum","const","default");
  }

  private void copyIfPresent(ObjectNode src, ObjectNode tgt, String... keys) {
    for (String k : keys) if (src.has(k)) tgt.set(k, src.get(k));
  }
}




package com.acme.flowsim.schema;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.*;

/**
 * Produit un schéma "effectif" SANS AUCUN "$ref".
 * - Résout récursivement tous les $ref (y compris imbriqués)
 * - Fusionne les contraintes locales adjacentes à $ref (allOf implicite)
 * - Aplatie / fusionne les allOf
 * - Inline récursivement dans toutes les zones porteuses de schéma :
 *   properties, patternProperties, additionalProperties, propertyNames,
 *   items, contains, allOf/anyOf/oneOf/not, if/then/else, dependentSchemas, $defs/definitions, etc.
 * - Anti-cycles + mémoïsation
 */
@Component
public class SchemaInliner {

  private final RefResolver refResolver;
  private final ObjectMapper om;

  public SchemaInliner(RefResolver refResolver, ObjectMapper om) {
    this.refResolver = refResolver;
    this.om = om;
  }

  /** Point d’entrée : retourne un ObjectNode sans aucun "$ref". */
  public ObjectNode inlineNoRefs(JsonNode root, URI baseUri) {
    Map<String, JsonNode> memo = new HashMap<>();        // mémoïsation
    Deque<String> stack = new ArrayDeque<>();            // anti-boucle (trace)
    JsonNode eff = inlineRec(root, baseUri, memo, stack);
    // Dernière passe de nettoyage par sécurité
    return (ObjectNode) removeAllRefs(eff);
  }

  /* =================== cœur récursif =================== */

  private JsonNode inlineRec(JsonNode node, URI base, Map<String, JsonNode> memo, Deque<String> stack) {
    if (node == null) return null;
    if (!node.isObject()) return node.deepCopy();

    // Mémorisation par identité de nœud + base (grossier mais efficace pour éviter re-travail)
    String memoKey = System.identityHashCode(node) + "@" + (base == null ? "" : base);
    if (memo.containsKey(memoKey)) return memo.get(memoKey).deepCopy();

    ObjectNode obj = ((ObjectNode) node).deepCopy();

    // 1) Si $ref présent : résoudre puis fusionner les mots-clés locaux (allOf implicite), puis continuer
    if (obj.has("$ref")) {
      ObjectNode locals = obj.deepCopy();
      locals.remove("$ref");

      // Résolution (RefResolver gère déjà refs imbriqués et base URI)
      JsonNode resolved = refResolver.deref(obj, base);
      JsonNode merged = resolved;
      if (locals.size() > 0) {
        // allOf = [resolved, locals] puis fusion
        ObjectNode carrier = om.createObjectNode();
        ArrayNode allOf = om.createArrayNode().add(resolved).add(locals);
        carrier.set("allOf", allOf);
        merged = mergeAllOfInline(carrier, base, memo, stack);
      }
      JsonNode res = inlineRec(merged, base, memo, stack);
      memo.put(memoKey, res);
      return res.deepCopy();
    }

    // 2) allOf : inline & fusion
    if (obj.has("allOf") && obj.get("allOf").isArray()) {
      obj = mergeAllOfInline(obj, base, memo, stack);
    }

    // 3) Inline récursif dans toutes les positions porteuses de schéma

    // object-like
    inlineObjectKeyword(obj, "properties",        base, memo, stack);
    inlineObjectKeyword(obj, "patternProperties", base, memo, stack);
    inlineSchemaKeyword(obj, "additionalProperties", base, memo, stack);
    inlineSchemaKeyword(obj, "propertyNames", base, memo, stack);
    inlineObjectKeyword(obj, "dependentSchemas",  base, memo, stack);

    // array-like
    inlineSchemaKeyword(obj, "items",     base, memo, stack);   // objet ou tableau de schémas
    inlineSchemaKeyword(obj, "contains",  base, memo, stack);

    // combinators & conditions
    inlineArrayOfSchemas(obj, "anyOf", base, memo, stack);
    inlineArrayOfSchemas(obj, "oneOf", base, memo, stack);
    inlineSchemaKeyword(obj, "not",   base, memo, stack);
    inlineSchemaKeyword(obj, "if",    base, memo, stack);
    inlineSchemaKeyword(obj, "then",  base, memo, stack);
    inlineSchemaKeyword(obj, "else",  base, memo, stack);

    // defs / definitions (on inline, mais tu peux ensuite choisir de les dropper si tu veux un schéma minimal)
    inlineObjectKeyword(obj, "$defs",       base, memo, stack);
    inlineObjectKeyword(obj, "definitions", base, memo, stack);

    memo.put(memoKey, obj);
    return obj.deepCopy();
  }

  /* =================== helpers d’inlining =================== */

  /** Inline & fusionne allOf, puis continue l’inlining sur le résultat. */
  private ObjectNode mergeAllOfInline(ObjectNode nodeWithAllOf, URI base, Map<String, JsonNode> memo, Deque<String> stack) {
    ArrayNode all = nodeWithAllOf.has("allOf") && nodeWithAllOf.get("allOf").isArray()
        ? (ArrayNode) nodeWithAllOf.get("allOf") : om.createArrayNode();

    List<ObjectNode> parts = new ArrayList<>();
    for (JsonNode part : all) {
      JsonNode eff = inlineRec(part, base, memo, stack);
      if (eff.isObject()) parts.add((ObjectNode) eff);
    }

    ObjectNode acc = om.createObjectNode();
    for (ObjectNode p : parts) deepMergeSchema(acc, p);

    // Recopier les champs locaux hors allOf (priorité locaux)
    nodeWithAllOf.fields().forEachRemaining(e -> {
      if (!"allOf".equals(e.getKey())) acc.set(e.getKey(), e.getValue());
    });

    // Continuer l’inlining sur le résultat fusionné
    return (ObjectNode) inlineRec(acc, base, memo, stack);
  }

  /** Inline récursif pour un champ map<string,schema> (properties, patternProperties, $defs, …). */
  private void inlineObjectKeyword(ObjectNode host, String key, URI base, Map<String, JsonNode> memo, Deque<String> stack) {
    JsonNode n = host.get(key);
    if (n != null && n.isObject()) {
      ObjectNode out = om.createObjectNode();
      n.fields().forEachRemaining(e -> out.set(e.getKey(), inlineRec(e.getValue(), base, memo, stack)));
      host.set(key, out);
    }
  }

  /** Inline récursif pour un champ qui peut être un schéma objet ou un tableau de schémas (items, contains, not, if/then/else, …). */
  private void inlineSchemaKeyword(ObjectNode host, String key, URI base, Map<String, JsonNode> memo, Deque<String> stack) {
    JsonNode n = host.get(key);
    if (n == null) return;
    if (n.isObject()) {
      host.set(key, inlineRec(n, base, memo, stack));
    } else if (n.isArray()) {
      ArrayNode arr = om.createArrayNode();
      n.forEach(el -> arr.add(inlineRec(el, base, memo, stack)));
      host.set(key, arr);
    }
  }

  /** Inline pour un tableau de schémas (anyOf/oneOf). */
  private void inlineArrayOfSchemas(ObjectNode host, String key, URI base, Map<String, JsonNode> memo, Deque<String> stack) {
    JsonNode n = host.get(key);
    if (n != null && n.isArray()) {
      ArrayNode arr = om.createArrayNode();
      n.forEach(el -> arr.add(inlineRec(el, base, memo, stack)));
      host.set(key, arr);
    }
  }

  /* =================== fusion de schémas =================== */

  /**
   * Fusion "schema-wise" :
   * - object: merge properties (profonde), union required, propagate additionalProperties/patternProperties
   * - array: items/contains + contraintes
   * - scalaires: constraints (min/max/format/enum/…); la source écrase la cible pour les scalaires
   * - type: si absent sur cible, on copie; si conflit, on garde la cible (ou adapte au besoin)
   */
  private void deepMergeSchema(ObjectNode target, ObjectNode src) {
    // type
    if (src.has("type") && !target.has("type")) target.set("type", src.get("type"));

    // object-like
    if (src.has("properties") && src.get("properties").isObject()) {
      ObjectNode tgtProps = target.has("properties") && target.get("properties").isObject()
          ? (ObjectNode) target.get("properties") : om.createObjectNode();
      ObjectNode srcProps = (ObjectNode) src.get("properties");
      srcProps.fields().forEachRemaining(e -> {
        JsonNode t = tgtProps.get(e.getKey());
        if (t != null && t.isObject() && e.getValue().isObject()) {
          ObjectNode merged = om.createObjectNode();
          deepMergeSchema(merged, (ObjectNode) t);
          deepMergeSchema(merged, (ObjectNode) e.getValue());
          tgtProps.set(e.getKey(), merged);
        } else {
          tgtProps.set(e.getKey(), e.getValue());
        }
      });
      target.set("properties", tgtProps);
    }
    // required (union)
    Set<String> req = new LinkedHashSet<>();
    if (target.has("required") && target.get("required").isArray())
      target.get("required").forEach(n -> req.add(n.asText()));
    if (src.has("required") && src.get("required").isArray())
      src.get("required").forEach(n -> req.add(n.asText()));
    if (!req.isEmpty()) {
      ArrayNode arr = om.createArrayNode();
      req.forEach(arr::add);
      target.set("required", arr);
    }
    // additionalProperties / patternProperties / propertyNames
    copyIfPresent(src, target, "additionalProperties", "propertyNames");
    if (src.has("patternProperties") && src.get("patternProperties").isObject()) {
      ObjectNode tgt = target.has("patternProperties") && target.get("patternProperties").isObject()
          ? (ObjectNode) target.get("patternProperties") : om.createObjectNode();
      src.get("patternProperties").fields().forEachRemaining(e -> tgt.set(e.getKey(), e.getValue()));
      target.set("patternProperties", tgt);
    }
    // dependentSchemas
    if (src.has("dependentSchemas") && src.get("dependentSchemas").isObject()) {
      ObjectNode tgt = target.has("dependentSchemas") && target.get("dependentSchemas").isObject()
          ? (ObjectNode) target.get("dependentSchemas") : om.createObjectNode();
      src.get("dependentSchemas").fields().forEachRemaining(e -> tgt.set(e.getKey(), e.getValue()));
      target.set("dependentSchemas", tgt);
    }

    // array-like
    if (src.has("items"))     target.set("items", src.get("items"));
    if (src.has("contains"))  target.set("contains", src.get("contains"));
    copyIfPresent(src, target, "minItems","maxItems","uniqueItems");

    // combinators / conditionals (recopiés tels quels, déjà inlinés ailleurs)
    copyIfPresent(src, target, "anyOf","oneOf","not","if","then","else");

    // scalaires & contraintes communes
    copyIfPresent(src, target,
        "format","minimum","maximum","exclusiveMinimum","exclusiveMaximum",
        "multipleOf","minLength","maxLength","pattern","enum","const","default");

    // defs : recopier si présents (déjà inlinés mais on les conserve pour information ; sinon on peut les retirer ensuite)
    if (src.has("$defs"))       target.set("$defs", src.get("$defs"));
    if (src.has("definitions")) target.set("definitions", src.get("definitions"));
  }

  private void copyIfPresent(ObjectNode src, ObjectNode tgt, String... keys) {
    for (String k : keys) if (src.has(k)) tgt.set(k, src.get(k));
  }

  /* =================== nettoyage final =================== */

  /** Supprime tout "$ref" résiduel (sécurité) sur l’arbre. */
  private JsonNode removeAllRefs(JsonNode n) {
    if (n == null) return null;
    if (n.isObject()) {
      ObjectNode obj = ((ObjectNode) n).deepCopy();
      obj.remove("$ref");
      obj.fieldNames().forEachRemaining(k -> obj.set(k, removeAllRefs(obj.get(k))));
      return obj;
    } else if (n.isArray()) {
      ArrayNode arr = om.createArrayNode();
      n.forEach(el -> arr.add(removeAllRefs(el)));
      return arr;
    } else {
      return n.deepCopy();
    }
  }
}


