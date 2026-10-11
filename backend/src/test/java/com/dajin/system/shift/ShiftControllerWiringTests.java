package com.dajin.system.shift;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.SyncWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class ShiftControllerWiringTests {
    @Test
    void springCreatesControllerUsingItsExplicitInjectionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DbSupport.class, () -> mock(DbSupport.class));
            context.registerBean(ShiftService.class, () -> mock(ShiftService.class));
            context.registerBean(SyncWebSocketHandler.class, () -> mock(SyncWebSocketHandler.class));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(ShiftController.class);
            context.refresh();

            assertNotNull(context.getBean(ShiftController.class));
        }
    }
}
