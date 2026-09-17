package com.redischallenge.exercise2.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Wire format for the Redis Enterprise "User object" (Users API), reduced
 * to the fields this exercise writes (email, name, password, role_uids) or
 * reads back (email, name, role/role_uids). "password" is only ever
 * populated on requests - the cluster never returns it in responses.
 *
 * This cluster has RBAC enabled: {@code GET /v1/roles} confirms the Roles
 * resource is supported, and the plain {@code role} string (e.g.
 * "db_viewer") is rejected with {@code invalid_param} /
 * "Trying to associate with a non-existing role", because on an
 * RBAC-enabled cluster that string is looked up as the name of a Role
 * object rather than treated as a fixed enum value. Per the Users API
 * reference, RBAC-enabled clusters must use {@code role_uids} (an array of
 * Role object uids) instead. Both fields are kept here: {@code role} for
 * tolerant deserialization of responses from non-RBAC clusters, and
 * {@code roleUids} for what this cluster actually requires on write - see
 * {@code RestClusterApiGateway.resolveRoleUid}.
 *
 * @JsonInclude(NON_NULL) omits null fields from the serialized request
 * (in particular "uid", always null on create requests) instead of
 * sending them as explicit JSON nulls - see BdbDto's Javadoc for why this
 * matters to the Redis Enterprise REST API's request validation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RedisUserDto {

    public Integer uid;
    public String email;
    public String password;
    public String name;
    public String role;

    @JsonProperty("role_uids")
    public List<Integer> roleUids;

    public RedisUserDto() {
        // required by Jackson for deserialization
    }

    public RedisUserDto(String email, String name, String password, int roleUid) {
        this.email = email;
        this.name = name;
        this.password = password;
        this.roleUids = List.of(roleUid);
    }
}
