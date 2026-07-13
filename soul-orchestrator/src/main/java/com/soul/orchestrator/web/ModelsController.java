package com.soul.orchestrator.web;

import com.soul.orchestrator.ollama.OllamaClient;
import com.soul.orchestrator.web.Dtos.ModelDto;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Installed local models, proxied from Ollama (SPEC §5.1). */
@RestController
@RequestMapping("/api/v1")
public class ModelsController {

    private final OllamaClient ollama;

    public ModelsController(OllamaClient ollama) {
        this.ollama = ollama;
    }

    @GetMapping("/models")
    public List<ModelDto> models() {
        return ollama.listModels().stream().map(ModelDto::new).toList();
    }
}
