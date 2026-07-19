package dareka.common.json;


public abstract class JsonValue {
    public String toJson() {
        return toJson(new StringBuilder()).toString();
    }
    public abstract StringBuilder toJson(StringBuilder sb);
    public abstract boolean toBoolean();

    @Override
    public String toString() {
        return toJson();
    }

    public boolean isNull() {
        return (this instanceof JsonNull);
    }

    public JsonValue getStrictly(Object...args)
            throws JsonTypeException {
        return Json.getValueStrictly(this, args);
    }
    public JsonValue get(Object...args) {
        try {
            return Json.getValueStrictly(this, args);
        } catch (JsonTypeException e) {
            return null;
        }
    }

    public JsonObject getObject(Object...args) {
        try {
            return getObjectStrictly(args);
        } catch (JsonTypeException e) {
            return null;
        }
    }
    public JsonObject getObjectStrictly(Object...args)
            throws JsonTypeException {
        JsonValue v = getStrictly(args);
        if (v instanceof JsonObject) {
            return (JsonObject)v;
        } else {
            throw new JsonTypeException();
        }
    }

    public JsonArray getArray(Object...args) {
        try {
            return getArrayStrictly(args);
        } catch (JsonTypeException e) {
            return null;
        }
    }
    public JsonArray getArrayStrictly(Object...args)
            throws JsonTypeException {
        JsonValue v = getStrictly(args);
        if (v instanceof JsonArray) {
            return (JsonArray)v;
        } else {
            throw new JsonTypeException();
        }
    }

    public boolean getBoolean(Object...args) {
        return Json.getBoolean(this, args);
    }

    public String getString(Object...args) {
        return Json.getString(this, args);
    }
}
