package com.acme.flowsim.schema;

import com.acme.flowsim.dataset.DatasetRepository;
import com.acme.flowsim.resolver.*;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ResolverRegistry {
  private final StringResolver stringResolver;
  private final NumberResolver numberResolver;
  private final IntegerResolver integerResolver;
  private final BooleanResolver booleanResolver;
  private final ArrayResolver arrayResolver;
  private final ObjectResolver objectResolver;

  public ResolverRegistry(DatasetRepository datasetRepo, ObjectMapper om, EffectiveSchema eff) {
    var strategies = new XSourceStrategies(datasetRepo);
    this.stringResolver  = new StringResolver(strategies);
    this.numberResolver  = new NumberResolver(strategies);
    this.integerResolver = new IntegerResolver(strategies);
    this.booleanResolver = new BooleanResolver(strategies);
    this.objectResolver  = new ObjectResolver(this, om, strategies, eff);
    this.arrayResolver   = new ArrayResolver(this, om, strategies, eff);
  }

  public com.acme.flowsim.schema.PropertyResolver byType(String type) {
    return switch (type) {
      case "string"  -> stringResolver;
      case "number"  -> numberResolver;
      case "integer" -> integerResolver;
      case "boolean" -> booleanResolver;
      case "object"  -> objectResolver;
      case "array"   -> arrayResolver;
      default        -> (s, c, b) -> null;
    };
  }
}
