package com.hcbt.hcisup.controller;

import com.hcbt.hcisup.service.FrameDetectionProcessor;
import com.hcbt.hcisup.service.StreamingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

@Slf4j
@RestController
@RequestMapping("/recording")
@Tag(name = "检测控制接口")
public class VideoRecordingController {

    private final StreamingService streamingService;
    private final Path recordingDir;

    @Autowired
    private FrameDetectionProcessor frameDetectionProcessor;

    public VideoRecordingController(
            StreamingService streamingService,
            @Value("${app.stream.recording-dir}") String recordingDirPath) {
        this.streamingService = streamingService;
        this.recordingDir = Paths.get(recordingDirPath);

        try {
            if (!Files.exists(recordingDir)) {
                Files.createDirectories(recordingDir);
            }
        } catch (IOException e) {
            throw new RuntimeException("无法创建录制目录", e);
        }
    }


    @GetMapping("/latest/{luserId}")
    @Operation(summary = "获取最新的检测结果图像")
    public ResponseEntity<byte[]> getLatestDetectionResult(@PathVariable("luserId") Integer luserId) throws IOException {
        String resultPath = frameDetectionProcessor.getLatestResultPath(luserId);
        if (resultPath == null) {
            return ResponseEntity.notFound().build();
        }
        Path path = Paths.get(resultPath);
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        byte[] imageBytes = Files.readAllBytes(path);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(imageBytes);
    }


