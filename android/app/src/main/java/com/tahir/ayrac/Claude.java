package com.tahir.ayrac;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.stream.Collectors;

/** Optional Claude requests with the user's Anthropic API key (same behaviour as ayrac_app.Api.ask). */
final class Claude {
    static final String OPUS = "claude-opus-5-5", HAIKU = "claude-haiku-5-5";

    private AnthropicClient client;
    private String clientKey;

    private synchronized AnthropicClient client(String key) {
        if (client == null || !key.equals(clientKey)) {
            client = AnthropicOkHttpClient.builder().apiKey(key).maxRetries(2).build();
            clientKey = key;
        }
        return client;
    }

    JSONObject ask(String key, String model, String prompt, String effort) throws JSONException {
        if (key == null || key.isEmpty()) return new JSONObject().put("ok", false).put("code", "no_key");
        OutputConfig.Effort e = "medium".equals(effort) ? OutputConfig.Effort.MEDIUM
                : "high".equals(effort) ? OutputConfig.Effort.HIGH : OutputConfig.Effort.LOW;
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .outputConfig(OutputConfig.builder().effort(e).build())
                .addUserMessage(prompt);
        if (OPUS.equals(model)) {
            // On a policy decline, let the API re-run the request on a suitable fallback model.
            b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        Message m;
        try {
            m = client(key).messages().create(b.build());
        } catch (UnauthorizedException | PermissionDeniedException ex) {
            return new JSONObject().put("ok", false).put("code", "bad_key");
        } catch (RateLimitException ex) {
            return new JSONObject().put("ok", false).put("code", "rate_limited");
        } catch (BadRequestException ex) {
            return new JSONObject().put("ok", false).put("code", "bad_request").put("message", ex.getMessage());
        } catch (AnthropicServiceException ex) {
            return new JSONObject().put("ok", false).put("code", "upstream_error").put("message", ex.getMessage());
        } catch (AnthropicIoException ex) {
            return new JSONObject().put("ok", false).put("code", "connection");
        }
        String stop = m.stopReason().map(Object::toString).orElse("");
        if (stop.equalsIgnoreCase("refusal")) return new JSONObject().put("ok", false).put("code", "refused");
        String text = m.content().stream().flatMap(c -> c.text().stream()).map(t -> t.text()).collect(Collectors.joining());
        if (text.trim().isEmpty()) return new JSONObject().put("ok", false).put("code", "empty_completion");
        return new JSONObject().put("ok", true).put("text", text).put("truncated", stop.equalsIgnoreCase("max_tokens"));
    }
}
