package com.dajin.system.upload;

import com.dajin.system.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static org.junit.jupiter.api.Assertions.*;

// Real embedded Tomcat validates the application.yml limits before a controller sees the file.
// No store, credentials, database, MinIO or business writes are involved.
@SpringBootTest(classes = MultipartLimitsTests.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultipartLimitsTests {
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({Probe.class, GlobalExceptionHandler.class})
    static class TestApp {}

    @RestController
    static class Probe {
        @PostMapping("/test/multipart") public long size(@RequestPart("file") MultipartFile file) { return file.getSize(); }
    }
    @Autowired TestRestTemplate http;
    private ResponseEntity<String> upload(int size) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new ByteArrayResource(new byte[size]) {
            @Override public String getFilename() { return "size-test.jpg"; }
        });
        var headers = new HttpHeaders(); headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.postForEntity("/test/multipart", new HttpEntity<>(body, headers), String.class);
    }
    @Test void acceptsAboveOldOneMegabyteLimit() {
        var response = upload(1_100_000);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("1100000", response.getBody());
    }
    @Test void rejectsMoreThanFiveMegabytesWithChineseExplanation() {
        var response = upload(5 * 1024 * 1024 + 1);
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertTrue(response.getBody().contains("5MB"));
    }
}
