package dareka.common.json;

import dareka.common.TextUtil;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

public class JsonObject extends JsonValue {
    Map<String, JsonValue> members = new LinkedHashMap<>();

    @Override
    public String toString() {
        return "{object}";
    }

    @Override
    public StringBuilder toJson(StringBuilder sb) {
        boolean needComma = false;
        sb.append('{');
        for (Map.Entry<String, JsonValue> e : members.entrySet()) {
            if (needComma) {
                sb.append(',');
            } else {
                needComma = true;
            }
            sb.append('"').append(TextUtil.escapeJSON(e.getKey())).append('"');
            sb.append(':');
            e.getValue().toJson(sb);
        }
        return sb.append('}');
    }

    @Override
    public boolean toBoolean() {
        return true;
    }

    public JsonValue get(String key) {
        if (members.containsKey(key)) {
            return members.get(key);
        } else {
            return new JsonNull();
        }
    }

    public JsonObject put(String key, JsonValue value) {
        members.put(key, value);
        return this;
    }

    public Map<String, JsonValue> getMap() {
        return Collections.unmodifiableMap(members);
    }
}
