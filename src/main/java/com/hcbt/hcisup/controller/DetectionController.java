package com.hcbt.hcisup.controller;

import com.hcbt.hcisup.service.DetectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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

    private final Path uploadDir;
    private final Path resultDir;

    public DetectionController(
            DetectionService detectionService,
            @Value("${app.upload-dir}") String uploadDirPath,
            @Value("${app.result-dir}") String resultDirPath) {
        this.detectionService = detectionService;
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
        Path filePath = uploadDir.resolve(filename);
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
}