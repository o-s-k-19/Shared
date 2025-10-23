package com.acme.flowsim.schema;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
public class GenerationContext {
  private final Map<String, Object> variables = new HashMap<>();
  private final Map<String, AtomicInteger> rr = new ConcurrentHashMap<>();
  private final Random rnd = new Random();
  public void setVar(String key, Object value) { variables.put(key, value); }
  public Object getVar(String key) { return variables.get(key); }
  public Map<String,Object> vars() { return variables; }
  public int nextIndex(String key) { return rr.computeIfAbsent(key, k -> new AtomicInteger(0)).getAndIncrement(); }
  public Random random() { return rnd; }
}
