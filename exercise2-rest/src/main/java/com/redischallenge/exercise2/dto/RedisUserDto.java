package com.redischallenge.exercise2.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Wire format for the Redis Enterprise "User object" (Users API), reduced
 * to the fields this exercise writes (email, name, password, role) or
 * reads back (email, name, role). "password" is only ever populated on
 * requests - the cluster never returns it in responses.
 *
 * "role" (rather than "role_uids") is used because this cluster is not
 * RBAC-enabled: the Users API accepts a plain role name in that case
 * ("db_viewer" / "db_member" / "admin", exactly as given in the exercise).
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

    public RedisUserDto() {
        // required by Jackson for deserialization
    }

    public RedisUserDto(String email, String name, String password, String role) {
        this.email = email;
        this.name = name;
        this.password = password;
        this.role = role;
    }
}
