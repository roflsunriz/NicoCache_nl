package nlfilterlab;

import java.util.Collection;

final class Json {
    private Json() {
    }

    static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder output = new StringBuilder(value.length() + 16).append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        return output.append('"').toString();
    }

    static String stringArray(Collection<String> values) {
        StringBuilder output = new StringBuilder("[");
        boolean first = true;
        for (String value : values) {
            if (!first) {
                output.append(',');
            }
            first = false;
            output.append(quote(value));
        }
        return output.append(']').toString();
    }
}
