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
    private Delegation delegation = new Delegation();
    private Research research = new Research();
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

    public Delegation getDelegation() {
        return delegation;
    }

    public void setDelegation(Delegation delegation) {
        this.delegation = delegation;
    }

    public Research getResearch() {
        return research;
    }

    public void setResearch(Research research) {
        this.research = research;
    }

    /** Researcher-specific knobs (docs/researcher-agent.md §4.2). */
    public static class Research {
        private int maxSources = 4;
        private Search search = new Search();

        public int getMaxSources() {
            return maxSources;
        }

        public void setMaxSources(int maxSources) {
            this.maxSources = maxSources;
        }

        public Search getSearch() {
            return search;
        }

        public void setSearch(Search search) {
            this.search = search;
        }
    }

    /**
     * Which search provider the {@code web-search} skill's connector should use, and what
     * to fall back to (§4.3). Reaches the skill as environment variables — the skill never
     * reads SOUL's config, and adding a provider never touches Java.
     */
    public static class Search {
        private String provider = "duckduckgo";
        private List<String> fallbacks = new ArrayList<>();

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public List<String> getFallbacks() {
            return fallbacks;
        }

        public void setFallbacks(List<String> fallbacks) {
            this.fallbacks = fallbacks;
        }
    }

    /**
     * Generic delegation knobs — they apply to ANY worker, not just the researcher
     * (docs/researcher-agent.md §4.2, §5.1).
     */
    public static class Delegation {
        private int timeoutSeconds = 120;
        private Confidence confidence = new Confidence();

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public Confidence getConfidence() {
            return confidence;
        }

        public void setConfidence(Confidence confidence) {
            this.confidence = confidence;
        }
    }

    /**
     * The confidence policy thresholds (§5.1). The decision is deterministic orchestrator
     * code — an 8B model is trusted to present a result, never to decide whether to retry.
     */
    public static class Confidence {
        private double retryBelow = 0.4;
        private double hedgeBelow = 0.7;
        private int maxRetries = 1;

        public double getRetryBelow() {
            return retryBelow;
        }

        public void setRetryBelow(double retryBelow) {
            this.retryBelow = retryBelow;
        }

        public double getHedgeBelow() {
            return hedgeBelow;
        }

        public void setHedgeBelow(double hedgeBelow) {
            this.hedgeBelow = hedgeBelow;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }
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
        private String description = "";
        private int maxSteps = 6;
        private List<String> capabilities = new ArrayList<>();
        private List<String> skills = new ArrayList<>();
        private List<String> hooks = new ArrayList<>();

        /** One line quoted by the generated delegate tool description (§5). */
        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        /**
         * The agent's PUBLIC contract — namespaced {@code domain.action} verbs others may
         * ask of it (§3.4). Distinct from {@link #getSkills()}, its private toolbox for
         * fulfilling them. Config is the registry's content: declaring capabilities here is
         * all it takes to join the fleet.
         */
        public List<String> getCapabilities() {
            return capabilities;
        }

        public void setCapabilities(List<String> capabilities) {
            this.capabilities = capabilities;
        }

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
