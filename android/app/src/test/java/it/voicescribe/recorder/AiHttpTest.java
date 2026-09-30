package it.voicescribe.recorder;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;

public class AiHttpTest {
    @Test public void providerDestinationsAreFixed() throws Exception {
        assertEquals("https://api.groq.com/openai/v1/models", AiHttp.endpoint("groq", "/models"));
        assertEquals("https://ollama.com/v1/chat/completions", AiHttp.endpoint("ollama", "/chat/completions"));
        assertEquals("https://ollama.com/api/tags", AiHttp.endpoint("ollama", "/models"));
        assertEquals("https://integrate.api.nvidia.com/v1/models", AiHttp.endpoint("nvidia", "/models"));
        assertEquals("https://openrouter.ai/api/v1/chat/completions", AiHttp.endpoint("openrouter", "/chat/completions"));
    }
    @Test public void arbitraryUrlOrPathCannotReceiveCredentials() throws Exception {
        for (String[] input : new String[][] {
            {"https://example.com", "/models"}, {"groq", "//example.com"},
            {"ollama", "/models/../../secret"}, {"nvidia", "/audio/transcriptions"}
        }) {
            try { AiHttp.endpoint(input[0], input[1]); fail("Destination accepted"); }
            catch (IOException expected) { }
        }
    }
    @Test public void invalidKeyIsRejectedBeforeNetwork() throws Exception {
        for (String key : new String[] {"", "key\r\nInjected: header"}) {
            try { AiHttp.request("groq", "/models", key, ""); fail("Invalid key accepted"); }
            catch (IOException expected) { }
        }
    }
}
