package com.example.aimindmap.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.aimindmap.importpdf.PdfRawExtraction;
import com.example.aimindmap.importpdf.PdfTextExtractor;
import com.example.aimindmap.mqsupport.MqTopology;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * 阶段五-A PDF 集成测试（复用 4-4 Testcontainers 设施，无 Docker 自动跳过）：
 * a) PENDING→outbox 投递→MQ 消费→业务终态（正常/扫描件/损坏），终态同事务写幂等行；
 * b) 连续 5 个任务在单并发队列中严格按投递顺序处理（spy 注入短延迟并记录顺序）；
 * c) 消费者离线时消息在 broker 不丢，启动后顺序续跑。
 * broker 一律 Testcontainers，不用开发机上的 RabbitMQ。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import({MqIntegrationMocks.class, PdfImportMqIntegrationTest.SlowExtractorSpy.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PdfImportMqIntegrationTest extends AbstractMqIntegrationTest {

    private static final String PDF_QUEUE = MqTopology.queueName(TaskType.PDF_IMPORT);
    private static final String MARKER = "it888-%";

    /** 顺序测试注入的短延迟（毫秒）：若并发消费，顺序断言会立即失败。 */
    private static final long SPY_DELAY_MS = 300L;

    @TempDir
    static Path fixtureDir;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RabbitListenerEndpointRegistry registry;

    private Path normalPdf;
    private Path blankPdf;
    private Path corruptPdf;
    @BeforeAll
    static void prepareFixtures() throws Exception {
        writeNormalPdf(fixtureDir.resolve("it888-normal.pdf"));
        writeBlankPdf(fixtureDir.resolve("it888-blank.pdf"));
        Files.writeString(fixtureDir.resolve("it888-corrupt.pdf"),
                "%PDF-1.4\nthis is not a real pdf body at all, just garbage bytes",
                StandardCharsets.UTF_8);
        for (int i = 1; i <= 5; i++) {
            writeNormalPdf(fixtureDir.resolve("it888-order-" + i + ".pdf"));
        }
    }

    @BeforeEach
    void resetSpyAndUsers() {
        SlowExtractorSpy.START_ORDER.clear();
        normalPdf = fixtureDir.resolve("it888-normal.pdf");
        blankPdf = fixtureDir.resolve("it888-blank.pdf");
        corruptPdf = fixtureDir.resolve("it888-corrupt.pdf");
        insertUser(888501L);
        insertUser(888502L);
        insertUser(888503L);
    }

    @AfterEach
    void cleanupPdfRows() {
        // 子类 @AfterEach 先于基类执行：先删 PDF 任务相关行，基类随后删 888 段用户不撞外键
        jdbc.update("DELETE FROM outbox_events WHERE task_type = 'PDF_IMPORT' AND task_id IN ("
                + "SELECT id FROM pdf_import_jobs WHERE original_file_name LIKE ?)", MARKER);
        jdbc.update("DELETE FROM idempotent_consumptions WHERE message_id IN ("
                + "SELECT 'PDF_IMPORT:' || id FROM pdf_import_jobs WHERE original_file_name LIKE ?)", MARKER);
        jdbc.update("DELETE FROM pdf_import_jobs WHERE original_file_name LIKE ?", MARKER);
    }

    @Test
    void pendingOutboxReachesDraftReadyScannedAndFailedTerminals() {
        long normalJob = insertPendingJob(888501L, "it888-normal.pdf", normalPdf);
        long blankJob = insertPendingJob(888502L, "it888-blank.pdf", blankPdf);
        long corruptJob = insertPendingJob(888503L, "it888-corrupt.pdf", corruptPdf);
        insertOutbox(normalJob);
        insertOutbox(blankJob);
        insertOutbox(corruptJob);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jobStatus(normalJob)).isEqualTo("DRAFT_READY"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jobStatus(blankJob)).isEqualTo("SCANNED"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jobStatus(corruptJob)).isEqualTo("FAILED"));

        assertThat(jdbc.queryForObject(
                "SELECT error_code FROM pdf_import_jobs WHERE id = ?", String.class, corruptJob))
                .isEqualTo("PDF_PARSE_FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT markdown_draft IS NOT NULL FROM pdf_import_jobs WHERE id = ?",
                Boolean.class, normalJob)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT suspected_scanned FROM pdf_import_jobs WHERE id = ?",
                Boolean.class, blankJob)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT completed_at IS NOT NULL FROM pdf_import_jobs WHERE id = ?",
                Boolean.class, normalJob)).isTrue();

        // 终态与幂等记录均落库；outbox 已投递；业务失败不进 DLQ
        for (long jobId : new long[]{normalJob, blankJob, corruptJob}) {
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM idempotent_consumptions WHERE message_id = ?",
                    Integer.class, "PDF_IMPORT:" + jobId)).isEqualTo(1);
            assertThat(outboxStatus(jobId)).isEqualTo("SENT");
        }
        await().atMost(Duration.ofSeconds(5)).until(() ->
                MqTestBroker.messageCount(PDF_QUEUE) == 0);
        assertThat(MqTestBroker.messageCount(MqTopology.DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void fiveJobsAreProcessedStrictlyInDeliveryOrderWithSingleConcurrency() {
        List<Long> jobIds = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            long jobId = insertPendingJob(888502L, "it888-order-" + i + ".pdf",
                    fixtureDir.resolve("it888-order-" + i + ".pdf"));
            jobIds.add(jobId);
        }
        // 直接按 1→5 顺序投递（不依赖 relay 批量行为），队列必须保序
        for (Long jobId : jobIds) {
            publishMessage(jobId);
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            for (Long jobId : jobIds) {
                assertThat(jobStatus(jobId)).isEqualTo("DRAFT_READY");
            }
        });

        // 每个任务解析至少 SPY_DELAY_MS；若并发>1，完成顺序与开始顺序都会乱，这里断言开始顺序
        assertThat(SlowExtractorSpy.START_ORDER).containsExactly(
                "it888-order-1.pdf", "it888-order-2.pdf", "it888-order-3.pdf",
                "it888-order-4.pdf", "it888-order-5.pdf");
        assertThat(MqTestBroker.messageCount(PDF_QUEUE)).isZero();
    }

    @Test
    void messagesSurviveConsumerOfflineAndDrainInOrderAfterRestart() throws Exception {
        long firstJob = insertPendingJob(888503L, "it888-order-1.pdf",
                fixtureDir.resolve("it888-order-1.pdf"));
        long secondJob = insertPendingJob(888503L, "it888-order-2.pdf",
                fixtureDir.resolve("it888-order-2.pdf"));

        registry.stop();
        try {
            publishMessage(firstJob);
            publishMessage(secondJob);

            // 无消费者：消息持久留在队列，任务保持 PENDING
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    MqTestBroker.messageCount(PDF_QUEUE) == 2);
            Thread.sleep(Duration.ofSeconds(1).toMillis());
            assertThat(jobStatus(firstJob)).isEqualTo("PENDING");
            assertThat(jobStatus(secondJob)).isEqualTo("PENDING");
            assertThat(SlowExtractorSpy.START_ORDER).isEmpty();

            registry.start();

            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(jobStatus(firstJob)).isEqualTo("DRAFT_READY");
                assertThat(jobStatus(secondJob)).isEqualTo("DRAFT_READY");
            });
            assertThat(SlowExtractorSpy.START_ORDER).containsExactly(
                    "it888-order-1.pdf", "it888-order-2.pdf");
            await().atMost(Duration.ofSeconds(5)).until(() ->
                    MqTestBroker.messageCount(PDF_QUEUE) == 0);
        } finally {
            if (!registry.isRunning()) {
                registry.start();
            }
        }
    }

    // ---------- helpers ----------

    private void insertUser(long id) {
        jdbc.update("INSERT INTO users(id, username, password_hash, major) VALUES (?, ?, 'x', '计算机')",
                id, "it" + id);
    }

    private long insertPendingJob(long userId, String fileName, Path storedFile) {
        Long id = jdbc.queryForObject(
                "INSERT INTO pdf_import_jobs(user_id, document_id, original_file_name, "
                        + "stored_file_path, status, suspected_scanned) "
                        + "VALUES (?, NULL, ?, ?, 'PENDING', FALSE) RETURNING id",
                Long.class, userId, fileName, storedFile.toAbsolutePath().toString());
        if (id == null) {
            throw new IllegalStateException("failed to insert pdf job " + fileName);
        }
        return id;
    }

    private void insertOutbox(long jobId) {
        try {
            String payload = objectMapper.writeValueAsString(new TaskMessage(
                    TaskType.PDF_IMPORT, jobId, null, "IMPORT", Instant.now()));
            jdbc.update("INSERT INTO outbox_events(task_type, task_id, payload, status, attempts) "
                    + "VALUES ('PDF_IMPORT', ?, ?, 'PENDING', 0)", jobId, payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void publishMessage(long jobId) {
        try {
            TaskMessage taskMessage = new TaskMessage(
                    TaskType.PDF_IMPORT, jobId, null, "IMPORT", Instant.now());
            MessageProperties properties = new MessageProperties();
            properties.setMessageId("PDF_IMPORT:" + jobId);
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setContentEncoding(StandardCharsets.UTF_8.name());
            Message message = new Message(objectMapper.writeValueAsBytes(taskMessage), properties);
            MqTestBroker.driver().send(
                    MqTopology.TASK_EXCHANGE, MqTopology.routingKey(TaskType.PDF_IMPORT), message);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String jobStatus(long jobId) {
        return jdbc.queryForObject(
                "SELECT status FROM pdf_import_jobs WHERE id = ?", String.class, jobId);
    }

    private String outboxStatus(long jobId) {
        return jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE task_type = 'PDF_IMPORT' AND task_id = ?",
                String.class, jobId);
    }

    private static Path writeNormalPdf(Path target) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
                cs.beginText();
                cs.setFont(font, 18);
                cs.newLineAtOffset(80, 720);
                cs.showText("Integration Test Chapter Heading");
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(0, -30);
                cs.showText("This normal text PDF contains enough characters on the page to pass");
                cs.newLineAtOffset(0, -20);
                cs.showText("the scanned detection threshold and produce a readable markdown draft.");
                cs.endText();
            }
            document.save(target.toFile());
        }
        return target;
    }

    private static Path writeBlankPdf(Path target) throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(target.toFile());
        }
        return target;
    }

    /** 测试替身：真实提取外包给 PdfTextExtractor，另注入短延迟并按文件名记录开始顺序。 */
    @TestConfiguration
    static class SlowExtractorSpy {

        static final List<String> START_ORDER = Collections.synchronizedList(new ArrayList<>());

        @Bean
        @Primary
        PdfTextExtractor slowPdfTextExtractor() {
            PdfTextExtractor delegate = new PdfTextExtractor();
            return new PdfTextExtractor() {
                @Override
                public PdfRawExtraction extract(Path file, int maxPages) {
                    START_ORDER.add(file.getFileName().toString());
                    try {
                        Thread.sleep(SPY_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return delegate.extract(file, maxPages);
                }
            };
        }
    }
}
