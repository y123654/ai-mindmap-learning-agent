package com.example.aimindmap.parser;

/**
 * v1.1 方案 §8.2 块类型。
 */
public enum BlockType {
    HEADING,
    PARAGRAPH,
    BULLET_LIST_ITEM,
    ORDERED_LIST_ITEM,
    QUOTE,
    CODE_BLOCK,
    TABLE,
    OTHER
}