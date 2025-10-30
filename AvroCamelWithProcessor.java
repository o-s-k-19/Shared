package com.example.remit.avroutil;

import org.apache.avro.Conversions;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;

import java.math.BigDecimal;
import java.nio.ByteBuffer;

public final class AvroDecimals {
  private static final LogicalTypes.Decimal DEC_18_2 = LogicalTypes.decimal(18, 2);
  private static final Schema DEC_SCHEMA = DEC_18_2.addToSchema(Schema.create(Schema.Type.BYTES));
  private static final Conversions.DecimalConversion CONV = new Conversions.DecimalConversion();

  private AvroDecimals(){}

  public static ByteBuffer toBytes(BigDecimal v) {
    return v == null ? null : CONV.toBytes(v, DEC_SCHEMA, DEC_18_2);
  }
}



package com.example.remit.core;

import java.math.BigDecimal;

public final class BatchAcc {
  public String batchId;
  public String currency;
  public int creditCount, debitCount;
  public BigDecimal creditAmt = BigDecimal.ZERO;
  public BigDecimal debitAmt = BigDecimal.ZERO;
}



package com.example.remit.proc;

import com.example.remit.avro.*;
import com.example.remit.avroutil.AvroDecimals;
import com.example.remit.core.BatchAcc;
import com.example.remit.config.SimProperties;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;

@Component
public class StartBatchProcessor implements Processor {
  private final SimProperties props;
  public StartBatchProcessor(SimProperties props){ this.props = props; }

  @Override public void process(Exchange ex) {
    BatchAcc acc = new BatchAcc();
    acc.batchId = "REM-" + System.currentTimeMillis();
    acc.currency = props.getCurrency();
    ex.setProperty("acc", acc);
  }
}

@Component
class InitEventProcessor implements Processor {
  @Override public void process(Exchange ex) {
    BatchAcc acc = ex.getProperty("acc", BatchAcc.class);
    RemittanceEvent ev = RemittanceEvent.newBuilder()
      .setEventType(EventType.INITIATION)
      .setBatchId(acc.batchId)
      .setEventId(UUID.randomUUID().toString())
      .setCreatedAt(OffsetDateTime.now().toInstant().toEpochMilli())
      .setCurrency(acc.currency)
      .setDirection(null)
      .setAmount(null)
      .setCreditCount(null).setDebitCount(null)
      .setCreditAmount(null).setDebitAmount(null).setTotalAmount(null)
      .setTransactionCount(null)
      .build();
    ex.getIn().setBody(ev);
    ex.getIn().setHeader("kafka.KEY", acc.batchId);
  }
}

@Component
class GenerateTxListProcessor implements Processor {
  private final SimProperties props;
  private final Random rnd = new Random(42L);
  public GenerateTxListProcessor(SimProperties props){ this.props = props; }

  @Override public void process(Exchange ex) {
    BatchAcc acc = ex.getProperty("acc", BatchAcc.class);
    List<RemittanceEvent> list = new ArrayList<>(props.getTransactionsPerBatch());
    for (int i=0;i<props.getTransactionsPerBatch();i++){
      Direction dir = rnd.nextDouble() < props.getCreditRatio() ? Direction.CREDIT : Direction.DEBIT;
      BigDecimal amt = randomAmount(props.getMinAmount(), props.getMaxAmount());
      RemittanceEvent tx = RemittanceEvent.newBuilder()
        .setEventType(EventType.TRANSACTION)
        .setBatchId(acc.batchId)
        .setEventId(UUID.randomUUID().toString())
        .setCreatedAt(OffsetDateTime.now().toInstant().toEpochMilli())
        .setCurrency(acc.currency)
        .setDirection(dir)
        .setAmount(AvroDecimals.toBytes(amt))
        .setCreditCount(null).setDebitCount(null)
        .setCreditAmount(null).setDebitAmount(null).setTotalAmount(null)
        .setTransactionCount(null)
        .build();
      list.add(tx);

      if (dir == Direction.CREDIT) { acc.creditCount++; acc.creditAmt = acc.creditAmt.add(amt); }
      else { acc.debitCount++; acc.debitAmt = acc.debitAmt.add(amt); }
    }
    ex.getIn().setBody(list);
    ex.getIn().setHeader("kafka.KEY", acc.batchId);
  }

