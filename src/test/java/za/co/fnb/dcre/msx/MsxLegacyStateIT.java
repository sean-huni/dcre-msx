package za.co.fnb.dcre.msx;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-107 v1 convergence guard: man_sbsr_resp has TWO creators on a brand-new dcre_man, this
 * service and MRG's {@code 004-bootstrap-man-sbsr-resp-mrg}, and nothing serializes the ten
 * M-service migrations. Whichever writer loses the race must MARK_RAN, never re-execute DDL.
 * Four fixtures: fresh DB, MRG's pre-create already standing, the table standing without its
 * unique constraint, double-apply.
 */
class MsxLegacyStateIT extends AbstractCrdbIT {

    /** What MRG's 004-bootstrap-man-sbsr-resp-mrg leaves behind: table plus unique constraint. */
    private static final String MRG_PRECREATED_SBSR_TABLE = """
            CREATE TABLE IF NOT EXISTS man_sbsr_resp (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              response_file VARCHAR(128) NOT NULL, orgnl_msg_id VARCHAR(35) NOT NULL,
              mndt_id VARCHAR(35) NOT NULL, mndt_req_id VARCHAR(35) NOT NULL,
              e2e VARCHAR(35), status VARCHAR(8) NOT NULL, reason VARCHAR(8),
              version BIGINT NOT NULL DEFAULT 0,
              created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
              updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
              CONSTRAINT uq_man_sbsr_resp_file_mndt_req UNIQUE (response_file, mndt_req_id))""";

    /**
     * The table standing WITHOUT its unique constraint (SCRUM-91 review R5). Two v1 paths
     * reach it: MSX won the table create and was killed before its own unique-constraint
     * changeset committed, or MSX created the table and MRG's combined changeset then
     * MARK_RANed on tableExists so the constraint never landed from MRG either. One
     * precondition guarding both statements would MARK_RAN the whole changeset here, leaving
     * the runtime ON CONFLICT (response_file, mndt_req_id) with no constraint to arbitrate on,
     * so the idempotency guarantee is silently gone. The constraint gets its own changeset
     * guarded on the schema state IT transforms.
     */
    private static final String SBSR_TABLE_WITHOUT_CONSTRAINT = """
            CREATE TABLE IF NOT EXISTS man_sbsr_resp (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              response_file VARCHAR(128) NOT NULL, orgnl_msg_id VARCHAR(35) NOT NULL,
              mndt_id VARCHAR(35) NOT NULL, mndt_req_id VARCHAR(35) NOT NULL,
              e2e VARCHAR(35), status VARCHAR(8) NOT NULL, reason VARCHAR(8),
              version BIGINT NOT NULL DEFAULT 0,
              created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
              updated_at TIMESTAMPTZ NOT NULL DEFAULT now())""";

    /** The runtime guarded write, byte-identical in shape to ManRespRepo.insertGuarded. */
    private static final String GUARDED_INSERT = """
            INSERT INTO man_sbsr_resp (id, response_file, orgnl_msg_id, mndt_id, mndt_req_id, status)
            VALUES (gen_random_uuid(), 'F1', 'OUT-1', 'MND-1', 'MREQ-1', 'ACCP')
            ON CONFLICT (response_file, mndt_req_id) DO NOTHING""";

    @Test
    void aTableWithoutItsConstraintGainsItAndOnConflictStillArbitrates() throws Exception {
        jdbc.execute(SBSR_TABLE_WITHOUT_CONSTRAINT);

        runLiquibase();
        runLiquibase();

        assertThat(jdbc.update(GUARDED_INSERT)).isOne();
        assertThat(jdbc.update(GUARDED_INSERT))
                .as("ON CONFLICT (response_file, mndt_req_id) needs the constraint to arbitrate on")
                .isZero();

        assertThat(execTypeOf("msx-001-man-sbsr-resp")).isEqualTo("MARK_RAN");
        assertThat(execTypeOf("msx-001-man-sbsr-resp-uq"))
                .as("the constraint is guarded on its OWN schema state, so it still executes")
                .isEqualTo("EXECUTED");
        assertThat(countIndexesOn("man_sbsr_resp")).isEqualTo(2);
    }

    @Test
    void mrgPreCreatedSbsrTableMarksTheChangesetRan() throws Exception {
        jdbc.execute(MRG_PRECREATED_SBSR_TABLE);

        runLiquibase();
        runLiquibase();

        assertThat(execTypeOf("msx-001-man-sbsr-resp")).isEqualTo("MARK_RAN");
        assertThat(execTypeOf("msx-001-man-sbsr-resp-uq"))
                .as("the constraint already stands, so its own guard converges too")
                .isEqualTo("MARK_RAN");
        assertThat(countIndexesOn("man_sbsr_resp")).isEqualTo(2);
    }

    @Test
    void aFreshDatabaseExecutesTheChangesetAndDoubleApplyIsANoOp() throws Exception {
        runLiquibase();
        runLiquibase();

        assertThat(execTypeOf("msx-001-man-sbsr-resp")).isEqualTo("EXECUTED");
        assertThat(execTypeOf("msx-001-man-sbsr-resp-uq")).isEqualTo("EXECUTED");
        assertThat(countIndexesOn("man_sbsr_resp")).isEqualTo(2);
    }

    @Test
    void msxShipsNeitherTheIsrNorThePbsrTable() throws Exception {
        runLiquibase();

        assertThat(countIndexesOn("man_isr_resp")).isZero();
        assertThat(countIndexesOn("man_pbsr_resp")).isZero();
    }
}
