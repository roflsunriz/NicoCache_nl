package dareka.common.json;

import java.util.Arrays;
import dareka.common.Logger;
import dareka.common.TextUtil;

// 適当に実装した JSON パーサ
// See: http://www.json.org/json-ja.html
public class Json {

    public static JsonValue parse(String input) {
        return parse(input, false);
    }

    public static JsonObject parseObject(String input) {
        return parseObject(input, false);
    }

    // JSONは本来コメントを許さないので
    // 設定ファイルでのみallowCommentを有効にすること
    public static JsonValue parse(String input, boolean allowComment) {
        if (input != null) {
            JsonParser json = new JsonParser(input, allowComment);
            return json.getResult();
        }
        return null;
    }

    public static JsonObject parseObject(String input, boolean allowComment) {
        if (input != null && input.startsWith("{")) {
            return (JsonObject)parse(input, allowComment);
        }
        return null;
    }


    public static JsonValue getValue(JsonValue root, Object[] a) {
        try {
            return getValueStrictly(root, a);
        } catch (JsonTypeException e) {
            return new JsonNull();
        }
    }

    public static JsonValue getValueStrictly(JsonValue root, Object[] a)
            throws JsonTypeException {
        if (root == null) return null;
        JsonValue v = root;

        for (Object key : a) {
            if (v instanceof JsonObject && key instanceof String) {
                String s = (String)key;
                v = ((JsonObject) v).get(s);
            } else if (v instanceof JsonArray && key instanceof Integer) {
                int i = (Integer)key;
                v = ((JsonArray) v).get(i);
            } else {
                throw new JsonTypeException();
            }
        }
        return v;
    }


    /* utility */
    public static String getString(JsonValue root, Object...args) {
        JsonValue v = getValue(root, args);
        if (v instanceof JsonString) {
            return ((JsonString) v).value();
        } else {
            return null;
        }
    }

    public static void putString(String replace, JsonValue root, Object...args) {
        JsonString newValue = new JsonString(replace);
        Object[] parentPath = Arrays.copyOf(args, args.length - 1);
        JsonValue parent = getValue(root, parentPath);
        Object key = args[args.length - 1];
        if (parent instanceof JsonObject) {
            ((JsonObject)parent).put((String)key, newValue);
        } else if (parent instanceof JsonArray) {
            ((JsonArray)parent).put((int)key, newValue);
        }
    }


    public static boolean getBoolean(JsonValue root, Object...args) {
        JsonValue v = getValue(root, args);
        if (v != null) {
            return v.toBoolean();
        } else {
            return false;
        }
    }
}

class JsonParser {
    private String input;
    private boolean allowComment;
    private int index;
    private JsonValue result;
    private boolean shownDebugDetail;

    public JsonParser(String input, boolean allowComment) {
        this.input  = input;
        this.allowComment = allowComment;
        this.result = parseValue();
    }

    public JsonParser(String input) {
        this(input, false);
    }

    public JsonValue getResult() {
        return this.result;
    }

    private void debugLog(String message) {
        char c = index < input.length() ? input.charAt(index) : 0;
        if (!shownDebugDetail) {
            Logger.debug("JSON: input = " + input.substring(0, index) +
                    c + " <<ERROR at " + index);
            shownDebugDetail = true;
        }
        Logger.debug("JSON: " + message);
    }


    private JsonValue parseValue() {
        switch (skipBlankCharAndComment()) {
        case '{':
            return parseObject();
        case '[':
            return parseArray();
        case '"':
            return parseString();
        }
        return parseLiteral();
    }

    private JsonObject parseObject() {
        index++;
        JsonObject object = new JsonObject();
        JsonValue value;
        for (char c;;) {
            if ((value = parseValue()) == null) {
                if ((c = skipBlankCharAndComment()) == '}') {
                    index++;
                    return object;
                } else if (c == ',' && object.members.size() > 0) {
                    index++;
                } else {
                    break;
                }
            } else if (value instanceof JsonString) {
                JsonString s = (JsonString) value;
                if (skipBlankCharAndComment() != ':') {
                    break;
                }
                index++;
                if ((value = parseValue()) == null) {
                    break;
                }
                object.members.put(s.value, value);
            } else {
                break;
            }
        }
        debugLog("object parse error");
        return null;
    }

