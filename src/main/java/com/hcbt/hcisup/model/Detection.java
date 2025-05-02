package com.hcbt.hcisup.model;

import lombok.Data;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * 检测结果类
 * 存储对象检测的结果信息，包括类别ID、类别名称、置信度、颜色和边界框
 */
@Data
public class Detection {
    private int classId = 0;              // 类别ID
    private String className = "";        // 类别名称
    private float confidence = 0.0f;      // 置信度
    private Scalar color;                 // 用于可视化的颜色
    private Rect box;                     // 边界框
}