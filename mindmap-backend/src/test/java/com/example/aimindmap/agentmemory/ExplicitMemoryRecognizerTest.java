package com.example.aimindmap.agentmemory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExplicitMemoryRecognizerTest {

    private final ExplicitMemoryRecognizer recognizer = new ExplicitMemoryRecognizer();

    @Test
    void recognizesAllCommandVariants() {
        assertRecognized("记住，我喜欢用代码例子学习", "我喜欢用代码例子学习");
        assertRecognized("请记住我是软件工程专业的", "我是软件工程专业的");
        assertRecognized("帮我记住：期末考试在下周", "期末考试在下周");
        assertRecognized("请你记住,我总是混淆进程和线程", "我总是混淆进程和线程");
        assertRecognized("麻烦记住 这周要复习数据库第三章", "这周要复习数据库第三章");
        assertRecognized("  记住：我叫张三", "我叫张三");
        assertRecognized("你记住这个偏好", "这个偏好");
        assertRecognized("请帮我麻烦记住：多多用例子", "多多用例子");
    }

    @Test
    void emptyBodyAfterCommandIsNotRecognized() {
        assertThat(recognizer.recognize("记住，")).isNull();
        assertThat(recognizer.recognize("记住 ")).isNull();
        assertThat(recognizer.recognize("记住：")).isNull();
    }

    @Test
    void ordinaryChatIsNotRecognized() {
        assertThat(recognizer.recognize("我记住了上次的错误")).isNull();
        assertThat(recognizer.recognize("这个知识点要记住")).isNull();
        assertThat(recognizer.recognize("什么是进程，帮我解释下")).isNull();
        assertThat(recognizer.recognize("我会记住这些内容的")).isNull();
        assertThat(recognizer.recognize("我们要先记住公式再做题")).isNull();
    }

    @Test
    void recognizedMemoryCarriesDeterministicType() {
        assertThat(recognizer.recognize("记住，我喜欢用例子").memoryType())
                .isEqualTo(MemoryTypeRules.PREFERENCE);
        assertThat(recognizer.recognize("记住，我是本科大三的").memoryType())
                .isEqualTo(MemoryTypeRules.PROFILE);
        assertThat(recognizer.recognize("记住，我总混淆两个概念").memoryType())
                .isEqualTo(MemoryTypeRules.WEAKNESS);
        assertThat(recognizer.recognize("记住，我的目标是期末考90分").memoryType())
                .isEqualTo(MemoryTypeRules.GOAL);
        assertThat(recognizer.recognize("记住，我正在复习第三章").memoryType())
                .isEqualTo(MemoryTypeRules.TASK);
        assertThat(recognizer.recognize("记住，系统下午两点开门").memoryType())
                .isEqualTo(MemoryTypeRules.FACT);
    }

    private void assertRecognized(String raw, String expectedBody) {
        ExplicitMemoryRecognizer.ExplicitMemory memory = recognizer.recognize(raw);
        assertThat(memory).isNotNull();
        assertThat(memory.content()).isEqualTo(expectedBody);
    }
}
