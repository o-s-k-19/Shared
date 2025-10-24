import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * JsonSchemaResolver — résout récursivement tous les $ref d’un JSON Schema (fichiers externes + fragments internes).
 * - Remplace les objets contenant "$ref" par la définition résolue (avec merge des "siblings", si présents).
 * - Supporte $ref placé dans "type" (type: { $ref: ... }) : remplace type par la valeur résolue et fusionne les contraintes.
 * - Ajoute un "type" lorsqu'il manque (inférence simple ; fallback "object").
 * - Détecte les cycles et lève une IllegalStateException (modifiable selon besoin).
 */
public class JsonSchemaResolver {
    private final ObjectMapper mapper;
    private final SchemaLoader loader;

    // Cache des documents chargés: docURI -> JsonNode
    private final Map<URI, JsonNode> docCache = new HashMap<>();
    // Cache des fragments résolus: fullURI (doc+fragment) -> JsonNode
    private final Map<URI, JsonNode> refCache = new HashMap<>();
    // Pile en cours de résolution pour détection de cycles
    private final Deque<URI> resolvingStack = new ArrayDeque<>();

    public JsonSchemaResolver(SchemaLoader loader) {
        this.mapper = new ObjectMapper();
        this.loader = loader;
    }

    /** API principale */
    public JsonNode resolve(JsonNode root, URI baseUri) {
        return resolveNode(root, normalizeBase(baseUri));
    }

    // --------- Cœur de la résolution ---------

    private JsonNode resolveNode(JsonNode node, URI baseUri) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode obj = node.deepCopy();

            // 1) $ref au niveau de l’objet
            if (obj.has("$ref")) {
                return resolveRefObject(obj, baseUri);
            }

            // 2) Cas spécial: type: { "$ref": ... }
            if (obj.has("type") && obj.get("type").isObject()) {
                ObjectNode typeObj = (ObjectNode) obj.get("type");
                if (typeObj.has("$ref")) {
                    ObjectNode resolvedTypeSchema = (ObjectNode) resolveRefObject(typeObj, baseUri);
                    // Inférer la valeur du "type" et fusionner les contraintes dans l’objet courant
                    JsonNode inferredType = extractTypeValue(resolvedTypeSchema);
                    if (inferredType != null) {
                        obj.set("type", inferredType);
                    } else {
                        // fallback: si rien d’explicite, inférer depuis la structure
                        obj.put("type", guessTypeFromSchema(resolvedTypeSchema));
                    }
                    // Merge des autres contraintes (format, enum, pattern, items, properties, etc.)
                    ObjectNode constraints = resolvedTypeSchema.deepCopy();
                    constraints.remove("$id");
                    constraints.remove("$schema");
                    constraints.remove("$ref");
                    // ne pas réécraser le "type" si nous l’avons déjà posé
                    constraints.remove("type");
                    deepMergeInto(obj, constraints);
                }
            }

            // 3) Résoudre récursivement les sous-nœuds
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            it.forEachRemaining(fields::add);
            for (Map.Entry<String, JsonNode> e : fields) {
                obj.set(e.getKey(), resolveNode(e.getValue(), baseUri));
            }

            // 4) S’assurer que chaque objet schéma a un "type"
            ensureType(obj);

