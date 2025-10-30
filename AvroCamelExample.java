// .avsc
{
  "namespace": "com.example.remit.avro",
  "type": "record",
  "name": "RemittanceEvent",
  "fields": [
    { "name": "eventType", "type": { "type":"enum", "name":"EventType",
      "symbols": ["INITIATION","TRANSACTION","FINALIZATION"] } },

    { "name": "batchId",   "type": "string" },
    { "name": "eventId",   "type": "string" },
    { "name": "createdAt", "type": { "type":"long", "logicalType":"timestamp-millis" } },
    { "name": "currency",  "type": "string" },

    { "name": "direction", "type": [ "null", { "type":"enum", "name":"Direction",
      "symbols": ["CREDIT","DEBIT"] } ], "default": null },

    { "name": "amount", "type": [ "null",
      { "type":"bytes", "logicalType":"decimal", "precision":18, "scale":2 } ], "default": null },

    { "name": "creditCount",      "type": ["null","int"], "default": null },
    { "name": "debitCount",       "type": ["null","int"], "default": null },
    { "name": "creditAmount", "type": [ "null",
      { "type":"bytes", "logicalType":"decimal", "precision":18, "scale":2 } ], "default": null },
    { "name": "debitAmount",  "type": [ "null",
      { "type":"bytes", "logicalType":"decimal", "precision":18, "scale":2 } ], "default": null },
    { "name": "totalAmount",  "type": [ "null",
      { "type":"bytes", "logicalType":"decimal", "precision":18, "scale":2 } ], "default": null },
    { "name": "transactionCount", "type": ["null","int"], "default": null }
  ]
}



<properties>
  <camel.version>3.22.0</camel.version>
  <avro.version>1.11.3</avro.version>
  <confluent.version>7.6.0</confluent.version>
  <java.version>21</java.version>
</properties>

<dependencies>
  <!-- Camel -->
  <dependency>
    <groupId>org.apache.camel.springboot</groupId>
    <artifactId>camel-spring-boot-starter</artifactId>
    <version>${camel.version}</version>
  </dependency>
  <dependency>
    <groupId>org.apache.camel.springboot</groupId>
    <artifactId>camel-kafka-starter</artifactId>
    <version>${camel.version}</version>
  </dependency>

  <!-- Avro SpecificRecord (généré par le plugin) -->
  <dependency>
    <groupId>org.apache.avro</groupId>
    <artifactId>avro</artifactId>
    <version>${avro.version}</version>
  </dependency>

  <!-- Confluent serializers + Schema Registry client -->
  <dependency>
    <groupId>io.confluent</groupId>
    <artifactId>kafka-avro-serializer</artifactId>
    <version>${confluent.version}</version>
  </dependency>

  <!-- Optionnel: Jakarta Validation -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
  </dependency>
</dependencies>

<build>
  <plugins>
    <!-- Avro: génère les classes SpecificRecord à partir de src/main/avro -->
    <plugin>
      <groupId>org.apache.avro</groupId>
      <artifactId>avro-maven-plugin</artifactId>
      <version>${avro.version}</version>
      <executions>
        <execution>
          <phase>generate-sources</phase>
          <goals><goal>schema</goal></goals>
          <configuration>
            <sourceDirectory>${project.basedir}/src/main/avro</sourceDirectory>
            <outputDirectory>${project.build.directory}/generated-sources/avro</outputDirectory>
            <stringType>String</stringType>
            <enableDecimalLogicalType>true</enableDecimalLogicalType>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>




sim:
  currency: EUR
  transactions-per-batch: 50
  min-amount: 1.00
  max-amount: 200.00
  credit-ratio: 0.5
  period: 30s
  enabled: true

kafka:
  bootstrap: localhost:9092
  topic: remittance.events
  schemaRegistryUrl: http://localhost:8081

camel:
  springboot:
    main-run-controller: true
spring:
  main:
    web-application-type: none




package com.example.remit.core;

