package com.acme.flowsim;

import com.acme.flowsim.schema.SchemaGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.Map;

@SpringBootTest
class GeneratorIT {
  @Autowired SchemaGenerator generator;
  @Test void generateOrder() {
    Map<String,Object> obj = generator.generate("order");
    assert obj.get("items") != null;
    assert obj.get("total") != null;
  }
}
