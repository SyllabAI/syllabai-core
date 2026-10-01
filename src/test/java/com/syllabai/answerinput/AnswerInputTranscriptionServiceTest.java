package com.syllabai.answerinput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.infrastructure.llm.FailoverLlmChain;
import com.syllabai.infrastructure.llm.FakeLlmProvider;
import com.syllabai.infrastructure.llm.LlmFailureClass;
import com.syllabai.infrastructure.llm.LlmMedia;
import com.syllabai.infrastructure.llm.LlmProviderException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Transcription service tests (HUB-ANSWER-BOX wave 3): validation happens BEFORE
 * any model call (cost guard), blank/[empty] model output is a structured 422
 * (NothingReadableException), chain failure is a structured 503, and success
 * carries provider/model provenance. The vision-capable fake doubles as proof the
 * request actually carries media — the text-only fake can never serve it.
 */
class AnswerInputTranscriptionServiceTest {

    private static final String TINY_PNG_BASE64 =
            Base64.getEncoder().encodeToString(new byte[] {(byte) 0x89, 'P', 'N', 'G'});

    private static AnswerInputTranscriptionService serviceWith(FakeLlmProvider visionMember) {
        return new AnswerInputTranscriptionService(
                new FailoverLlmChain(List.of(visionMember), id -> Optional.empty()));
    }

    @Test
    @DisplayName("success returns the transcribed text with provider provenance")
    void success() {
        FakeLlmProvider gemini = FakeLlmProvider.visionCapable("gemini")
                .respondsWith("  x² + 2x = 0  ");
        AnswerInputTranscriptionService service = serviceWith(gemini);

        AnswerInputTranscriptionService.Transcription result =
                service.transcribe(TINY_PNG_BASE64, "image/png");

        assertThat(result.text()).isEqualTo("x² + 2x = 0");
        assertThat(result.providerName()).isEqualTo("gemini");
        // the request the provider saw must carry the image
        LlmMedia seen = gemini.lastRequest().media();
        assertThat(seen).isNotNull();
        assertThat(seen.mimeType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("missing image or bad mime is rejected before any provider call")
    void validationPrecedesModelCall() {
        FakeLlmProvider gemini = FakeLlmProvider.visionCapable("gemini");
        AnswerInputTranscriptionService service = serviceWith(gemini);

        assertThatThrownBy(() -> service.transcribe(null, "image/png"))
                .isInstanceOf(AnswerInputTranscriptionService.BadRequestException.class);
        assertThatThrownBy(() -> service.transcribe("   ", "image/png"))
                .isInstanceOf(AnswerInputTranscriptionService.BadRequestException.class);
        assertThatThrownBy(() -> service.transcribe(TINY_PNG_BASE64, "application/pdf"))
                .isInstanceOf(AnswerInputTranscriptionService.BadRequestException.class);
        assertThatThrownBy(() -> service.transcribe(TINY_PNG_BASE64, null))
                .isInstanceOf(AnswerInputTranscriptionService.BadRequestException.class);
        assertThat(gemini.callCount()).as("no provider call may be paid for invalid input")
                .isZero();
    }

    @Test
    @DisplayName("invalid base64 is a 400-class rejection, not a provider failure")
    void invalidBase64() {
        AnswerInputTranscriptionService service = serviceWith(FakeLlmProvider.visionCapable("gemini"));

        assertThatThrownBy(() -> service.transcribe("!!!not-base64!!!", "image/png"))
                .isInstanceOf(AnswerInputTranscriptionService.BadRequestException.class);
    }

    @Test
    @DisplayName("oversized image is a 413-class rejection before the model call")
    void oversizedImage() {
        FakeLlmProvider gemini = FakeLlmProvider.visionCapable("gemini");
        AnswerInputTranscriptionService service = serviceWith(gemini);
        byte[] big = new byte[AnswerInputTranscriptionService.MAX_DECODED_BYTES + 1];
        String bigBase64 = Base64.getEncoder().encodeToString(big);

        assertThatThrownBy(() -> service.transcribe(bigBase64, "image/png"))
                .isInstanceOf(AnswerInputTranscriptionService.ImageTooLargeException.class);
        assertThat(gemini.callCount()).isZero();
    }

    @Test
    @DisplayName("blank model output maps to NothingReadableException (422 class)")
    void blankOutputIsNothingReadable() {
        AnswerInputTranscriptionService service = serviceWith(
                FakeLlmProvider.visionCapable("gemini").respondsWith("   "));

        assertThatThrownBy(() -> service.transcribe(TINY_PNG_BASE64, "image/png"))
                .isInstanceOf(AnswerInputTranscriptionService.NothingReadableException.class);
    }

    @Test
    @DisplayName("[empty] sentinel output maps to NothingReadableException (422 class)")
    void emptySentinelIsNothingReadable() {
        AnswerInputTranscriptionService service = serviceWith(
                FakeLlmProvider.visionCapable("gemini").respondsWith("[EMPTY]"));

        assertThatThrownBy(() -> service.transcribe(TINY_PNG_BASE64, "image/png"))
                .isInstanceOf(AnswerInputTranscriptionService.NothingReadableException.class);
    }

    @Test
    @DisplayName("chain exhaustion maps to TranscriptionUnavailableException (503 class)")
    void chainFailureIsUnavailable() {
        AnswerInputTranscriptionService service = serviceWith(
                FakeLlmProvider.visionCapable("gemini").alwaysFails(LlmFailureClass.RATE_LIMITED));

        assertThatThrownBy(() -> service.transcribe(TINY_PNG_BASE64, "image/png"))
                .isInstanceOf(AnswerInputTranscriptionService.TranscriptionUnavailableException.class);
    }

    @Test
    @DisplayName("a text-only chain can never serve transcription — the failure is structured")
    void textOnlyChainFailsStructured() {
        AnswerInputTranscriptionService service = new AnswerInputTranscriptionService(
                new FailoverLlmChain(List.of(FakeLlmProvider.named("groq")),
                        id -> Optional.empty()));

        assertThatThrownBy(() -> service.transcribe(TINY_PNG_BASE64, "image/png"))
                .isInstanceOf(AnswerInputTranscriptionService.TranscriptionUnavailableException.class);
    }
}
