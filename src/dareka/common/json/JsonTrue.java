package dareka.common.json;

public class JsonTrue extends JsonBoolean {

    @Override
    public String toJson() {
        return "true";
    }

    @Override
    public StringBuilder toJson(StringBuilder sb) {
        return sb.append("true");
    }

    @Override
    public boolean value() {
        return true;
    }

}
