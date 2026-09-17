package com.redischallenge.exercise2.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Wire format for the Redis Enterprise "Role object" (Roles API,
 * {@code /v1/roles}). A role is a named cluster-management permission
 * level; {@code management} is one of the fixed values documented for the
 * Users API's legacy {@code role} field ("none", "db_viewer", "db_member",
 * "cluster_viewer", "cluster_member", "user_manager", "admin").
 *
 * On RBAC-enabled clusters, a user is associated with a role by its
 * {@code uid} (see {@code RedisUserDto.roleUids}), not by this management
 * string directly - see {@link RedisUserDto}'s Javadoc.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoleDto {

    public Integer uid;
    public String name;
    public String management;

    public RoleDto() {
        // required by Jackson for deserialization
    }

    public RoleDto(String name, String management) {
        this.name = name;
        this.management = management;
    }
}
