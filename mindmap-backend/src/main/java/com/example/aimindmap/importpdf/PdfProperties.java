package com.example.aimindmap.importpdf;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 阶段 5 PDF 导入限制与存储配置（v1.1 §15、修订第 28 条）。 */
@ConfigurationProperties(prefix = "app.pdf")
public class PdfProperties {

    /** 上传文件保存目录（不对外提供静态访问）。 */
    private String uploadDir = "./data/pdf-uploads";
    /** 单文件大小上限（MB）。 */
    private int maxFileSizeMb = 20;
    /** 最大页数。 */
    private int maxPages = 100;
    /** 判定扫描件：平均每页可提取字符数下限。 */
    private int minCharsPerPage = 100;
    /** 判定扫描件：空白页占比上限，超过则疑似扫描件。 */
    private double maxEmptyPageRatio = 0.5d;

    public String getUploadDir() {
        return uploadDir;
    }

    public void setUploadDir(String uploadDir) {
        this.uploadDir = uploadDir;
    }

    public int getMaxFileSizeMb() {
        return maxFileSizeMb;
    }

    public void setMaxFileSizeMb(int maxFileSizeMb) {
        this.maxFileSizeMb = maxFileSizeMb;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }

    public int getMinCharsPerPage() {
        return minCharsPerPage;
    }

    public void setMinCharsPerPage(int minCharsPerPage) {
        this.minCharsPerPage = minCharsPerPage;
    }

    public double getMaxEmptyPageRatio() {
        return maxEmptyPageRatio;
    }

    public void setMaxEmptyPageRatio(double maxEmptyPageRatio) {
        this.maxEmptyPageRatio = maxEmptyPageRatio;
    }
}
