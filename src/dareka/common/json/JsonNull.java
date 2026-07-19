package dareka.common.json;

public class JsonNull extends JsonValue {
    @Override
    public String toJson() {
        return "null";
    }

    @Override
    public StringBuilder toJson(StringBuilder sb) {
        return sb.append("null");
    }

    @Override
    public boolean toBoolean() {
        return false;
    }
}