            return obj;
        } else if (node.isArray()) {
            ArrayNode arr = ((ArrayNode) node).deepCopy();
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, resolveNode(arr.get(i), baseUri));
            }
            return arr;
        } else {
            return node; // primitifs
        }
    }

    /** Résout un objet de schéma contenant "$ref" (+ merge des propriétés locales). */
    private JsonNode resolveRefObject(ObjectNode refHolder, URI baseUri) {
        String refStr = refHolder.get("$ref").asText();
        URI resolvedUri = resolveAgainstBase(refStr, baseUri);
        JsonNode target = dereference(resolvedUri);

        // JSON Schema déconseille des "siblings" de $ref, mais on gère un merge "ref <- local"
        ObjectNode result = (target.isObject() ? (ObjectNode) target.deepCopy() : objectOf("const", target));
        // Enlever $ref pour éviter de le réinjecter
        ObjectNode locals = refHolder.deepCopy();
        locals.remove("$ref");
        // Les props locales écrasent la définition référencée
        deepMergeInto(result, locals);

        // Continuer la résolution récursive sur le résultat fusionné
        return resolveNode(result, baseUriFrom(resolvedUri));
    }

    /** Charge et renvoie la cible d’un $ref (support doc externe + fragment JSON Pointer). */
    private JsonNode dereference(URI full) {
        // Cycle ?
        if (resolvingStack.contains(full)) {
            throw new IllegalStateException("Cycle de $ref détecté: " + full + " -> " + resolvingStack);
        }
        if (refCache.containsKey(full)) return refCache.get(full);

        resolvingStack.push(full);
        try {
            URI docUri = withoutFragment(full);
            String frag = full.getFragment(); // peut être null
            JsonNode doc = loadDocument(docUri);

            JsonNode target;
            if (frag == null || frag.isEmpty()) {
                target = doc;
            } else {
                String pointerStr = decodeFragmentToPointer(frag);
                JsonPointer ptr = JsonPointer.compile(pointerStr);
                target = doc.at(ptr);
                if (target.isMissingNode()) {
                    throw new IllegalArgumentException("Fragment introuvable " + pointerStr + " dans " + docUri);
                }
            }
            // Memoize
            refCache.put(full, target);
            return target;
        } finally {
            resolvingStack.pop();
        }
    }

    private JsonNode loadDocument(URI docUri) {
        if (docUri == null) {
            // Pas de doc => on suppose base = document racine déjà chargé
            throw new IllegalArgumentException("docUri null: " + docUri);
        }
        if (docCache.containsKey(docUri)) return docCache.get(docUri);
        try (InputStream in = loader.open(docUri)) {
            if (in == null) throw new IllegalArgumentException("Impossible de charger: " + docUri);
            JsonNode node = mapper.readTree(in);
            docCache.put(docUri, node);
            return node;
        } catch (IOException e) {
            throw new RuntimeException("Erreur de lecture " + docUri + ": " + e.getMessage(), e);
        }
    }

    // --------- Utils schéma ---------

    /** Ajoute "type" si absent, par heuristique. */
    private void ensureType(ObjectNode obj) {
        if (obj.has("type")) return;

        // Essais d’inférence
        String guessed = guessTypeFromSchema(obj);
        obj.put("type", guessed);
    }

    private String guessTypeFromSchema(ObjectNode schema) {
        // 1) Signalements structurels
        if (schema.has("properties") || schema.has("additionalProperties") || schema.has("patternProperties") || schema.has("required")) {
            return "object";
        }
        if (schema.has("items") || schema.has("additionalItems")) {
            return "array";
        }
        // 2) Contraintes qui insinuent un scalaire
        if (schema.has("enum")) {
            JsonNode first = schema.get("enum").isArray() && schema.get("enum").size() > 0 ? schema.get("enum").get(0) : NullNode.getInstance();
            return scalarTypeOf(first);
        }
        if (schema.has("const")) {
            return scalarTypeOf(schema.get("const"));
        }
        if (schema.has("pattern") || schema.has("format") || schema.has("minLength") || schema.has("maxLength")) {
            return "string";
        }
        if (schema.has("minimum") || schema.has("maximum") || schema.has("multipleOf")) {
            return "number";
        }
        if (schema.has("anyOf") || schema.has("oneOf") || schema.has("allOf") || schema.has("not")) {
            // Ambigu : laisser "object" par défaut pour rester sûr.
            return "object";
        }
        // 3) Fallback prudent
        return "object";
    }

    private String scalarTypeOf(JsonNode v) {
        if (v.isTextual()) return "string";
        if (v.isInt() || v.isLong()) return "integer";
        if (v.isNumber()) return "number";
        if (v.isBoolean()) return "boolean";
        if (v.isNull()) return "null";
        if (v.isArray()) return "array";
        if (v.isObject()) return "object";
        return "object";
    }

    /** Extrait la valeur "type" (string|array) d’un schéma résolu si présente. */
    private JsonNode extractTypeValue(ObjectNode schema) {
        if (!schema.has("type")) return null;
        JsonNode t = schema.get("type");
        if (t.isTextual()) return TextNode.valueOf(t.asText());
        if (t.isArray()) return t.deepCopy();
        return null;
    }

    // --------- URI & JSON Pointer helpers ---------

    private static URI normalizeBase(URI base) {
        if (base == null) return URI.create("file:///");
        if (base.getScheme() == null) {
            // interpréter comme chemin fichier
            return Paths.get(base.toString()).toUri();
        }
        return base;
    }

    private static URI resolveAgainstBase(String ref, URI base) {
        URI ru = URI.create(ref);
        return (base == null) ? ru : base.resolve(ru);
    }

    private static URI withoutFragment(URI u) {
        if (u == null) return null;
        return URI.create(u.toString().split("#", 2)[0]);
    }

    private static URI baseUriFrom(URI full) {
        return withoutFragment(full);
    }

    private static String decodeFragmentToPointer(String frag) {
        // Pour "#/definitions/X" → "/definitions/X" (décodage percent + JSON Pointer)
        String f = URLDecoder.decode(frag, StandardCharsets.UTF_8);
        if (!f.startsWith("/")) {
            // JSON Schema autorise aussi des id autres que pointer (peu courant). On force pointer s’il manque le /
            if (f.equals("") || f.equals("#")) return "";
            if (f.startsWith("#")) f = f.substring(1);
            if (!f.startsWith("/")) f = "/" + f;
        }
        // JSON Pointer utilise ~0, ~1, Jackson gère déjà cette sémantique via compile
        return f.startsWith("#") ? f.substring(1) : f;
    }

    // --------- JSON utils ---------

    private static void deepMergeInto(ObjectNode target, JsonNode src) {
        if (src == null || src.isNull() || src.isMissingNode()) return;
        if (!src.isObject()) {
            // Remplacement brut si non-objet (peu probable ici)
            return;
        }
        ObjectNode srcObj = (ObjectNode) src;
        Iterator<Map.Entry<String, JsonNode>> it = srcObj.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String k = e.getKey();
            JsonNode v = e.getValue();

            if (target.has(k) && target.get(k).isObject() && v.isObject()) {
                deepMergeInto((ObjectNode) target.get(k), v);
            } else {
                target.set(k, v.deepCopy());
            }
        }
    }

    private static ObjectNode objectOf(String k, JsonNode v) {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.set(k, v);
        return o;
    }

    // --------- Loader SPI ---------

    public interface SchemaLoader {
        /** Ouvre un InputStream pour l’URI donné (file:, classpath:, http:, etc. selon implémentation) */
        InputStream open(URI uri) throws IOException;
    }

    /** Loader basique fichiers locaux (file:) + classpath: */
    public static class FileSystemSchemaLoader implements SchemaLoader {
        private final Path baseDir;

        public FileSystemSchemaLoader(Path baseDir) {
            this.baseDir = baseDir;
        }

        @Override
        public InputStream open(URI uri) throws IOException {
            String scheme = uri.getScheme();
            if (scheme == null || "file".equalsIgnoreCase(scheme)) {
                Path p = (uri.getPath() == null || uri.getPath().isEmpty())
                        ? baseDir
                        : baseDir.resolve(uri.getPath().startsWith("/") ? uri.getPath().substring(1) : uri.getPath());
                return Files.newInputStream(p.normalize());
            } else if ("classpath".equalsIgnoreCase(scheme)) {
                String path = uri.getSchemeSpecificPart();
                InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
                if (in == null) throw new IOException("Classpath resource not found: " + path);
                return in;
            } else {
                throw new IOException("Schéma non supporté pour l’instant: " + scheme + " (" + uri + ")");
            }
        }
    }

    // ---------- Demo main (facultatif) ----------
    public static void main(String[] args) throws Exception {
        Path base = Paths.get("schemas");            // dossier contenant vos fichiers .json
        Path rootFile = base.resolve("root.json");   // schéma racine
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(Files.newInputStream(rootFile));

        JsonSchemaResolver resolver = new JsonSchemaResolver(new FileSystemSchemaLoader(base));
        JsonNode resolved = resolver.resolve(root, rootFile.toUri());

        Files.createDirectories(base.resolve("out"));
        Path out = base.resolve("out/root-resolved.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), resolved);
        System.out.println("OK -> " + out.toAbsolutePath());
    }
}