    /**
     * 获取检测结果视频流，可通过VLC等播放器播放
     * @param luserId 用户ID
     * @return 视频流
     */
    @GetMapping("/stream/{luserId}")
    @Operation(summary = "获取实时检测结果视频流", description = "返回可通过VLC等播放器播放的MJPEG视频流")
    public ResponseEntity<StreamingResponseBody> getDetectionVideoStream(@PathVariable("luserId") Integer luserId) {
        if (!frameDetectionProcessor.isProcessingUser(luserId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("multipart/x-mixed-replace; boundary=frame"));

        StreamingResponseBody responseBody = outputStream -> {
            streamingService.streamMjpegFromDetectionFrames(luserId, outputStream);
        };

        return ResponseEntity.ok()
                .headers(headers)
                .body(responseBody);
    }

//    /**
//     * 启动检测流程
//     * @param luserId 用户ID
//     * @return 操作结果
//     */
//    @PostMapping("/start/{luserId}")
//    @Operation(summary = "启动用户的检测流程")
//    public ResponseEntity<?> startDetection(@PathVariable("luserId") Integer luserId) {
//        frameDetectionProcessor.startDetection(luserId);
//        Map<String, Object> response = new HashMap<>();
//        response.put("status", "started");
//        response.put("luserId", luserId);
//        return ResponseEntity.ok(response);
//    }
//
//    /**
//     * 停止检测流程
//     * @param luserId 用户ID
//     * @return 操作结果
//     */
//    @PostMapping("/stop/{luserId}")
//    @Operation(summary = "停止用户的检测流程")
//    public ResponseEntity<?> stopDetection(@PathVariable("luserId") Integer luserId) {
//        frameDetectionProcessor.stopDetection(luserId);
//        Map<String, Object> response = new HashMap<>();
//        response.put("status", "stopped");
//        response.put("luserId", luserId);
//        return ResponseEntity.ok(response);
//    }

    /**
     * 录制检测流视频
     * @param luserId 用户ID
     * @param duration 录制时长（秒）
     * @return 录制结果
     */
    @PostMapping("/record/{luserId}")
    @Operation(summary = "录制指定用户的检测视频", description = "生成MP4视频文件")
    public ResponseEntity<?> recordVideo(
            @PathVariable("luserId") Integer luserId,
            @RequestParam(value = "duration", defaultValue = "60") int duration) {

        // 限制最大录制时长为10分钟
        if (duration > 600) {
            duration = 600;
        }

        // 生成唯一的文件名
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = "recording_" + luserId + "_" + timestamp + ".mp4";
        Path outputPath = recordingDir.resolve(filename);

        boolean success = streamingService.generateMp4FromDetectionFrames(
                luserId, outputPath.toString(), duration);

        Map<String, Object> response = new HashMap<>();
        if (success) {
            response.put("status", "success");
            response.put("filename", filename);
            response.put("duration", duration);
        } else {
            response.put("status", "error");
            response.put("message", "无法生成视频录制");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 获取录制的视频文件
     * @param filename 文件名
     * @return 视频文件
     */
    @GetMapping("/download")
    @Operation(summary = "下载录制的视频文件")
    public ResponseEntity<Resource> downloadRecording(@RequestParam("filename") String filename) {
        Path filePath = recordingDir.resolve(filename);
        File file = filePath.toFile();

        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(file);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("video/mp4"))
                .body(resource);
    }

    /**
     * 获取所有录制文件列表
     * @param luserId 可选，特定用户ID
     * @return 文件列表
     */
    @GetMapping("/list")
    @Operation(summary = "获取录制的视频文件列表")
    public ResponseEntity<?> listRecordings(
            @RequestParam(value = "luserId", required = false) Integer luserId) {

        try {
            Map<String, Object> response = new HashMap<>();
            Map<String, Object> fileInfo = new HashMap<>();

            try (Stream<Path> paths = Files.list(recordingDir)) {
                paths.filter(path -> path.toString().endsWith(".mp4"))
                        .forEach(path -> {
                            File file = path.toFile();
                            String fileName = file.getName();
                            if (luserId == null || fileName.contains("_" + luserId + "_")) {
                                try {
                                    fileInfo.put(fileName, new HashMap<String, Object>() {{
                                        put("size", file.length());
                                        put("lastModified", file.lastModified());
                                        put("path", "/recording/download?filename=" + fileName);
                                    }});
                                } catch (Exception e) {
                                    log.error("处理文件 {} 时出错: {}", fileName, e.getMessage());
                                }
                            }
                        });
            }

            response.put("recordings", fileInfo);
            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("列出录制文件时出错: {}", e.getMessage());
            return ResponseEntity.status(500)
                    .body(new HashMap<String, Object>() {{
                        put("status", "error");
                        put("message", "无法列出录制文件");
                    }});
        }
    }

    /**
     * 删除指定的录制视频文件
     * @param filename 文件名
     * @return 删除结果
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除录制的视频文件")
    public ResponseEntity<?> deleteRecording(@RequestParam("filename") String filename) {
        Path filePath = recordingDir.resolve(filename);
        File file = filePath.toFile();

        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        try {
            boolean deleted = Files.deleteIfExists(filePath);
            if (deleted) {
                Map<String, Object> response = new HashMap<>();
                response.put("status", "success");
                response.put("message", "文件 " + filename + " 已删除");
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.status(500)
                        .body(new HashMap<String, Object>() {{
                            put("status", "error");
                            put("message", "无法删除文件 " + filename);
                        }});
            }
        } catch (IOException e) {
            log.error("删除文件 {} 时出错: {}", filename, e.getMessage());
            return ResponseEntity.status(500)
                    .body(new HashMap<String, Object>() {{
                        put("status", "error");
                        put("message", "删除文件失败: " + e.getMessage());
                    }});
        }
    }

    /**
     * 清理特定用户的旧录制文件
     * @param luserId 用户ID
     * @param daysBefore 删除多少天前的文件
     * @return 清理结果
     */
    @PostMapping("/cleanup/{luserId}")
    @Operation(summary = "清理特定用户的旧录制文件")
    public ResponseEntity<?> cleanupRecordings(
            @PathVariable("luserId") Integer luserId,
            @RequestParam(value = "daysBefore", defaultValue = "30") int daysBefore) {

        try {
            long cutoffTime = System.currentTimeMillis() - (daysBefore * 24L * 60 * 60 * 1000);
            Map<String, Object> response = new HashMap<>();
            int deletedCount = 0;

            try (Stream<Path> paths = Files.list(recordingDir)) {
                for (Path path : paths.filter(path -> path.toString().endsWith(".mp4")).toList()) {
                    File file = path.toFile();
                    String fileName = file.getName();
                    if (fileName.contains("_" + luserId + "_") && file.lastModified() < cutoffTime) {
                        try {
                            Files.deleteIfExists(path);
                            deletedCount++;
                            log.info("已删除旧录制文件: {}", fileName);
                        } catch (IOException e) {
                            log.error("删除旧录制文件 {} 时出错: {}", fileName, e.getMessage());
                        }
                    }
                }
            }

            response.put("status", "success");
            response.put("deletedCount", deletedCount);
            response.put("message", "已清理 " + deletedCount + " 个旧录制文件");
            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("清理旧录制文件时出错: {}", e.getMessage());
            return ResponseEntity.status(500)
                    .body(new HashMap<String, Object>() {{
                        put("status", "error");
                        put("message", "清理旧录制文件失败: " + e.getMessage());
                    }});
        }
    }
}