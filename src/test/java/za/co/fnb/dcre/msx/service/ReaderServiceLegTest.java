package za.co.fnb.dcre.msx.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-91: MSX is the SBSR leg reader. The leg is a compile-time property of the
 * service, NOT a reply.type launch arg (that arg was MAR's merged-reader shape and
 * is the deviation this refactor removes).
 */
class ReaderServiceLegTest {

    @Test
    void targetTableIsTheSbsrLegAndNothingElse() {
        assertThat(ReaderService.TARGET_TABLE).isEqualTo("man_sbsr_resp");
    }
}
