package com.hcbt.hcisup.service;

import com.hcbt.hcisup.model.Detection;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class FrameDetectionProcessor {
    @Autowired
    private DetectionService detectionService;

    private final ConcurrentHashMap<Integer, ExecutorService> detectionExecutors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> latestResultPaths = new ConcurrentHashMap<>();

    public void startDetection(Integer luserId) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        detectionExecutors.put(luserId, executor);
        executor.submit(() -> runDetectionLoop(luserId));
    }

    private void runDetectionLoop(Integer luserId) {
        String framesDirPath = "/home/elitedatai/hclsup_java/SRS/hls/image/" + luserId;
        String resultsDirPath = framesDirPath + "/results";
        File resultsDir = new File(resultsDirPath);
        if (!resultsDir.exists()) {
            resultsDir.mkdirs();
        }

        String lastProcessedFrame = null;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                File framesDir = new File(framesDirPath);
                File[] frameFiles = framesDir.listFiles((dir, name) -> name.endsWith(".jpg"));
                if (frameFiles == null || frameFiles.length == 0) {
                    Thread.sleep(1000);
                    continue;
                }

                File latestFrame = Arrays.stream(frameFiles)
                        .max(Comparator.comparing(File::lastModified))
                        .orElse(null);

                if (latestFrame == null || latestFrame.getName().equals(lastProcessedFrame)) {
                    Thread.sleep(1000);
                    continue;
                }

                String framePath = latestFrame.getAbsolutePath();
                String resultFilename = "result_" + latestFrame.getName();
                String resultPath = resultsDirPath + "/" + resultFilename;

                Mat image = opencv_imgcodecs.imread(framePath);
                if (image.empty()) {
                    log.error("无法读取帧: {}", framePath);
                    continue;
                }

                try {
                    // Safe call to detection service with error handling
                    List<Detection> detections = detectionService.runInference(image);
                    drawDetections(image, detections);
                    opencv_imgcodecs.imwrite(resultPath, image);

                    lastProcessedFrame = latestFrame.getName();
                    latestResultPaths.put(luserId, resultPath);
                } catch (Exception e) {
                    log.error("运行推理时出错: {}", e.getMessage());
                    // Create an empty detection list to avoid NPE
                    drawDetections(image, new ArrayList<>());
                    // Still try to save the image without detections
                    opencv_imgcodecs.imwrite(resultPath, image);
                }

                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("检测帧时出错，用户 ID: {}", luserId, e);
                try {
                    // Allow some time before retry to avoid CPU spinning
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    public void stopDetection(Integer luserId) {
        ExecutorService executor = detectionExecutors.remove(luserId);
        if (executor != null) {
            executor.shutdownNow();
        }
        latestResultPaths.remove(luserId);
    }

    public String getLatestResultPath(Integer luserId) {
        return latestResultPaths.get(luserId);
    }

    private void drawDetections(Mat image, List<Detection> detections) {
        if (detections == null) {
            log.warn("收到空的检测结果，跳过绘制");
            return;
        }

        for (Detection detection : detections) {
            // Validate detection before using it
            if (detection == null || detection.getBox() == null || detection.getColor() == null) {
                log.warn("检测对象或其属性为空，跳过此检测");
                continue;
            }

            try {
                opencv_imgproc.rectangle(
                        image,
                        detection.getBox(),
                        detection.getColor(),
                        2,
                        opencv_imgproc.LINE_8,
                        0);

                String label = detection.getClassName() + ": " + String.format("%.2f", detection.getConfidence());
                int[] baseLine = new int[1];
                Size labelSize = opencv_imgproc.getTextSize(
                        label,
                        opencv_imgproc.FONT_HERSHEY_SIMPLEX,
                        0.6,
                        1,
                        baseLine);

                int x = Math.max(detection.getBox().x(), 0);
                int y = Math.max(detection.getBox().y() - labelSize.height(), 0);
                if (y < 5) {
                    y = detection.getBox().y() + detection.getBox().height();
                }
                int width = Math.min(labelSize.width(), image.cols() - x);

                opencv_imgproc.rectangle(
                        image,
                        new Point(x, y),
                        new Point(x + width, y + labelSize.height() + baseLine[0]),
                        detection.getColor()
                );

                opencv_imgproc.putText(
                        image,
                        label,
                        new Point(x, y + labelSize.height()),
                        opencv_imgproc.FONT_HERSHEY_SIMPLEX,
                        0.6,
                        new Scalar(255, 255, 255, 0),
                        1,
                        opencv_imgproc.LINE_AA,
                        false);
            } catch (Exception e) {
                log.error("在绘制检测结果时出错: {}", e.getMessage());
            }
        }
    }

    /**
     * 检查用户是否正在进行检测处理
     * @param luserId 用户ID
     * @return 是否正在处理
     */
    public boolean isProcessingUser(Integer luserId) {
        ExecutorService executor = detectionExecutors.get(luserId);
        return executor != null && !executor.isShutdown() && !executor.isTerminated();
    }
}