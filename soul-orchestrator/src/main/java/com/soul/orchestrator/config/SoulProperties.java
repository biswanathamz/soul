package com.soul.orchestrator.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code soul:} config tree (application.yml). Pool paths are global;
 * each agent names the skills and hooks it uses (docs/manager-agent.md §5).
 */
@ConfigurationProperties(prefix = "soul")
public class SoulProperties {

    private Ollama ollama = new Ollama();
    private Web web = new Web();
    private Pools pools = new Pools();
    private Map<String, Agent> agents = new LinkedHashMap<>();

    public Ollama getOllama() {
        return ollama;
    }

    public void setOllama(Ollama ollama) {
        this.ollama = ollama;
    }

    public Web getWeb() {
        return web;
    }

    public void setWeb(Web web) {
        this.web = web;
    }

    public Pools getPools() {
        return pools;
    }

    public void setPools(Pools pools) {
        this.pools = pools;
    }

    public static class Ollama {
        private String baseUrl = "http://localhost:11434";
        private int requestTimeoutSeconds = 120;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public int getRequestTimeoutSeconds() {
            return requestTimeoutSeconds;
        }

        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
            this.requestTimeoutSeconds = requestTimeoutSeconds;
        }
    }

    public static class Web {
        private String corsAllowedOrigin = "http://localhost:7787";

        public String getCorsAllowedOrigin() {
            return corsAllowedOrigin;
        }

        public void setCorsAllowedOrigin(String corsAllowedOrigin) {
            this.corsAllowedOrigin = corsAllowedOrigin;
        }
    }

    public Map<String, Agent> getAgents() {
        return agents;
    }

    public void setAgents(Map<String, Agent> agents) {
        this.agents = agents;
    }

    public static class Pools {
        private Pool skills = new Pool();
        private Pool hooks = new Pool();

        public Pool getSkills() {
            return skills;
        }

        public void setSkills(Pool skills) {
            this.skills = skills;
        }

        public Pool getHooks() {
            return hooks;
        }

        public void setHooks(Pool hooks) {
            this.hooks = hooks;
        }
    }

    public static class Pool {
        private String path;
        private boolean enabled = true;
        private int defaultTimeoutSeconds = 5;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getDefaultTimeoutSeconds() {
            return defaultTimeoutSeconds;
        }

        public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) {
            this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        }
    }

    public static class Agent {
        private String model;
        private String persona;
        private int maxSteps = 6;
        private List<String> skills = new ArrayList<>();
        private List<String> hooks = new ArrayList<>();

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getPersona() {
            return persona;
        }

        public void setPersona(String persona) {
            this.persona = persona;
        }

        public int getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
        }

        public List<String> getSkills() {
            return skills;
        }

        public void setSkills(List<String> skills) {
            this.skills = skills;
        }

        public List<String> getHooks() {
            return hooks;
        }

        public void setHooks(List<String> hooks) {
            this.hooks = hooks;
        }
    }
}