    private JsonArray parseArray() {
        index++;
        JsonArray array = new JsonArray();
        JsonValue value;
        for (char c;;) {
            if ((value = parseValue()) == null) {
                if ((c = skipBlankCharAndComment()) == ']') {
                    index++;
                    return array;
                } else if (c == ',' && array.elements.size() > 0) {
                    index++;
                } else {
                    break;
                }
            } else {
                array.elements.add(value);
            }
        }
        debugLog("array parse error");
        return null;
    }

    private JsonString parseString() {
        int start = index + 1;
        for (char c;;) {
            if ((c = nextChar()) == '"') {
                String value = input.substring(start, index++);
                return new JsonString(TextUtil.unescapeJSON(value), value);
            } else if (c == '\\') {
                if (nextChar() == 'u') {
                    index += 4;
                }
                // 厳密にパースするなら \[\"/bfnrt] 以外はエラー
            } else if (c == 0) {
                break;
            }
        }
        debugLog("string parse error");
        return null;
    }

    private JsonValue parseLiteral() {
        char c = skipBlankCharAndComment();
        int start = index;
        if (isLower(c)) {
            skipLowerChar();
            int end = index;
            if (isValueEnd(skipBlankCharAndComment())) {
                String value = input.substring(start, end);
                switch (value) {
                case "null":
                    return new JsonNull();
                case "true":
                    return new JsonTrue();
                case "false":
                    return new JsonFalse();
                }
            }
            debugLog("literal parse error");
        } else if (isDigit(c) || c == '-') {
            JsonNumber number = parseNumber();
            if (number != null) {
                return number;
            }
            debugLog("number parse error");
        }
        return null;
    }

    private JsonNumber parseNumber() {
        boolean floating = false;
        char c = skipBlankCharAndComment();
        int start = index;
        if (c == '-') {
            c = nextChar();
        }
        if (isDigit(c)) {
            if (c == '0') {
                c = nextChar();
            } else {
                while (isDigit(c = nextChar()))
                    ;
            }
            if (c == '.') {
                floating = true;
                if (!isDigit(c = nextChar())) {
                    return null;
                }
                while (isDigit(c = nextChar()))
                    ;
            }
            if (c == 'e' || c == 'E') {
                floating = true;
                c = nextChar();
                if (c == '+' || c == '-') {
                    c = nextChar();
                }
                if (!isDigit(c)) {
                    return null;
                }
                while (isDigit(c = nextChar()))
                    ;
            }
            int end = index;
            if (isValueEnd(skipBlankCharAndComment())) {
                String s = input.substring(start, end);
                if (floating) {
                    return new JsonNumber(Double.parseDouble(s), s);
                } else {
                    return new JsonNumber(Long.parseLong(s), s);
                }
            }
        }
        return null;
    }

    private boolean isBlank(char c) {
        return c <= 0x20;
    }

    private boolean isDigit(char c) {
        return '0' <= c && c <= '9';
    }

    private boolean isLower(char c) {
        return 'a' <= c && c <= 'z';
    }

    private boolean isValueEnd(char c) {
        return c == ',' || c == '}' || c == ']';
    }

    private char nextChar() {
        if (++index < input.length()) {
            return input.charAt(index);
        }
        return 0;
    }

    private char skipBlankCharAndComment() {
        char c;
        while (index < input.length()) {
            if (isBlank(c = input.charAt(index))) {
                index++;
            } else if (allowComment && c == '/') {
                if (index + 1 < input.length()) {
                    char c2 = input.charAt(index + 1);
                    switch (c2) {
                    case '/':
                        skipCommentLine(); break;
                    case '*':
                        skipCommentBlock(); break;
                    default:
                        return c;
                    }
                } else {
                    return c;
                }
            } else {
                return c;
            }
        }
        return 0;
    }

    private char skipLowerChar() {
        for (char c; index < input.length(); index++) {
            if (!isLower(c = input.charAt(index))) {
                return c;
            }
        }
        return 0;
    }

    private void skipCommentLine() {
        index++;
        while (true) {
            char c = nextChar();
            if (c == 0 || c == '\n') return;
        }
    }

    private void skipCommentBlock() {
        index++;
        boolean star = false;
        while (true) {
            char c = nextChar();
            if (c == 0) {
                debugLog("comment parse error");
                return;
            }
            if (c == '*') {
                star = true;
            }
            else if (star) {
                if (c == '/') {
                    index++;
                    return;
                }
                else {
                    star = false;
                }
            }
        }
    }
}

