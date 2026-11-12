package com.example.aimindmap.mindmap.graph;

/**
 * 节点在画布上的自由坐标（v1.1 §7.2，2026-09-13 修订）。
 * layout=null 时使用 ELK 自动布局；用户拖拽后写入坐标。
 */
public class NodeLayout {

    private double x;
    private double y;
    private boolean manuallyPositioned;

    public NodeLayout() {
    }

    public NodeLayout(double x, double y, boolean manuallyPositioned) {
        this.x = x;
        this.y = y;
        this.manuallyPositioned = manuallyPositioned;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public boolean isManuallyPositioned() {
        return manuallyPositioned;
    }

    public void setManuallyPositioned(boolean manuallyPositioned) {
        this.manuallyPositioned = manuallyPositioned;
    }
}