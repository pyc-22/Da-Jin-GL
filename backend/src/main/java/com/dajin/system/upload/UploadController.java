package com.dajin.system.upload;

import com.dajin.system.common.ApiResponse;
import com.dajin.system.common.BusinessException;
import io.minio.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/upload")
public class UploadController {
    private final MinioClient client;
    private final String bucket;
    public UploadController(@Value("${minio.endpoint}") String endpoint,
                            @Value("${minio.access-key}") String accessKey, @Value("${minio.secret-key}") String secretKey, @Value("${minio.bucket}") String bucket) {
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<?> upload(@RequestPart("file") MultipartFile file,
                                 @RequestParam(required = false) String bizType,
                                 @RequestParam(required = false) String orderNo) throws Exception {
        if (file.isEmpty()) throw new BusinessException(400740, "上传文件不能为空");
        if (file.getSize() > 5 * 1024 * 1024) throw new BusinessException(400741, "图片不能超过 5MB");
        byte[] content = file.getBytes();
        ImageFormat format = detectImage(content);
        if (format == null) throw new BusinessException(400742, "图片格式与文件内容不符，仅支持 JPEG、PNG、GIF、WebP、HEIC/HEIF");
        String directory = sanitizeSegment(bizType, "goods");
        String orderSegment = sanitizeSegment(orderNo, "");
        String prefix = "processing".equals(directory) && !orderSegment.isBlank()
                ? directory + "/" + orderSegment + "/"
                : directory + "/";
        String object = prefix + UUID.randomUUID() + format.extension();
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            try (ByteArrayInputStream stream = new ByteArrayInputStream(content)) {
                client.putObject(PutObjectArgs.builder().bucket(bucket).object(object).stream(stream, content.length, -1).contentType(format.contentType()).build());
            }
            return ApiResponse.ok(Map.of("object", object, "url", "/api/file/" + object, "storage", "minio"));
        } catch (Exception minioError) {
            throw new IllegalStateException("对象存储不可用，请检查 MinIO 服务状态", minioError);
        }
    }

    static ImageFormat detectImage(byte[] bytes) {
        if (bytes == null) return null;
        if (matches(bytes, 0, 0xFF, 0xD8, 0xFF)) return new ImageFormat(".jpg", "image/jpeg");
        if (matches(bytes, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return new ImageFormat(".png", "image/png");
        if (ascii(bytes, 0, "GIF87a") || ascii(bytes, 0, "GIF89a")) return new ImageFormat(".gif", "image/gif");
        if (ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP")) return new ImageFormat(".webp", "image/webp");
        if (ascii(bytes, 4, "ftyp") && bytes.length >= 12) {
            String brand = new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).toLowerCase();
            if (Set.of("heic", "heix", "hevc", "hevx").contains(brand)) return new ImageFormat(".heic", "image/heic");
            if (Set.of("mif1", "msf1", "heim", "heis").contains(brand)) return new ImageFormat(".heif", "image/heif");
        }
        return null;
    }

    private static boolean matches(byte[] bytes, int offset, int... expected) {
        if (offset < 0 || bytes.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++) if ((bytes[offset + i] & 0xFF) != expected[i]) return false;
        return true;
    }

    private static boolean ascii(byte[] bytes, int offset, String expected) {
        return matches(bytes, offset, expected.chars().toArray());
    }

    private static String sanitizeSegment(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        if (normalized.length() > 64) normalized = normalized.substring(0, 64);
        return normalized.isBlank() ? fallback : normalized;
    }

    record ImageFormat(String extension, String contentType) { }
}
