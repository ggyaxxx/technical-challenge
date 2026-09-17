package com.redischallenge.exercise2.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire format for the Redis Enterprise "BDB object" (Database API), reduced
 * to only the fields this exercise reads or writes. The real API response
 * contains many more fields (sharding, persistence, ports, ...) - they are
 * simply ignored on deserialization.
 *
 * Deliberately has no "module_list" field: omitting it means "no modules",
 * which is exactly what the exercise requires ("without using any modules").
 *
 * @JsonInclude(NON_NULL) is required for "uid": when creating a database,
 * this DTO's uid field is null (the cluster assigns it), and without this
 * annotation Jackson serializes it as an explicit "uid": null in the
 * request body. The Database API rejects that with 400 Bad Request - a
 * null uid is not the same as an absent one. Omitting the field entirely
 * is what actually triggers auto-assignment, per the API reference.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
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
