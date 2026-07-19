package dareka.common.json;

public abstract class JsonBoolean extends JsonValue {

    @Override
    public boolean toBoolean() {
        return value();
    }

    public abstract boolean value();
}
