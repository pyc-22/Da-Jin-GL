package com.dajin.system.upload;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.JwtService;
import io.jsonwebtoken.Claims;
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
    private final DbSupport db;
    private final JwtService jwt;
    public FileController(@Value("${minio.endpoint}") String endpoint,
                          @Value("${minio.access-key}") String accessKey, @Value("${minio.secret-key}") String secretKey, @Value("${minio.bucket}") String bucket,
                          DbSupport db, JwtService jwt) {
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
        this.db = db;
        this.jwt = jwt;
    }

    @GetMapping("/**")
    public void serve(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String path = request.getRequestURI();
        String object = path.substring("/api/file/".length());
        if (object.isBlank() || object.contains("..")) { response.sendError(HttpStatus.NOT_FOUND.value()); return; }
        if (object.startsWith("processing/")) {
            if (!canReadProcessingPhoto(object, request)) { response.sendError(HttpStatus.FORBIDDEN.value()); return; }
        }
        String ext = object.contains(".") ? object.substring(object.lastIndexOf('.')).toLowerCase(Locale.ROOT) : "";
        String type = TYPES.get(ext);
        if (type == null) { response.sendError(HttpStatus.NOT_FOUND.value()); return; }
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(object).build());
            response.setContentType(type);
            response.setHeader("Cache-Control", object.startsWith("processing/") ? "private, max-age=60" : "public, max-age=86400");
            if (object.startsWith("processing/")) response.setHeader("Referrer-Policy", "no-referrer");
            try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(object).build())) {
                in.transferTo(response.getOutputStream());
            }
        } catch (Exception e) {
            response.sendError(HttpStatus.NOT_FOUND.value());
        }
    }

    /** Processing photos are private. New clients send the session token as a Bearer header. */
    private boolean canReadProcessingPhoto(String object, HttpServletRequest request) {
        String[] parts = object.split("/", 4);
        if (parts.length < 3) return false;
        String orderNo = parts[1];
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) token = token.substring(7);
        if (token == null || token.isBlank()) token = request.getParameter("access_token");
        if (token == null || token.isBlank()) return false;
        try {
            Claims claims = jwt.parse(token);
            long userId = Long.parseLong(claims.getSubject());
            long storeId = ((Number) claims.get("storeId")).longValue();
            String role = String.valueOf(claims.get("role"));
            String scope = switch (role.toUpperCase(java.util.Locale.ROOT)) {
                case "ADMIN", "MANAGER", "CASHIER" -> "1=1";
                case "SALES" -> "(created_by=:uid or sales_id=:uid)";
                case "CRAFTSMAN" -> "craftsman_id=:uid";
                default -> null;
            };
            if (scope == null) return false;
            String sql = "select count(*) from processing_order where store_id=:store and order_no=:no and " + scope;
            Integer count = db.jdbc().queryForObject(sql, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                    .addValue("store", storeId).addValue("no", orderNo).addValue("uid", userId), Integer.class);
            return count != null && count > 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
