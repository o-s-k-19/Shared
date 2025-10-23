package com.acme.flowsim.domain;

import java.time.Instant;
import java.util.Map;

public record FlowEvent(String type, String id, Instant timestamp, Map<String,Object> data) {}
