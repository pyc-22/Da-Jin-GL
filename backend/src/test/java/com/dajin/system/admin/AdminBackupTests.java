package com.dajin.system.admin;

import com.dajin.system.common.DbSupport;
import com.dajin.system.config.SyncWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminBackupTests {
    @Test void backupMustNotReportSuccessWithoutAnArtifact() {
        var db = mock(DbSupport.class);
        when(db.jdbc()).thenReturn(mock(NamedParameterJdbcTemplate.class));
        var backups = mock(BackupService.class);
        when(backups.create()).thenReturn(Map.of("status","COMPLETED","fileName","test.sql.gz"));
        var controller = new AdminController(db, mock(SyncWebSocketHandler.class), backups);
        var result = (Map<?,?>) controller.backup(new MockHttpServletRequest()).data();
        assertEquals("COMPLETED", result.get("status"));
        assertNotNull(result.get("fileName"));
        verify(backups).create();
    }

    @Test void failedDumpLeavesNoCompletedFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("spring.datasource.url","jdbc:mysql://127.0.0.1:13306/dajin_repair_test")
                .withProperty("spring.datasource.username","root")
                .withProperty("BACKUP_DIRECTORY",directory.toString())
                .withProperty("BACKUP_MYSQLDUMP",directory.resolve("missing-mysqldump").toString());
        var service = new BackupService(env);
        assertThrows(com.dajin.system.common.BusinessException.class, service::create);
        assertTrue(service.list().isEmpty());
        try (var files = java.nio.file.Files.list(directory)) { assertEquals(0, files.count()); }
    }
}
