package com.redischallenge.exercise2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * TDD step 1 (RED): these tests are written BEFORE Exercise2Workflow exists.
 * They describe the exact contract required by the exercise:
 *   - createDatabase() must call the Database API without specifying any
 *     modules.
 *   - createRequiredUsers() must call the Users API exactly 3 times, with
 *     the exact email/name/role combinations given in the exercise text
 *     (the password value itself is not specified by the exercise, so it
 *     is deliberately not asserted here - see Exercise2Workflow's Javadoc).
 *   - listUsers() / deleteDatabase() simply delegate to the gateway.
 *
 * We mock our own ClusterApiGateway port (never a concrete REST client),
 * so these tests run with zero network calls and no real cluster required.
 */
@ExtendWith(MockitoExtension.class)
class Exercise2WorkflowTest {

    @Mock
    private ClusterApiGateway cluster;

    @Test
    void createDatabase_delegatesToGatewayWithoutModulesAndReturnsUid() {
        when(cluster.createDatabase(anyString(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(42);

        Exercise2Workflow workflow = new Exercise2Workflow(cluster);
        int uid = workflow.createDatabase();

        assertEquals(42, uid);
        verify(cluster).createDatabase(eq(Exercise2Workflow.NEW_DATABASE_NAME),
                eq(Exercise2Workflow.NEW_DATABASE_MEMORY_SIZE_BYTES));
        verifyNoMoreInteractions(cluster);
    }

    @Test
    void createRequiredUsers_createsExactlyTheThreeUsersFromTheExerciseSpec() {
        Exercise2Workflow workflow = new Exercise2Workflow(cluster);

        workflow.createRequiredUsers();

        verify(cluster).createUser(eq("john.doe@example.com"), eq("John Doe"), anyString(), eq("db_viewer"));
        verify(cluster).createUser(eq("mike.smith@example.com"), eq("Mike Smith"), anyString(), eq("db_member"));
        verify(cluster).createUser(eq("cary.johnson@example.com"), eq("Cary Johnson"), anyString(), eq("admin"));
        verifyNoMoreInteractions(cluster);
    }

    @Test
    void createRequiredUsers_usesADifferentGeneratedPasswordPerUser() {
        Exercise2Workflow workflow = new Exercise2Workflow(cluster);

        workflow.createRequiredUsers();

        var passwordCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(cluster, org.mockito.Mockito.times(3))
                .createUser(anyString(), anyString(), passwordCaptor.capture(), anyString());
        List<String> passwords = passwordCaptor.getAllValues();
        assertEquals(3, passwords.stream().distinct().count(), "each user should get its own generated password");
    }

    @Test
    void listUsers_delegatesToGatewayAndReturnsItsResult() {
        List<UserView> expected = List.of(
                new UserView("John Doe", "db_viewer", "john.doe@example.com"),
                new UserView("Mike Smith", "db_member", "mike.smith@example.com"),
                new UserView("Cary Johnson", "admin", "cary.johnson@example.com"));
        when(cluster.listUsers()).thenReturn(expected);

        Exercise2Workflow workflow = new Exercise2Workflow(cluster);

        assertEquals(expected, workflow.listUsers());
        verify(cluster).listUsers();
    }

    @Test
    void deleteDatabase_delegatesToGatewayWithTheGivenUid() {
        Exercise2Workflow workflow = new Exercise2Workflow(cluster);

        workflow.deleteDatabase(42);

        verify(cluster).deleteDatabase(42);
        verifyNoMoreInteractions(cluster);
    }
}
