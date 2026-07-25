package za.co.fnb.dcre.msx.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.fnb.dcre.msx.data.repo.ManOutboundLookupDao;
import za.co.fnb.dcre.msx.data.repo.ManRespDao;
import za.co.fnb.dcre.msx.domain.ManReply;
import za.co.fnb.dcre.msx.domain.MandateReplyParser;

import java.util.Optional;
import java.util.UUID;

/**
 * Business tier: parses one pain.012 SBSR acceptance leg, correlates it fail-closed
 * to the MRW outbound registry, and writes it to the ONE response table this
 * service owns.
 *
 * <p>SCRUM-91: the leg is a compile-time property of the service, NOT a
 * {@code reply.type} launch arg. MSX is the SBSR leg reader, the exact mirror of
 * collections SXR; MIX and MPX are its ISR and PBSR twins. That triplication IS
 * the fleet pattern (ixr/sxr/pxr); the merged three-table reader was MAR's shape
 * and is the deviation this refactor removes.
 *
 * <p>[SYNTHETIC-CONTRACT R-35/A-60] A pain.012 message carries exactly ONE
 * mandate, so the commit unit is the single reply row (not the collections ISR
 * per-Tx fan-out). The write still runs in its OWN REQUIRES_NEW transaction under
 * CrdbRetry, giving the same resume-zero-dup guarantee as the fleet slice-commit
 * pattern: a 40001 abort retries in a fresh tx, and a re-run no-ops over the
 * committed identity via ON CONFLICT (response_file, mndt_req_id) DO NOTHING.
 *
 * <p>CORRELATION is fail-closed (SCRUM-60 canon): the reply's OrgnlMsgId must
 * resolve to a known man_outbound.out_msg_id or the reply is WARNed and EXCLUDED
 * (never ingested, never a guessed family fallback). A known outbound identity is
 * the only evidence the route is real.
 */
@Service
public class ReaderService {

    /** The ONE response table this service owns. MSX is the SBSR leg (mirror of collections SXR). */
    public static final String TARGET_TABLE = "man_sbsr_resp";

    private static final Logger log = LoggerFactory.getLogger(ReaderService.class);

    private final ManRespDao respDao;
    private final ManOutboundLookupDao outboundLookup;
    private final TransactionTemplate sliceTx;

    public ReaderService(final ManRespDao respDao, final ManOutboundLookupDao outboundLookup,
                         final PlatformTransactionManager txManager) {
        this.respDao = respDao;
        this.outboundLookup = outboundLookup;
        // Own REQUIRES_NEW transaction per write: a CRDB 40001 abort poisons the
        // surrounding transaction (25P02 on any further statement), so each retry
        // needs a fresh transaction (same shape as the ixr sliced ingest).
        this.sliceTx = new TransactionTemplate(txManager);
        this.sliceTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @return 1 when the reply is ingested (or already stood, on replay via ON
     * CONFLICT), 0 when it is excluded fail-closed on an unknown outbound identity.
     */
    public int ingest(final String fileText, final String responseFile) {
        final ManReply reply = MandateReplyParser.parse(fileText, responseFile);
        final Optional<UUID> outbound = outboundLookup.findByOutMsgId(reply.orgnlMsgId());
        if (outbound.isEmpty()) {
            log.warn("excluded stage=MSX mndtReqId={} reason=UNKNOWN_OUTBOUND_MSG orgnlMsgId={} file={}",
                    reply.mndtReqId(), reply.orgnlMsgId(), responseFile);
            return 0;
        }
        return writeSlice(responseFile, reply);
    }

    /** One reply row = one committed unit: fresh REQUIRES_NEW tx per bounded-retry attempt. */
    private int writeSlice(final String responseFile, final ManReply reply) {
        return CrdbRetry.run("ingest leg=SBSR file=%s".formatted(responseFile),
                () -> sliceTx.execute(status -> respDao.insertGuarded(responseFile, reply)));
    }
}
