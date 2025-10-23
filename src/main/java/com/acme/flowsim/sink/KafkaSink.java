package com.acme.flowsim.sink;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaSink {
  private final KafkaTemplate<String,Object> template;
  public KafkaSink(KafkaTemplate<String,Object> template) { this.template = template; }
  public void send(String topic, String key, Object event) { template.send(topic, key, event); }
}