  private BigDecimal randomAmount(BigDecimal min, BigDecimal max){
    BigDecimal span = max.subtract(min);
    return min.add(span.multiply(BigDecimal.valueOf(rnd.nextDouble()))).setScale(2, RoundingMode.HALF_UP);
  }
}

@Component
class FinalEventProcessor implements Processor {
  @Override public void process(Exchange ex) {
    BatchAcc acc = ex.getProperty("acc", BatchAcc.class);
    BigDecimal total = acc.creditAmt.add(acc.debitAmt);
    RemittanceEvent fin = RemittanceEvent.newBuilder()
      .setEventType(EventType.FINALIZATION)
      .setBatchId(acc.batchId)
      .setEventId(UUID.randomUUID().toString())
      .setCreatedAt(OffsetDateTime.now().toInstant().toEpochMilli())
      .setCurrency(acc.currency)
      .setDirection(null)
      .setAmount(null)
      .setCreditCount(acc.creditCount)
      .setDebitCount(acc.debitCount)
      .setCreditAmount(AvroDecimals.toBytes(acc.creditAmt))
      .setDebitAmount(AvroDecimals.toBytes(acc.debitAmt))
      .setTotalAmount(AvroDecimals.toBytes(total))
      .setTransactionCount(acc.creditCount + acc.debitCount)
      .build();
    ex.getIn().setBody(fin);
    ex.getIn().setHeader("kafka.KEY", acc.batchId);
  }
}



package com.example.remit.route;

import com.example.remit.config.KafkaProps;
import com.example.remit.config.SimProperties;
import com.example.remit.proc.*;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.spi.IdempotentRepository;
import org.apache.camel.support.processor.idempotent.MemoryIdempotentRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class RemittanceRouteProcessors extends RouteBuilder {
  private final SimProperties sim;
  private final KafkaProps kafka;
  private final StartBatchProcessor start;
  private final InitEventProcessor init;
  private final GenerateTxListProcessor genTx;
  private final FinalEventProcessor fin;

  public RemittanceRouteProcessors(SimProperties sim, KafkaProps kafka,
                                   StartBatchProcessor start, InitEventProcessor init,
                                   GenerateTxListProcessor genTx, FinalEventProcessor fin) {
    this.sim = sim; this.kafka = kafka; this.start = start; this.init = init; this.genTx = genTx; this.fin = fin;
  }

  @Bean IdempotentRepository<String> eventIdRepo() {
    return MemoryIdempotentRepository.memoryIdempotentRepository(1_000_000);
  }

  @Override public void configure() {

    if (!sim.isEnabled()) return;

    onException(Exception.class).logHandled(true).log("Simulation error: ${exception.message}");

    final String kafkaUri =
        "kafka:" + kafka.getTopic()
      + "?brokers=" + kafka.getBootstrap()
      + "&keySerializer=org.apache.kafka.common.serialization.StringSerializer"
      + "&valueSerializer=io.confluent.kafka.serializers.KafkaAvroSerializer"
      + "&additionalProperties[enable.idempotence]=true"
      + "&additionalProperties[acks]=all"
      + "&additionalProperties[max.in.flight.requests.per.connection]=1"
      + "&additionalProperties[retries]=2147483647"
      + "&additionalProperties[schema.registry.url]=" + kafka.getSchemaRegistryUrl();

    fromF("timer:simulate-remit?fixedRate=true&period=%d", sim.getPeriod().toMillis())
      .routeId("simulate-remittance-avro-processors")
      .process(start)

      // INIT
      .process(init)
      .setHeader(KafkaConstants.KEY, header("kafka.KEY"))
      .idempotentConsumer(simple("${body.eventId}")).messageIdRepository("eventIdRepo").skipDuplicate(true).end()
      .to(kafkaUri)

      // TX* (split + idempotence par eventId)
      .process(genTx)
      .split(body())
        .setHeader(KafkaConstants.KEY, header("kafka.KEY"))
        .idempotentConsumer(simple("${body.eventId}")).messageIdRepository("eventIdRepo").skipDuplicate(true).end()
        .to(kafkaUri)
      .end()

      // FINAL
      .process(fin)
      .setHeader(KafkaConstants.KEY, header("kafka.KEY"))
      .idempotentConsumer(simple("${body.eventId}")).messageIdRepository("eventIdRepo").skipDuplicate(true).end()
      .to(kafkaUri)
    ;
  }
}






