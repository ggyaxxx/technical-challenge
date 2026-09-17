package com.redischallenge.exercise2.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire format for the Redis Enterprise "BDB object" (Database API), reduced
 * to only the fields this exercise reads or writes. The real API response
 * contains many more fields (sharding, persistence, ports, ...) - they are
 * simply ignored on deserialization.
 *
 * Deliberately has no "module_list" field: omitting it means "no modules",
 * which is exactly what the exercise requires ("without using any modules").
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BdbDto {

    public Integer uid;
    public String name;
    public String type;

    @JsonProperty("memory_size")
    public long memorySize;

    public BdbDto() {
        // required by Jackson for deserialization
    }

    public BdbDto(String name, long memorySize) {
        this.name = name;
        this.type = "redis";
        this.memorySize = memorySize;
    }
}
