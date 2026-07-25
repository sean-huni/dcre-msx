package za.co.fnb.dcre.msx.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import za.co.fnb.dcre.msx.AbstractCrdbIT;
import za.co.fnb.dcre.msx.ManOutboundSourceTable;
import za.co.fnb.dcre.msx.ManReplyFixture;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * MSX reader proofs against a real CRDB (T12). A pain.012 SBSR acceptance leg is
 * parsed, correlated fail-closed to the MRW outbound registry, and written to the
 * ONE response table this service owns:
 * <ul>
 *   <li>the ingest lands in man_sbsr_resp, the table fixed at compile time, so there
 *       is no other table a launch arg could steer it to (SCRUM-91);</li>
 *   <li>correlation resolves a known man_outbound.out_msg_id and ingests the reply;</li>
 *   <li>an UNKNOWN outbound identity is WARNed and EXCLUDED (fail-closed, SCRUM-60,
 *       no family fallback, nothing ingested);</li>
 *   <li>a re-ingest of the same file is a zero-duplicate no-op (resume audit).</li>
 * </ul>
 * MRW owns man_outbound, so the suite bootstraps a lookalike via plain JDBC.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false"})
class MsxReaderIT {


    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractCrdbIT.CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", AbstractCrdbIT.CRDB::getUsername);
        registry.add("spring.datasource.password", AbstractCrdbIT.CRDB::getPassword);
    }

    @Autowired
    ReaderService service;

    @Autowired
    JdbcTemplate jdbc;

    ListAppender<ILoggingEvent> logEvents;

    @BeforeEach
    void setUp() {
        ManOutboundSourceTable.bootstrap(jdbc);
        logEvents = new ListAppender<>();
        logEvents.start();
        readerLogger().addAppender(logEvents);
    }

    @AfterEach
    void tearDown() {
        readerLogger().detachAppender(logEvents);
    }

    @Test
    void theSbsrLegLandsInTheOneTableThisServiceOwns() {
        ManOutboundSourceTable.seed(jdbc, "OUT-I", "MREQ-I", "PAIN009");
        final String file = "fnbcc01_OUT-I_SBSR.xml";
        final String text = ManReplyFixture.leg("SBSR", "OUT-I", "MREQ-I", "MND-I", "ACCP", null);

        final int rows = service.ingest(text, file);

        assertEquals(1, rows, "the correlated reply is ingested");
        assertEquals("man_sbsr_resp", ReaderService.TARGET_TABLE, "MSX owns the SBSR leg and nothing else");
        assertEquals(1, count(ReaderService.TARGET_TABLE, file), "the reply lands in man_sbsr_resp");
    }

    @Test
    void correlationResolvesKnownOutboundAndPersistsAllReplyFields() {
        ManOutboundSourceTable.seed(jdbc, "OUT-K", "MREQ-K", "PAIN009");
        final String file = "fnbcc01_OUT-K_SBSR.xml";
        final String text = ManReplyFixture.leg("SBSR", "OUT-K", "MREQ-K", "MND-K", "RJCT", "AC04");

        assertEquals(1, service.ingest(text, file));

        assertEquals("OUT-K", one("SELECT orgnl_msg_id FROM man_sbsr_resp WHERE response_file=?", file));
        assertEquals("MND-K", one("SELECT mndt_id FROM man_sbsr_resp WHERE response_file=?", file));
        assertEquals("MREQ-K", one("SELECT mndt_req_id FROM man_sbsr_resp WHERE response_file=?", file));
        assertEquals("RJCT", one("SELECT status FROM man_sbsr_resp WHERE response_file=?", file));
        assertEquals("AC04", one("SELECT reason FROM man_sbsr_resp WHERE response_file=?", file));
        assertNull(jdbc.queryForObject("SELECT e2e FROM man_sbsr_resp WHERE response_file=?", String.class, file),
                "synthetic pain.012 carries no EndToEndId: e2e persists NULL");
    }

    @Test
    void unknownOutboundIsExcludedFailClosed() {
        // No man_outbound row seeded: the OrgnlMsgId does not resolve.
        final String file = "fnbcc01_OUT-GHOST_SBSR.xml";
        final String text = ManReplyFixture.leg("SBSR", "OUT-GHOST", "MREQ-GHOST", "MND-GHOST", "ACCP", null);

        final int rows = service.ingest(text, file);

        assertEquals(0, rows, "fail-closed: an unknown outbound identity ingests nothing");
        assertEquals(0, count("man_sbsr_resp", file), "no row may land for an unresolved outbound (no family fallback)");
        assertEquals(1, warns("reason=UNKNOWN_OUTBOUND_MSG", "OUT-GHOST").size(),
                "exactly one exclusion WARN for the unknown outbound");
    }

    @Test
    void reIngestOfSameFileIsZeroDuplicate() {
        ManOutboundSourceTable.seed(jdbc, "OUT-R", "MREQ-R", "PAIN009");
        final String file = "fnbcc01_OUT-R_SBSR.xml";
        final String text = ManReplyFixture.leg("SBSR", "OUT-R", "MREQ-R", "MND-R", "PDNG", null);

        assertEquals(1, service.ingest(text, file));
        final UUID firstId = jdbc.queryForObject(
                "SELECT id FROM man_sbsr_resp WHERE response_file=?", UUID.class, file);

        final int replay = service.ingest(text, file);

        assertEquals(0, replay, "replay is a no-op via ON CONFLICT (response_file, mndt_req_id) DO NOTHING");
        assertEquals(1, count("man_sbsr_resp", file), "no duplicate row on resume");
        assertEquals(firstId, jdbc.queryForObject(
                "SELECT id FROM man_sbsr_resp WHERE response_file=?", UUID.class, file),
                "the committed row keeps its identity");
    }

    private int count(final String table, final String responseFile) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE response_file=?",
                Integer.class, responseFile);
    }

    private String one(final String sql, final String responseFile) {
        return jdbc.queryForObject(sql, String.class, responseFile);
    }

    private List<ILoggingEvent> warns(final String... needles) {
        return logEvents.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> {
                    final String message = event.getFormattedMessage();
                    for (final String needle : needles) {
                        if (!message.contains(needle)) {
                            return false;
                        }
                    }
                    return true;
                })
                .toList();
    }

    private static Logger readerLogger() {
        return (Logger) LoggerFactory.getLogger(ReaderService.class);
    }
}
