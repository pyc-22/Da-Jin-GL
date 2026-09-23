package com.dajin.system.admin;

import com.dajin.system.common.BusinessException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

@Service
public class BackupService {
    private final Environment env;
    private final AtomicBoolean running = new AtomicBoolean();

    public BackupService(Environment env) { this.env = env; }

    public Map<String,Object> create() {
        if (!running.compareAndSet(false, true)) throw new BusinessException(409450, "已有备份正在执行");
        Path sql = null, compressed = null;
        Process process = null;
        try {
            String jdbcUrl = env.getRequiredProperty("spring.datasource.url");
            if (!jdbcUrl.startsWith("jdbc:mysql://")) throw new IllegalArgumentException();
            URI uri = URI.create(jdbcUrl.substring(5));
            String database = uri.getPath().substring(1);
            if (uri.getHost() == null || !database.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException();
            Path directory = directory();
            Files.createDirectories(directory);
            sql = privateTemp(directory, ".sql.part");
            compressed = privateTemp(directory, ".gz.part");
            List<String> command = List.of(env.getProperty("BACKUP_MYSQLDUMP", "mysqldump"),
                    "--host=" + uri.getHost(), "--port=" + (uri.getPort() < 0 ? 3306 : uri.getPort()),
                    "--user=" + env.getRequiredProperty("spring.datasource.username"), "--protocol=TCP",
                    "--single-transaction", "--quick", "--skip-lock-tables", "--no-tablespaces",
                    "--hex-blob", "--routines", "--events", "--triggers", "--default-character-set=utf8mb4", database);
            ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(sql.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            // Keep credentials out of command lines, logs and returned metadata.
            builder.environment().put("MYSQL_PWD", env.getProperty("spring.datasource.password", ""));
            process = builder.start();
            if (!process.waitFor(90, TimeUnit.SECONDS)) throw new BusinessException(504450, "数据库备份超时，未生成完成文件");
            if (process.exitValue() != 0 || Files.size(sql) == 0) throw new BusinessException(500450, "数据库备份失败，请检查数据库连接、备份账号权限和客户端版本");
            try (InputStream input = Files.newInputStream(sql); OutputStream output = new GZIPOutputStream(Files.newOutputStream(compressed))) {
                input.transferTo(output);
            }
            String name = "dajin_backup_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + "_" + UUID.randomUUID().toString().substring(0,8) + ".sql.gz";
            Path target = directory.resolve(name);
            try { Files.move(compressed, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(compressed, target); }
            return metadata(target);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(500450, "数据库备份已中断");
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            throw new BusinessException(500450, "无法执行数据库备份，请检查 BACKUP_MYSQLDUMP、数据库配置及备份目录权限");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            deletePartial(sql);
            deletePartial(compressed);
            running.set(false);
        }
    }

    public List<Map<String,Object>> list() {
        if (!Files.isDirectory(directory())) return List.of();
        try (var paths = Files.list(directory())) {
            List<Map<String,Object>> result = new ArrayList<>();
            for (Path file : paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && path.getFileName().toString().matches("dajin_backup_[A-Za-z0-9_]+\\.sql\\.gz")).sorted(Comparator.reverseOrder()).toList()) {
                result.add(metadata(file));
            }
            return result;
        } catch (IOException e) { throw new BusinessException(500450, "无法读取备份目录"); }
    }

    private Path directory() { return Path.of(env.getProperty("BACKUP_DIRECTORY", "./backup")).toAbsolutePath().normalize(); }
    private Path privateTemp(Path directory, String suffix) throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix"))
            return Files.createTempFile(directory, ".backup-", suffix, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        return Files.createTempFile(directory, ".backup-", suffix);
    }
    private Map<String,Object> metadata(Path file) throws IOException {
        return Map.of("status", "COMPLETED", "fileName", file.getFileName().toString(), "size", Files.size(file), "createdAt", Files.getLastModifiedTime(file).toInstant().toString());
    }
    private void deletePartial(Path file) {
        if (file != null) try { Files.deleteIfExists(file); } catch (IOException ignored) { }
    }
}
