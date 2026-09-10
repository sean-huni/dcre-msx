package za.co.fnb.dcre.msx.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

/**
 * One pain.012 SBSR acceptance-leg verdict, the single aggregate MSX writes.
 *
 * <p>{@link #TABLE} is the ONE literal naming this service's response table
 * (SCRUM-91 review R1): {@code @Table} above, the guarded insert on
 * {@code ManRespRepo}, and {@code ReaderService.TARGET_TABLE} all read it, so
 * there is no second literal an implementer can update independently. A native
 * {@code @Query} needs a compile-time constant, which a {@code static final}
 * String literal is, so the annotation concatenates this constant rather than
 * formatting a template at runtime.
 *
 * <p>id is assigned client-side here rather than by {@code JdbcConfig}'s
 * BeforeConvertCallback, because the guarded insert is a native query and never
 * passes through the Data JDBC conversion callbacks (same shape as mrw's
 * write-ahead entity). version/created_at/updated_at fill from the column
 * defaults for the same reason. e2e is nullable: the current synthetic pain.012
 * contract (A-60) carries no EndToEndId.
 */
@Table(ManSbsrRespEntity.TABLE)
public class ManSbsrRespEntity extends BaseEntity {

    /** The ONE response table this service owns. MSX is the SBSR leg (mirror of collections CSX). */
    public static final String TABLE = "man_sbsr_resp";

    private String responseFile;
    private String orgnlMsgId;
    private String mndtId;
    private String mndtReqId;
    private String e2e;
    private String status;
    private String reason;

    public static ManSbsrRespEntity of(final String responseFile, final String orgnlMsgId, final String mndtId,
                                       final String mndtReqId, final String e2e, final String status,
                                       final String reason) {
        final ManSbsrRespEntity entity = new ManSbsrRespEntity();
        entity.assignIdIfMissing();
        entity.responseFile = responseFile;
        entity.orgnlMsgId = orgnlMsgId;
        entity.mndtId = mndtId;
        entity.mndtReqId = mndtReqId;
        entity.e2e = e2e;
        entity.status = status;
        entity.reason = reason;
        return entity;
    }

    public String getResponseFile() { return responseFile; }
    public String getOrgnlMsgId() { return orgnlMsgId; }
    public String getMndtId() { return mndtId; }
    public String getMndtReqId() { return mndtReqId; }
    public String getE2e() { return e2e; }
    public String getStatus() { return status; }
    public String getReason() { return reason; }
}
