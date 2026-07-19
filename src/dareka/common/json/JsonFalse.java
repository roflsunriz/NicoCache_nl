package dareka.common.json;

public class JsonFalse extends JsonBoolean {

    @Override
    public String toJson() {
        return "false";
    }

    @Override
    public StringBuilder toJson(StringBuilder sb) {
        return sb.append("false");
    }

    @Override
    public boolean value() {
        return false;
    }
}
