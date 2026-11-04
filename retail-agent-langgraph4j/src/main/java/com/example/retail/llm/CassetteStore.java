package com.example.retail.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** One human-readable file per interaction: prompt, marker, response. Keyed by SHA-256 of the prompt text. */
public class CassetteStore {
    private static final String MARK = "\n<<<RESPONSE>>>\n";
    private final Path dir;

    public CassetteStore(Path dir) {
        this.dir = dir;
    }

    public static String key(String promptText) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(promptText.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path file(String promptText) {
        return dir.resolve(PromptText.task(promptText) + "-" + key(promptText) + ".cassette");
    }

    public Optional<String> read(String promptText) {
        Path f = file(promptText);
        if (!Files.exists(f)) return Optional.empty();
        try {
            String content = Files.readString(f, StandardCharsets.UTF_8);
            int i = content.indexOf(MARK);
            return i < 0 ? Optional.empty() : Optional.of(content.substring(i + MARK.length()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void write(String promptText, String response) {
        try {
            Files.createDirectories(dir);
            Files.writeString(file(promptText), promptText + MARK + response, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
