package extensions;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import dareka.extensions.Extension;
import dareka.processor.HttpRequestHeader;
import dareka.processor.Processor;
import dareka.processor.Resource;
import dareka.processor.StringResource;

public final class LegacyFunctionalExtension implements Extension, Processor {
    private static final Pattern URL_PATTERN = Pattern.compile(
            "^https?://www\\.nicovideo\\.jp/functional/legacy$");

    public LegacyFunctionalExtension() {
        try {
            Files.writeString(Path.of("legacy-extension-loaded.txt"), "loaded",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Object queryInterface(Type type) {
        return type == Type.Processor1 ? this : null;
    }

    @Override
    public String getVersionString() {
        return "LegacyFunctionalExtension/1";
    }

    @Override
    public String[] getSupportedMethods() {
        return new String[] { "GET" };
    }

    @Override
    public Pattern getSupportedURLAsPattern() {
        return URL_PATTERN;
    }

    @Override
    public String getSupportedURLAsString() {
        return null;
    }

    @Override
    public Resource onRequest(HttpRequestHeader requestHeader, Socket browser) {
        return new StringResource("legacy-extension-ok");
    }
}
