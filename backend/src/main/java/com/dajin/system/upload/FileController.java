package com.dajin.system.upload;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/file")
public class FileController {
    private static final Map<String, String> TYPES = Map.of(
            ".jpg", "image/jpeg", ".jpeg", "image/jpeg", ".png", "image/png",
            ".webp", "image/webp", ".gif", "image/gif", ".heic", "image/heic", ".heif", "image/heif");
    private final MinioClient client;
    private final String bucket;
    public FileController(@Value("${minio.endpoint}") String endpoint,
                          @Value("${minio.access-key}") String accessKey, @Value("${minio.secret-key}") String secretKey, @Value("${minio.bucket}") String bucket) {
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
    }

    @GetMapping("/**")
    public void serve(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String path = request.getRequestURI();
        String object = path.substring("/api/file/".length());
        if (object.isBlank() || object.contains("..")) { response.sendError(HttpStatus.NOT_FOUND.value()); return; }
        String ext = object.contains(".") ? object.substring(object.lastIndexOf('.')).toLowerCase(Locale.ROOT) : "";
        String type = TYPES.get(ext);
        if (type == null) { response.sendError(HttpStatus.NOT_FOUND.value()); return; }
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(object).build());
            response.setContentType(type);
            response.setHeader("Cache-Control", "public, max-age=86400");
            try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(object).build())) {
                in.transferTo(response.getOutputStream());
            }
        } catch (Exception e) {
            response.sendError(HttpStatus.NOT_FOUND.value());
        }
    }
}
