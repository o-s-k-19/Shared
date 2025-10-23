package com.acme.flowsim.schema;

import com.acme.flowsim.dataset.DatasetRepository;
import com.acme.flowsim.resolver.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResolverRegistryConfig {
  @Bean
  public ResolverRegistry resolverRegistry(DatasetRepository datasetRepo, ObjectMapper om, EffectiveSchema eff) {
    return new ResolverRegistry(datasetRepo, om, eff);
  }
}
