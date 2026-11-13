package com.anuj.testingdashboard;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestingDashboardApplicationTest {

    @Test
    void applicationClassExists() {
        assertNotNull(TestingDashboardApplication.class);
    }

    @Test
    void basicArithmeticTest() {
        assertEquals(10, 5 + 5);
    }
}
