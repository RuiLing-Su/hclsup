package com.hcbt.hcisup.service;

import com.hcbt.hcisup.model.Detection;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    private final String framesDirBasePath;

    private final ConcurrentHashMap<Integer, ExecutorService> detectionExecutors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> latestResultPaths = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> lastProcessedFrames = new ConcurrentHashMap<>();

    public FrameDetectionProcessor(@Value("${app.stream.frames-dir}") String framesDirBasePath) {
        this.framesDirBasePath = framesDirBasePath;
    }

    public void startDetection(Integer luserId) {
        // 如果已经在处理，先停止
        stopDetection(luserId);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        detectionExecutors.put(luserId, executor);
        executor.submit(() -> runDetectionLoop(luserId));

        log.info("用户 {} 的检测流程已启动", luserId);
    }

    private void runDetectionLoop(Integer luserId) {
        String framesDirPath = framesDirBasePath + luserId;
        String resultsDirPath = framesDirPath + "/results";
        File resultsDir = new File(resultsDirPath);
        if (!resultsDir.exists()) {
            resultsDir.mkdirs();
        }

        while (!Thread.currentThread().isInterrupted()) {
            try {
                File framesDir = new File(framesDirPath);
                File[] frameFiles = framesDir.listFiles((dir, name) -> name.endsWith(".jpg"));
                if (frameFiles == null || frameFiles.length == 0) {
                    log.debug("用户 {} 无可用帧，等待中", luserId);
                    Thread.sleep(500);
                    continue;
                }

                // 按文件名排序以确保顺序处理
                List<File> sortedFrames = Arrays.stream(frameFiles)
                        .sorted(Comparator.comparing(File::getName))
                        .toList();

                String lastProcessedFrame = lastProcessedFrames.get(luserId);
                File frameToProcess = null;

                // 找到下一个未处理的帧
                for (File frame : sortedFrames) {
                    if (lastProcessedFrame == null || frame.getName().compareTo(lastProcessedFrame) > 0) {
                        frameToProcess = frame;
                        break;
                    }
                }

                if (frameToProcess == null) {
                    log.debug("用户 {} 无新帧，等待中", luserId);
                    Thread.sleep(500);
                    continue;
                }

                String framePath = frameToProcess.getAbsolutePath();
                String resultFilename = "result_" + frameToProcess.getName();
                String resultPath = resultsDirPath + "/" + resultFilename;

                Mat image = opencv_imgcodecs.imread(framePath);
                if (image.empty()) {
                    log.error("无法读取帧: {}", framePath);
                    continue;
                }

                try {
                    List<Detection> detections = detectionService.runInference(image);
                    drawDetections(image, detections);
                    opencv_imgcodecs.imwrite(resultPath, image);

                    lastProcessedFrames.put(luserId, frameToProcess.getName());
                    latestResultPaths.put(luserId, resultPath);
                    log.info("用户 {} 处理帧: {}，结果保存至: {}", luserId, frameToProcess.getName(), resultPath);
                } catch (Exception e) {
                    log.error("用户 {} 运行推理时出错: {}", luserId, e.getMessage());
                    drawDetections(image, new ArrayList<>());
                    opencv_imgcodecs.imwrite(resultPath, image);
                    lastProcessedFrames.put(luserId, frameToProcess.getName());
                    latestResultPaths.put(luserId, resultPath);
                }

                // 控制处理速度，避免过快消耗CPU
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("用户 {} 检测循环被中断", luserId);
                break;
            } catch (Exception e) {
                log.error("用户 {} 检测帧时出错: {}", luserId, e.getMessage());
                try {
                    Thread.sleep(1000);
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
            log.info("用户 {} 的检测流程已停止", luserId);
        }
        latestResultPaths.remove(luserId);
        lastProcessedFrames.remove(luserId);
    }

    public String getLatestResultPath(Integer luserId) {
        return latestResultPaths.get(luserId);
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

    private void drawDetections(Mat image, List<Detection> detections) {
        if (detections == null) {
            log.warn("收到空的检测结果，跳过绘制");
            return;
        }

        for (Detection detection : detections) {
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
}