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

/**
 * 取摄像头视频帧 进行 检测
 */
@Slf4j
@Service
public class FrameDetectionProcessor {
    @Autowired
    private DetectionService detectionService;

    private final String framesDirBasePath;

    private final ConcurrentHashMap<Integer, ExecutorService> detectionExecutors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> latestResultPaths = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> lastProcessedFrames = new ConcurrentHashMap<>();

    // 构造函数，注入framesDirBasePath
    public FrameDetectionProcessor(@Value("${app.stream.frames-dir}") String framesDirBasePath) {
        this.framesDirBasePath = framesDirBasePath;
    }

    // 启动检测流程
    public void startDetection(Integer luserId) {
        // 如果已经在处理，先停止
        stopDetection(luserId);
        // 创建一个新的单线程执行器
        ExecutorService executor = Executors.newSingleThreadExecutor();
        // 将执行器放入map中，以luserId为key
        detectionExecutors.put(luserId, executor);
        // 提交一个任务，执行runDetectionLoop方法
        executor.submit(() -> runDetectionLoop(luserId));
        // 记录日志，用户luserId的检测流程已启动
        log.info("用户 {} 的检测流程已启动", luserId);
    }

    // 运行检测循环
    private void runDetectionLoop(Integer luserId) {
        // 获取用户帧目录路径
        String framesDirPath = framesDirBasePath + luserId;
        // 获取用户结果目录路径
        String resultsDirPath = framesDirPath + "/results";
        // 创建用户结果目录
        File resultsDir = new File(resultsDirPath);
        if (!resultsDir.exists()) {
            resultsDir.mkdirs();
        }

        // 循环检测
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 获取用户帧目录
                File framesDir = new File(framesDirPath);
                // 获取用户帧目录下的所有jpg文件
                File[] frameFiles = framesDir.listFiles((dir, name) -> name.endsWith(".jpg"));
                // 如果没有可用帧，等待500毫秒
                if (frameFiles == null || frameFiles.length == 0) {
                    log.debug("用户 {} 无可用帧，等待中", luserId);
                    Thread.sleep(500);
                    continue;
                }

                // 按文件名排序以确保顺序处理
                List<File> sortedFrames = Arrays.stream(frameFiles)
                        .sorted(Comparator.comparing(File::getName))
                        .toList();

                // 获取上次处理的帧
                String lastProcessedFrame = lastProcessedFrames.get(luserId);
                File frameToProcess = null;

                // 找到下一个未处理的帧
                for (File frame : sortedFrames) {
                    if (lastProcessedFrame == null || frame.getName().compareTo(lastProcessedFrame) > 0) {
                        frameToProcess = frame;
                        break;
                    }
                }

                // 如果没有新帧，等待500毫秒
                if (frameToProcess == null) {
                    log.debug("用户 {} 无新帧，等待中", luserId);
                    Thread.sleep(500);
                    continue;
                }

                // 获取帧路径
                String framePath = frameToProcess.getAbsolutePath();
                // 获取结果文件名
                String resultFilename = "result_" + frameToProcess.getName();
                // 获取结果路径
                String resultPath = resultsDirPath + "/" + resultFilename;

                // 读取帧
                Mat image = opencv_imgcodecs.imread(framePath);
                // 如果无法读取帧，记录错误日志
                if (image.empty()) {
                    log.error("无法读取帧: {}", framePath);
                    continue;
                }

                try {
                    // 运行推理
                    List<Detection> detections = detectionService.runInference(image);
                    // 绘制检测结果
                    drawDetections(image, detections);
                    // 保存结果
                    opencv_imgcodecs.imwrite(resultPath, image);

                    // 更新上次处理的帧和最新结果路径
                    lastProcessedFrames.put(luserId, frameToProcess.getName());
                    latestResultPaths.put(luserId, resultPath);
                    log.info("用户 {} 处理帧: {}，结果保存至: {}", luserId, frameToProcess.getName(), resultPath);
                } catch (Exception e) {
                    // 如果运行推理出错，记录错误日志，绘制空检测结果，保存结果
                    log.error("用户 {} 运行推理时出错: {}", luserId, e.getMessage());
                    drawDetections(image, new ArrayList<>());
                    opencv_imgcodecs.imwrite(resultPath, image);
                    // 更新上次处理的帧和最新结果路径
                    lastProcessedFrames.put(luserId, frameToProcess.getName());
                    latestResultPaths.put(luserId, resultPath);
                }

                // 控制处理速度，避免过快消耗CPU
                Thread.sleep(500);
            } catch (InterruptedException e) {
                // 如果线程被中断，记录日志并退出循环
                Thread.currentThread().interrupt();
                log.info("用户 {} 检测循环被中断", luserId);
                break;
            } catch (Exception e) {
                // 如果检测帧时出错，记录错误日志，等待1秒
                log.error("用户 {} 检测帧时出错: {}", luserId, e.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    // 如果等待时被中断，记录日志并退出循环
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    // 停止检测流程
    public void stopDetection(Integer luserId) {
        // 从检测线程池中移除指定用户的线程池
        ExecutorService executor = detectionExecutors.remove(luserId);
        // 如果线程池存在
        if (executor != null) {
            // 立即停止线程池中的所有任务
            executor.shutdownNow();
            // 记录日志，表示指定用户的检测流程已停止
            log.info("用户 {} 的检测流程已停止", luserId);
        }
        // 从最新结果路径集合中移除指定用户的路径
        latestResultPaths.remove(luserId);
        // 从最后处理的帧集合中移除指定用户的帧
        lastProcessedFrames.remove(luserId);
    }

    // 获取最新结果路径
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

    // 绘制检测结果
    private void drawDetections(Mat image, List<Detection> detections) {
        // 如果检测结果为空，则跳过绘制
        if (detections == null) {
            log.warn("收到空的检测结果，跳过绘制");
            return;
        }

        // 遍历检测结果
        for (Detection detection : detections) {
            // 如果检测对象或其属性为空，则跳过此检测
            if (detection == null || detection.getBox() == null || detection.getColor() == null) {
                log.warn("检测对象或其属性为空，跳过此检测");
                continue;
            }

            try {
                // 在图像上绘制矩形框
                opencv_imgproc.rectangle(
                        image,
                        detection.getBox(),
                        detection.getColor(),
                        2,
                        opencv_imgproc.LINE_8,
                        0);

                // 获取标签文本
                String label = detection.getClassName() + ": " + String.format("%.2f", detection.getConfidence());
                // 获取标签文本大小
                int[] baseLine = new int[1];
                Size labelSize = opencv_imgproc.getTextSize(
                        label,
                        opencv_imgproc.FONT_HERSHEY_SIMPLEX,
                        0.6,
                        1,
                        baseLine);

                // 计算标签文本位置
                int x = Math.max(detection.getBox().x(), 0);
                int y = Math.max(detection.getBox().y() - labelSize.height(), 0);
                if (y < 5) {
                    y = detection.getBox().y() + detection.getBox().height();
                }
                int width = Math.min(labelSize.width(), image.cols() - x);

                // 在图像上绘制标签背景
                opencv_imgproc.rectangle(
                        image,
                        new Point(x, y),
                        new Point(x + width, y + labelSize.height() + baseLine[0]),
                        detection.getColor()
                );

                // 在图像上绘制标签文本
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
