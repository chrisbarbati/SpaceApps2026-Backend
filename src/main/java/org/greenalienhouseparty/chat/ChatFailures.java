package org.greenalienhouseparty.chat;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import org.greenalienhouseparty.dto.ChatEvent;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;

/**
 * Decides what a failure while talking to an AI model means: whether another model may be tried, and what to
 * tell the farmer.
 *
 * <p>A fallback model is only worth trying for failures of the service rather than of the request: timeouts,
 * lost connections, rate limits (HTTP 429), conflicts and timeouts reported by the service (408, 409) and
 * server errors (5xx). A rejected request (other 4xx, including bad credentials) would fail the same way on
 * any model, so it is reported instead. Messages to the farmer never include the provider's raw error, which
 * can contain request details.</p>
 */
public final class ChatFailures {

    private ChatFailures() {
    }

    /**
     * Says whether a failure is temporary, so another model may succeed, and why.
     *
     * @param error the failure
     * @return a short reason when another model may be tried; empty when it should not
     */
    public static Optional<String> retryableReason(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof OpenAIServiceException service) {
                int status = service.statusCode();
                if (status == 429) {
                    return Optional.of("rate limited");
                }
                if (status == 408 || status == 409 || status >= 500) {
                    return Optional.of("model service error (HTTP " + status + ")");
                }
                return Optional.empty();
            }
            if (cause instanceof TimeoutException) {
                return Optional.of("timed out");
            }
            if (cause instanceof OpenAIIoException || cause instanceof OpenAIRetryableException || cause instanceof IOException) {
                return Optional.of("could not reach the model");
            }
        }
        return Optional.empty();
    }

    /**
     * Turns a failure into the error event sent to the farmer.
     *
     * @param error the failure
     * @return the error event
     */
    public static ChatEvent.Error toEvent(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof OpenAIServiceException service) {
                int status = service.statusCode();
                if (status == 401 || status == 403) {
                    return new ChatEvent.Error("model_auth", "The assistant's AI model rejected the app's "
                            + "credentials, so it cannot answer right now. Please let the app's administrator know.");
                }
                if (status == 429) {
                    return new ChatEvent.Error("model_busy", "The assistant is busy right now. Please try again "
                            + "in a minute.");
                }
                if (status >= 500 || status == 408) {
                    return new ChatEvent.Error("model_unavailable", "The assistant's AI model is not responding "
                            + "right now. Please try again shortly.");
                }
                return new ChatEvent.Error("model_error", "The assistant's AI model could not handle this request "
                        + "(HTTP " + status + "). Try rephrasing or starting a new conversation.");
            }
            if (cause instanceof TimeoutException || cause instanceof OpenAIIoException
                    || cause instanceof OpenAIRetryableException || cause instanceof IOException) {
                return new ChatEvent.Error("model_unavailable", "The assistant's AI model could not be reached. "
                        + "Please try again shortly.");
            }
        }
        return new ChatEvent.Error("internal_error", "Something went wrong while answering. Please try again.");
    }
}