import com.example.remit.avro.RemittanceEvent;
import com.example.remit.avro.EventType;
import com.example.remit.avro.Direction;
import com.example.remit.config.SimProperties;
import org.apache.avro.Conversions;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class RemittanceSimulatorService {

  private final SimProperties props;
  private final Random rnd = new Random(42L);

  // Prépare un schema decimal(18,2) pour la conversion BigDecimal -> bytes
  private static final LogicalTypes.Decimal DEC_18_2 = LogicalTypes.decimal(18, 2);
  private static final Schema DEC_SCHEMA;
  private static final Conversions.DecimalConversion DEC_CONV = new Conversions.DecimalConversion();
  static {
    Schema s = Schema.create(Schema.Type.BYTES);
    DEC_SCHEMA = DEC_18_2.addToSchema(s);
  }

  public RemittanceSimulatorService(SimProperties props) {
    this.props = props;
  }

  public List<RemittanceEvent> simulateOneBatch() {
    String batchId = "REM-" + System.currentTimeMillis();
    String ccy = props.getCurrency();
    var out = new ArrayList<RemittanceEvent>(props.getTransactionsPerBatch() + 2);

    // INIT
    out.add(init(batchId, ccy));

    // TX
    int creditCount = 0, debitCount = 0;
    BigDecimal creditAmt = BigDecimal.ZERO, debitAmt = BigDecimal.ZERO;

    for (int i = 0; i < props.getTransactionsPerBatch(); i++) {
      Direction dir = rnd.nextDouble() < props.getCreditRatio() ? Direction.CREDIT : Direction.DEBIT;
      BigDecimal amt = randomAmount(props.getMinAmount(), props.getMaxAmount());
      out.add(tx(batchId, ccy, dir, amt));
      if (dir == Direction.CREDIT) { creditCount++; creditAmt = creditAmt.add(amt);}
      else { debitCount++; debitAmt = debitAmt.add(amt); }
    }

    // FINAL
    out.add(fin(batchId, ccy, creditCount, debitCount, creditAmt, debitAmt));
    return out;
  }

  private RemittanceEvent init(String batchId, String ccy) {
    return base(EventType.INITIATION, batchId, ccy).build();
  }

  private RemittanceEvent tx(String batchId, String ccy, Direction dir, BigDecimal amount) {
    return base(EventType.TRANSACTION, batchId, ccy)
      .setDirection(dir)
      .setAmount(toDecimal(amount))
      .build();
  }

  private RemittanceEvent fin(String batchId, String ccy, int cr, int dr,
                              BigDecimal crAmt, BigDecimal drAmt) {
    BigDecimal total = crAmt.add(drAmt);
    return base(EventType.FINALIZATION, batchId, ccy)
      .setCreditCount(cr)
      .setDebitCount(dr)
      .setCreditAmount(toDecimal(crAmt))
      .setDebitAmount(toDecimal(drAmt))
      .setTotalAmount(toDecimal(total))
      .setTransactionCount(cr + dr)
      .build();
  }

  private RemittanceEvent.Builder base(EventType type, String batchId, String ccy) {
    String eventId = UUID.randomUUID().toString(); // ou UUID.nameUUIDFromBytes(...) si tu veux déterministe
    return RemittanceEvent.newBuilder()
      .setEventType(type)
      .setBatchId(batchId)
      .setEventId(eventId)
      .setCreatedAt(OffsetDateTime.now().toInstant().toEpochMilli())
      .setCurrency(ccy)
      .setDirection(null)
      .setAmount(null)
      .setCreditCount(null)
      .setDebitCount(null)
      .setCreditAmount(null)
      .setDebitAmount(null)
      .setTotalAmount(null)
      .setTransactionCount(null);
  }

  private BigDecimal randomAmount(BigDecimal min, BigDecimal max) {
    BigDecimal span = max.subtract(min);
    return min.add(span.multiply(BigDecimal.valueOf(rnd.nextDouble()))).setScale(2, RoundingMode.HALF_UP);
  }

  private ByteBuffer toDecimal(BigDecimal v) {
    if (v == null) return null;
    return DEC_CONV.toBytes(v, DEC_SCHEMA, DEC_18_2);
  }
}


package com.example.remit.route;

import com.example.remit.config.KafkaProps;
import com.example.remit.config.SimProperties;
import com.example.remit.avro.RemittanceEvent;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.spi.IdempotentRepository;
import org.apache.camel.support.processor.idempotent.MemoryIdempotentRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class RemittanceRoute extends RouteBuilder {

  private final SimProperties sim;
  private final KafkaProps kafka;

  public RemittanceRoute(SimProperties sim, KafkaProps kafka) {
    this.sim = sim; this.kafka = kafka;
  }

  // Idempotence en mémoire (swap possible vers JDBC/Redis pour persister)
  @Bean
  public IdempotentRepository<String> eventIdRepo() {
    return MemoryIdempotentRepository.memoryIdempotentRepository(1_000_000);
  }

  @Override public void configure() {

    if (!sim.isEnabled()) return;

    onException(Exception.class).logHandled(true).log("Simulation error: ${exception.message}");

    fromF("timer:simulate-remit?fixedRate=true&period=%d", sim.getPeriod().toMillis())
      .routeId("simulate-remittance-avro-one-topic")
      .bean("remittanceSimulatorService", "simulateOneBatch")   // -> List<RemittanceEvent>
      .split(body())
        // KEY = batchId (garantit l'ordre par remise), header pour Kafka
        .setHeader(KafkaConstants.KEY, simple("${body.batchId}"))
        // Idempotence producteur: filtre si eventId déjà vu (optionnel mais utile en plus d'idempotence Kafka)
        .idempotentConsumer(simple("${body.eventId}")).messageIdRepository("eventIdRepo")
          .skipDuplicate(true).removeOnFailure(false)
        .end()
        // Envoi Avro: on passe un SpecificRecord -> serializer Confluent fait le reste
        .toD("kafka:" + kafka.getTopic()
            + "?brokers=" + kafka.getBootstrap()
            + "&keySerializer=org.apache.kafka.common.serialization.StringSerializer"
            + "&valueSerializer=io.confluent.kafka.serializers.KafkaAvroSerializer"
            + "&additionalProperties[enable.idempotence]=true"
            + "&additionalProperties[acks]=all"
            + "&additionalProperties[max.in.flight.requests.per.connection]=1"
            + "&additionalProperties[retries]=2147483647"
            + "&additionalProperties[schema.registry.url]=" + kafka.getSchemaRegistryUrl()
        )
        .log("Published ${header.kafka.KEY} ${body.eventType} to topic " + kafka.getTopic())
      .end();
  }
}




