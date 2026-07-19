package dareka.common.json;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

public class JsonArray extends JsonValue {
    List<JsonValue> elements = new ArrayList<>();

    @Override
    public StringBuilder toJson(StringBuilder sb) {
        boolean needComma = false;
        sb.append('[');
        for (JsonValue v : elements) {
            if (needComma) {
                sb.append(',');
            } else {
                needComma = true;
            }
            v.toJson(sb);
        }
        return sb.append(']');
    }

    @Override
    public boolean toBoolean() {
        return true;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (JsonValue v : elements) {
            if (sb.length() > 1) {
                sb.append(",");
            }
            sb.append(v.toString());
        }
        return sb.toString();
    }

    public JsonValue get(int index) {
        if (0 <= index && index < elements.size()) {
            return elements.get(index);
        } else {
            return new JsonNull();
        }
    }

    public JsonArray put(int index, JsonValue value) {
        if (0 <= index && index < elements.size()) {
            elements.set(index, value);
        }
        return this;
    }

    public JsonArray add(JsonValue value) {
        elements.add(value);
        return this;
    }

    public int size() {
        return elements.size();
    }

    public List<JsonValue> getList() {
        return Collections.unmodifiableList(elements);
    }
}
