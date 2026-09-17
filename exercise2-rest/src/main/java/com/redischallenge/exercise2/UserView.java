package com.redischallenge.exercise2;

/**
 * Read-only projection of a cluster user, limited to the fields the
 * exercise asks to display: name, role, and email.
 */
public record UserView(String name, String role, String email) {
}
