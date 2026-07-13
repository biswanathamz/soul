package com.soul.orchestrator.ollama;

/** Raised when the local model can't be reached or returns an error. */
public class OllamaException extends RuntimeException {

    public OllamaException(String message) {
        super(message);
    }

    public OllamaException(String message, Throwable cause) {
        super(message, cause);
    }
}
