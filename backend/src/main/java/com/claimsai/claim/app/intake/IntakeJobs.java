package com.claimsai.claim.app.intake;

import com.claimsai.claim.domain.Claim;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.claim.infra.ClaimRepository;
import com.claimsai.platform.jobs.app.JobContext;
import com.claimsai.platform.jobs.app.JobHandler;
import com.claimsai.platform.jobs.app.PermanentJobFailure;
import com.claimsai.policy.domain.PolicyPort;
import com.claimsai.policy.domain.PolicySnapshot;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/** Job handlers for claim intake; the logic lives in {@link ClaimIntakeService}. */
public final class IntakeJobs {

    private IntakeJobs() {
    }

    /**
     * Calls the policy system OUTSIDE any transaction (in a real carrier it's a remote system: slow, may
     * fail), then applies the result in a short transaction. If the call fails, the job is retried with
     * backoff; the claim simply stays SUBMITTED meanwhile.
     */
    @Component
    public static class VerifyPolicy implements JobHandler {

        private final ClaimRepository claims;
        private final PolicyPort policies;
        private final ClaimIntakeService intake;
        private final TransactionTemplate transaction;

        public VerifyPolicy(ClaimRepository claims, PolicyPort policies, ClaimIntakeService intake,
                            PlatformTransactionManager transactionManager) {
            this.claims = claims;
            this.policies = policies;
            this.intake = intake;
            this.transaction = new TransactionTemplate(transactionManager);
        }

        @Override
        public String type() {
            return ClaimIntakeService.VERIFY_POLICY;
        }

        @Override
        public boolean transactional() {
            return false;
        }

        @Override
        public void handle(JobContext job) {
            Claim claim = transaction.execute(status -> claims.findById(job.claimId())
                    .orElseThrow(() -> new PermanentJobFailure("Claim " + job.claimId() + " does not exist")));
            if (claim.getStatus() != ClaimStatus.SUBMITTED) {
                return;   // verified by an earlier run of this job
            }
            Optional<PolicySnapshot> policy = policies.findPolicy(claim.getPolicyNumber());   // no transaction held
            transaction.executeWithoutResult(status -> intake.applyPolicyCheck(job.claimId(), policy));
        }
    }

    @Component
    public static class CompleteAssessment implements JobHandler {

        private final ClaimIntakeService intake;

        public CompleteAssessment(ClaimIntakeService intake) {
            this.intake = intake;
        }

        @Override
        public String type() {
            return ClaimIntakeService.COMPLETE_ASSESSMENT;
        }

        @Override
        public void handle(JobContext job) {
            intake.completeAssessment(job.claimId(), false);
        }
    }

    /** The timer: if the assessment hasn't finished in time, the claim moves on without it. */
    @Component
    public static class AssessmentTimeout implements JobHandler {

        private final ClaimIntakeService intake;

        public AssessmentTimeout(ClaimIntakeService intake) {
            this.intake = intake;
        }

        @Override
        public String type() {
            return ClaimIntakeService.ASSESSMENT_TIMEOUT;
        }

        @Override
        public void handle(JobContext job) {
            intake.completeAssessment(job.claimId(), true);
        }
    }
}
