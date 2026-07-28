package dareka.common.json;

public class JsonNumber extends JsonValue {
    String raw;
    long valuel;
    double valued;

    public JsonNumber(long value) {
        this.valuel = value;
        this.valued = value;
        this.raw = Long.toString(value);
    }

    public JsonNumber(double value) {
        this.valuel = (long)value;
        this.valued = value;
        this.raw = Double.toString(value);
    }

    JsonNumber(long value, String raw) {
        this.valuel = value;
        this.valued = value;
        this.raw = raw;
    }
    JsonNumber(double value, String raw) {
        this.valuel = (long)value;
        this.valued = value;
        this.raw = raw;
    }

    public long getLong() {
        return valuel;
    }

    public double getDouble() {
        return valued;
    }

    public void setLong(long value) {
        this.valuel = value;
        this.valued = value;
        this.raw = Long.toString(value);
    }

    public void setDouble(double value) {
        this.valuel = (long)value;
        this.valued = value;
        this.raw = Double.toString(value);
    }

    @Override
    public String toJson() {
        return raw;
    }

    @Override
    public StringBuilder toJson(StringBuilder sb) {
        return sb.append(raw);
    }


    @Override
    public boolean toBoolean() {
        return 0 != valued;
    }
}
