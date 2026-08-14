package com.devicelk.inventory;

import com.devicelk.inventory.api.IngestionJobResponse;
import com.devicelk.inventory.config.DocumentProperties;
import com.devicelk.inventory.exception.SyncInProgressException;
import com.devicelk.inventory.repository.ProductRepository;
import com.devicelk.inventory.service.ProductDocumentService;
import com.devicelk.inventory.service.ProductDocumentServiceFactory;
import com.devicelk.inventory.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.bedrockagent.BedrockAgentClient;
import software.amazon.awssdk.services.bedrockagent.model.ConflictException;
import software.amazon.awssdk.services.bedrockagent.model.GetIngestionJobRequest;
import software.amazon.awssdk.services.bedrockagent.model.GetIngestionJobResponse;
import software.amazon.awssdk.services.bedrockagent.model.IngestionJob;
import software.amazon.awssdk.services.bedrockagent.model.StartIngestionJobRequest;
import software.amazon.awssdk.services.bedrockagent.model.StartIngestionJobResponse;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Knowledge-base ingestion. Bedrock is mocked throughout — no job is ever
 * actually started against the real knowledge base.
 */
class ProductDocumentSyncTest {

    private BedrockAgentClient bedrock;
    private ProductDocumentService service;

    @BeforeEach
    void setUp() {
        bedrock = mock(BedrockAgentClient.class);
        service = ProductDocumentServiceFactory.create(
                mock(S3Client.class), bedrock, mock(ProductRepository.class),
                mock(ProductService.class),
                new DocumentProperties("test-bucket", "KB1", "DS1", "us-east-1"));
    }

    @Test
    void startsAJobAgainstTheConfiguredKnowledgeBase() {
        when(bedrock.startIngestionJob(any(StartIngestionJobRequest.class)))
                .thenReturn(StartIngestionJobResponse.builder()
                        .ingestionJob(IngestionJob.builder()
                                .ingestionJobId("JOB1").status("STARTING").build())
                        .build());

        IngestionJobResponse result = service.startSync();

        ArgumentCaptor<StartIngestionJobRequest> req =
                ArgumentCaptor.forClass(StartIngestionJobRequest.class);
        verify(bedrock).startIngestionJob(req.capture());
        assertThat(req.getValue().knowledgeBaseId()).isEqualTo("KB1");
        assertThat(req.getValue().dataSourceId()).isEqualTo("DS1");
        assertThat(result.ingestionJobId()).isEqualTo("JOB1");
        assertThat(result.status()).isEqualTo("STARTING");
    }

    /**
     * Bedrock allows one job at a time per data source. A second start is an
     * expected outcome the admin should simply be told about, not a failure.
     */
    @Test
    void aJobAlreadyRunningIsReportedAsSuchNotAsAFailure() {
        when(bedrock.startIngestionJob(any(StartIngestionJobRequest.class)))
                .thenThrow(ConflictException.builder().message("already running").build());

        assertThatThrownBy(() -> service.startSync())
                .isInstanceOf(SyncInProgressException.class);
    }

    @Test
    void readsBackTheStatusOfAJob() {
        when(bedrock.getIngestionJob(any(GetIngestionJobRequest.class)))
                .thenReturn(GetIngestionJobResponse.builder()
                        .ingestionJob(IngestionJob.builder()
                                .ingestionJobId("JOB1").status("COMPLETE").build())
                        .build());

        IngestionJobResponse result = service.getSyncStatus("JOB1");

        ArgumentCaptor<GetIngestionJobRequest> req =
                ArgumentCaptor.forClass(GetIngestionJobRequest.class);
        verify(bedrock).getIngestionJob(req.capture());
        assertThat(req.getValue().ingestionJobId()).isEqualTo("JOB1");
        assertThat(req.getValue().knowledgeBaseId()).isEqualTo("KB1");
        assertThat(result.status()).isEqualTo("COMPLETE");
    }
}
