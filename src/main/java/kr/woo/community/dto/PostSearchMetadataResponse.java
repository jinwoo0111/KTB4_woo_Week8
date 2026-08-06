package kr.woo.community.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class PostSearchMetadataResponse {
    @JsonProperty("requested_sort")
    private final String requestedSort;

    @JsonProperty("effective_sort")
    private final String effectiveSort;

    private final String backend;
    private final boolean degraded;

    public PostSearchMetadataResponse(
            String requestedSort,
            String effectiveSort,
            String backend,
            boolean degraded
    ) {
        this.requestedSort = requestedSort;
        this.effectiveSort = effectiveSort;
        this.backend = backend;
        this.degraded = degraded;
    }

    public String getRequestedSort() {
        return requestedSort;
    }

    public String getEffectiveSort() {
        return effectiveSort;
    }

    public String getBackend() {
        return backend;
    }

    public boolean isDegraded() {
        return degraded;
    }
}
