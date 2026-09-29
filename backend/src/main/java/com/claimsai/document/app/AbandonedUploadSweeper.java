package com.claimsai.document.app;

import com.claimsai.document.domain.Document;
import com.claimsai.document.domain.DocumentStorage;
import com.claimsai.document.infra.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * An upload URL was issued but "complete" never came (tab closed, network lost). After a day the row and
 * any object behind it are deleted, so storage doesn't fill with files nobody verified.
 */
@Component
public class AbandonedUploadSweeper {

    private static final Logger log = LoggerFactory.getLogger(AbandonedUploadSweeper.class);

    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration maxAge;

    public AbandonedUploadSweeper(DocumentRepository documents, DocumentStorage storage,
                                  PlatformTransactionManager transactionManager, Clock clock,
                                  @Value("${app.documents.abandoned-after:24h}") Duration maxAge) {
        this.documents = documents;
        this.storage = storage;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.maxAge = maxAge;
    }

    @Scheduled(cron = "${app.documents.sweep-cron:0 23 * * * *}")
    public int sweep() {
        List<Document> abandoned = documents.findTop100ByStatusAndCreatedAtBefore(Document.Status.PENDING_UPLOAD,
                clock.instant().minus(maxAge));
        for (Document document : abandoned) {
            storage.delete(document.getStorageKey());   // object first: a leftover row is retried next time
            transaction.executeWithoutResult(status -> documents.findById(document.getId())
                    .filter(Document::isPending)
                    .ifPresent(documents::delete));
        }
        if (!abandoned.isEmpty()) {
            log.info("Removed {} abandoned uploads", abandoned.size());
        }
        return abandoned.size();
    }
}
