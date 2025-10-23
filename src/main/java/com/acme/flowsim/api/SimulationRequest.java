package com.acme.flowsim.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record SimulationRequest(
    @NotBlank String schema,
    @Min(1) int count,
    @Min(1) int rate,
    Destination destination,
    String topic,
    Map<String,Object> overrides
) {
  public enum Destination { FILE, KAFKA, BOTH }
}
