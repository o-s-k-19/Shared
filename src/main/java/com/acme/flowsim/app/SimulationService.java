package com.acme.flowsim.app;

import com.acme.flowsim.api.SimulationRequest;
import com.acme.flowsim.sink.FileSink;
import com.acme.flowsim.sink.KafkaSink;
import com.acme.flowsim.schema.SchemaGenerator;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class SimulationService {
  private final SchemaGenerator generator;
  private final FileSink fileSink;
  private final KafkaSink kafkaSink;

  public SimulationService(SchemaGenerator generator, FileSink fileSink, KafkaSink kafkaSink) {
    this.generator = generator; this.fileSink = fileSink; this.kafkaSink = kafkaSink;
  }

  public String run(SimulationRequest req) {
    String jobId = UUID.randomUUID().toString();
    int rate = Math.max(req.rate(), 1);
    long pauseMs = 1000L / rate;

    for (int i=0; i<req.count(); i++) {
      Map<String,Object> payload = generator.generate(req.schema());
      Map<String,Object> envelope = Map.of(
        "type", req.schema(),
        "id", UUID.randomUUID().toString(),
        "timestamp", Instant.now().toString(),
        "data", payload
      );

      switch (req.destination()) {
        case FILE -> fileSink.writeNdjson(jobId, envelope);
        case KAFKA -> { if (req.topic() != null) kafkaSink.send(req.topic(), req.schema()+":"+UUID.randomUUID(), envelope); }
        case BOTH -> {
          fileSink.writeNdjson(jobId, envelope);
          if (req.topic() != null) kafkaSink.send(req.topic(), req.schema()+":"+UUID.randomUUID(), envelope);
        }
        default -> {}
      }
      try { Thread.sleep(pauseMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
    return jobId;
  }
}
