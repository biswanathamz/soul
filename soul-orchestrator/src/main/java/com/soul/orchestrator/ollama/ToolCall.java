package com.soul.orchestrator.ollama;

import java.util.Map;

/** A tool (skill) the model asked to run, with its arguments. */
public record ToolCall(String name, Map<String, Object> arguments) {}
