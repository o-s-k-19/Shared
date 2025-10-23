package com.acme.flowsim.resolver;

import com.acme.flowsim.dataset.DatasetRepository;
import com.acme.flowsim.schema.GenerationContext;
import com.fasterxml.jackson.databind.JsonNode;
import net.datafaker.Faker;
import org.apache.commons.jexl3.*;
import java.time.Instant;
import java.util.*;

public class XSourceStrategies {
  private final DatasetRepository repo;
  private final Faker faker = new Faker(new Locale("fr"));
  private final JexlEngine jexl = new JexlBuilder().create();
  private final Random rnd = new Random();
  public XSourceStrategies(DatasetRepository repo) { this.repo = repo; }
  public boolean hasX(JsonNode s) { return s != null && s.has("x-source"); }
  public JsonNode x(JsonNode s)   { return s.get("x-source"); }
  public Object apply(JsonNode schemaNode, GenerationContext ctx) {
    if (!hasX(schemaNode)) return null;
    JsonNode x = x(schemaNode);
    String strategy = x.path("strategy").asText("");
    return switch (strategy) {
      case "uuid"        -> java.util.UUID.randomUUID().toString();
      case "now"         -> Instant.now().toString();
      case "constant"    -> constant(x);
      case "range"       -> range(x);
      case "probability" -> rnd.nextDouble() < x.path("p").asDouble(0.5);
      case "faker"       -> fakerValue(x);
      case "dataset"     -> datasetPick(x, ctx);
      case "expression"  -> expression(x, ctx);
      default            -> null;
    };
  }
  private Object constant(JsonNode x) {
    JsonNode v = x.get("value");
    if (v == null || v.isNull()) return null;
    if (v.isBoolean()) return v.booleanValue();
    if (v.isNumber()) return v.numberValue();
    if (v.isTextual()) return v.textValue();
    return v.toString();
  }
  private Number range(JsonNode x) {
    double min = x.path("min").asDouble(0);
    double max = x.has("max") ? x.get("max").asDouble(min + 100) : (min + 100);
    return min + rnd.nextDouble() * (max - min);
  }
  private Object fakerValue(JsonNode x) {
    String provider = x.path("provider").asText("");
    return switch (provider) {
      case "name.firstName" -> faker.name().firstName();
      case "name.lastName"  -> faker.name().lastName();
      case "internet.email" -> faker.internet().emailAddress();
      case "phone.e164"     -> faker.phoneNumber().phoneNumber();
      case "commerce.price" -> Double.parseDouble(faker.commerce().price(1, 500));
      default               -> faker.lorem().word();
    };
  }
  private Object datasetPick(JsonNode x, GenerationContext ctx) {
    String file = x.path("file").asText();
    String column = x.path("column").asText();
    String mode = x.path("mode").asText("random");
    var rows = repo.loadCsv(file, ';');
    if (rows.isEmpty()) return null;
    if ("roundrobin".equalsIgnoreCase(mode)) {
      int idx = ctx.nextIndex(file);
      return rows.get(idx % rows.size()).get(column);
    }
    return rows.get(new Random().nextInt(rows.size())).get(column);
  }
  private Object expression(JsonNode x, GenerationContext ctx) {
    String expr = x.path("expr").asText();
    JexlExpression e = jexl.createExpression(expr);
    JexlContext jc = new MapContext();
    ctx.vars().forEach(jc::set);
    return e.evaluate(jc);
  }
}
