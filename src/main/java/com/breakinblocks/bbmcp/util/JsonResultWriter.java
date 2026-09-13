package com.breakinblocks.bbmcp.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

/** Writes bounded JSON result dumps below the active Minecraft game directory. */
public final class JsonResultWriter {
    private static final String DUMP_DIRECTORY = "dumps";
    private static final String JSON_SUFFIX = ".json";
    private static final Gson GSON = new Gson();

    private JsonResultWriter() {
    }

    /**
     * Writes a result to {@code <gameDirectory>/dumps/<operation>/<subdirectory>/<uuid>.json}.
     *
     * @param result JSON object to write
     * @param operation fixed operation identifier used as a directory name
     * @param subdirectory fixed subdirectory identifier used as a directory name
     * @param gameDirectory active Minecraft game directory
     * @return metadata for the newly created dump
     * @throws IOException if the directory or file cannot be accessed or written
     * @throws IllegalArgumentException if an identifier is not a single safe path component
     * @throws IllegalStateException if a path component is redirected or the output is invalid
     */
    public static DumpMetadata write(
            JsonObject result,
            String operation,
            String subdirectory,
            Path gameDirectory) throws IOException {
        Objects.requireNonNull(result, "result");
        requireIdentifier(operation, "operation");
        requireIdentifier(subdirectory, "subdirectory");
        Objects.requireNonNull(gameDirectory, "gameDirectory");

        Path resolvedGameDirectory = gameDirectory.toAbsolutePath().normalize().toRealPath();
        if (!Files.isDirectory(resolvedGameDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Minecraft game directory is not a directory: " + gameDirectory);
        }

        Path dumpsDirectory = createAndVerifyDirectory(
                resolvedGameDirectory.resolve(DUMP_DIRECTORY), resolvedGameDirectory,
                "JSON dump directory");
        Path operationDirectory = createAndVerifyDirectory(
                dumpsDirectory.resolve(operation), dumpsDirectory,
                "JSON operation directory");
        Path targetDirectory = createAndVerifyDirectory(
                operationDirectory.resolve(subdirectory), operationDirectory,
                "JSON result directory");

        String fileName = UUID.randomUUID() + JSON_SUFFIX;
        Path output = targetDirectory.resolve(fileName).normalize();
        if (!targetDirectory.equals(output.getParent())) {
            throw new IllegalStateException("JSON result path escaped its target directory: " + output);
        }

        String json = GSON.toJson(result);
        Files.writeString(output, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.size(output) == 0L) {
            throw new IllegalStateException("JSON result was not written correctly: " + output);
        }
        return new DumpMetadata(operation, subdirectory, fileName, output, Files.size(output));
    }

    private static Path createAndVerifyDirectory(Path directory, Path expectedParent, String description)
            throws IOException {
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) {
            throw new IllegalStateException(description + " must not be a symbolic link: " + directory);
        }
        Path resolvedDirectory = directory.toRealPath();
        if (!expectedParent.equals(resolvedDirectory.getParent())) {
            throw new IllegalStateException(description + " was redirected outside its expected parent: " + directory);
        }
        return resolvedDirectory;
    }

    private static void requireIdentifier(String identifier, String name) {
        if (identifier == null || identifier.isBlank()
                || !identifier.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException(name + " must be a non-empty safe path identifier");
        }
    }

    /** Metadata for a JSON result dump. */
    public record DumpMetadata(
            String operation,
            String subdirectory,
            String fileName,
            Path path,
            long sizeBytes) {
    }
}
