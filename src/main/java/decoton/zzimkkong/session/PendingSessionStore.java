package decoton.zzimkkong.session;

import decoton.zzimkkong.claude.ParsedIntent;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PendingSessionStore {

    private final Map<String, ParsedIntent> store = new ConcurrentHashMap<>();

    public void save(String sessionKey, ParsedIntent intent) {
        store.put(sessionKey, intent);
    }

    public ParsedIntent get(String sessionKey) {
        return store.get(sessionKey);
    }

    public void clear(String sessionKey) {
        store.remove(sessionKey);
    }
}
