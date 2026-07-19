package dareka.common.json;

import dareka.common.TextUtil;


public class JsonString extends JsonValue {
    String value;
    String raw;

    public JsonString(String value) {
        this.value = value;
        this.raw = TextUtil.escapeJSON(value);

    }

    JsonString(String value, String raw) {
        this.value = value;
        this.raw = raw;
    }

    @Override
    public StringBuilder toJson(StringBuilder sb) {
        return sb.append('"').append(raw).append('"');
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean toBoolean() {
        return ! "".equals(value);
    }

    public String value() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
        this.raw = TextUtil.escapeJSON(value);
    }
}
