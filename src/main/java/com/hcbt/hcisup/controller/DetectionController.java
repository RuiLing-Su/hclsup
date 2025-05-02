package com.hcbt.hcisup.controller;

import com.hcbt.hcisup.service.DetectionService;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/detection")
@Tag(name = "检测")
public class DetectionController {
    private final DetectionService detectionService;
    private final StreamingService streamingService;

    private final Path uploadDir;
    private final Path resultDir;

    @Autowired
    private FrameDetectionProcessor frameDetectionProcessor;

    public DetectionController(
            DetectionService detectionService, StreamingService streamingService,
            @Value("${app.upload-dir}") String uploadDirPath,
            @Value("${app.result-dir}") String resultDirPath) {
        this.detectionService = detectionService;
        this.streamingService = streamingService;
        this.uploadDir = Paths.get(uploadDirPath);
        this.resultDir = Paths.get(resultDirPath);
        try {
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }
            if (!Files.exists(resultDir)) {
                Files.createDirectories(resultDir);
            }
        } catch (IOException e) {
            throw new RuntimeException("无法创建上传和结果目录", e);
        }
    }

    @PostMapping(value = "/detect", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "处理图像上传和对象检测", description = "传入图片")
    public ResponseEntity<?> detectObjects(@RequestParam("file") MultipartFile file) throws IOException {
        // 保存上传的文件
        String filename = file.getOriginalFilename();
        Path filePath = Path.of("C:\\Users\\Su180\\IdeaProjects\\yolocv\\yolo123\\uploads" + filename);
        file.transferTo(filePath.toFile());

        // 调用服务进行检测并获取结果文件名
        String resultFilename = detectionService.detectImage(filePath.toString());

        // 准备响应
        Map<String, Object> response = new HashMap<>();
        response.put("resultFile", resultFilename);

        return ResponseEntity.ok(response);
    }

    /**
     * 获取结果图像文件
     * @param filename 结果图像文件名
     * @return 图像文件
     */
    @GetMapping("/result")
    @Operation(summary = "获取结果图像文件")
    public ResponseEntity<byte[]> getResultImage(@RequestParam("filename") String filename) throws IOException {
        Path resultPath = resultDir.resolve(filename);
        File resultFile = resultPath.toFile();

        if (!resultFile.exists()) {
            return ResponseEntity.notFound().build();
        }

        byte[] imageBytes = Files.readAllBytes(resultPath);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(imageBytes);
    }

    @PostMapping(value = "/detect-video", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "处理视频上传和对象检测", description = "传入视频")
    public ResponseEntity<?> detectObjectsInVideo(@RequestParam("file") MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        Path filePath = uploadDir.resolve(filename);
        file.transferTo(filePath.toFile());

        String resultFilename = detectionService.detectVideo(filePath.toString());

        Map<String, Object> response = new HashMap<>();
        response.put("resultFile", resultFilename);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/result-video")
    @Operation(summary = "获取结果视频文件")
    public ResponseEntity<Resource> getResultVideo(@RequestParam("filename") String filename) throws IOException {
        Path resultPath = resultDir.resolve(filename);
        File resultFile = resultPath.toFile();

        if (!resultFile.exists()) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(resultFile);
        String contentType = Files.probeContentType(resultPath);
        if (contentType == null) {
            contentType = "video/mp4"; // 默认视频类型
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
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

    /**
     * 启动检测流程
     * @param luserId 用户ID
     * @return 操作结果
     */
    @PostMapping("/start/{luserId}")
    @Operation(summary = "启动用户的检测流程")
    public ResponseEntity<?> startDetection(@PathVariable("luserId") Integer luserId) {
        frameDetectionProcessor.startDetection(luserId);
        Map<String, Object> response = new HashMap<>();
        response.put("status", "started");
        response.put("luserId", luserId);
        return ResponseEntity.ok(response);
    }

    /**
     * 停止检测流程
     * @param luserId 用户ID
     * @return 操作结果
     */
    @PostMapping("/stop/{luserId}")
    @Operation(summary = "停止用户的检测流程")
    public ResponseEntity<?> stopDetection(@PathVariable("luserId") Integer luserId) {
        frameDetectionProcessor.stopDetection(luserId);
        Map<String, Object> response = new HashMap<>();
        response.put("status", "stopped");
        response.put("luserId", luserId);
        return ResponseEntity.ok(response);
    }
}