package com.acme.flowsim.api;

import com.acme.flowsim.app.SimulationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/simulate")
public class SimulationController {
  private final SimulationService service;
  public SimulationController(SimulationService service) { this.service = service; }

  @PostMapping
  public ResponseEntity<Map<String,Object>> simulate(@Valid @RequestBody SimulationRequest req) {
    String jobId = service.run(req);
    return ResponseEntity.accepted().body(Map.of("jobId", jobId, "count", req.count(), "rate", req.rate(), "destination", req.destination()));
  }
}
