package com.github.xandergos.terraindiffusionmc.worldgen.diagnostics;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

/** Called only by the diagnostics worker. Bounds storage to the active file and one backup. */
final class RotatingPerformanceLog {
    private final Path file;private final long limit;
    RotatingPerformanceLog(Path file,long limit){if(limit<1)throw new IllegalArgumentException("limit");this.file=file;this.limit=limit;}
    void append(String line) throws IOException {
        byte[] bytes=(line+"\n").getBytes(StandardCharsets.UTF_8);
        if(bytes.length>limit) throw new IOException("Performance entry exceeds log limit");
        Files.createDirectories(file.toAbsolutePath().getParent());
        if(Files.exists(file)&&Files.size(file)+bytes.length>limit)
            Files.move(file,file.resolveSibling(file.getFileName()+".1"),StandardCopyOption.REPLACE_EXISTING);
        Files.write(file,bytes,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
}
