package com.yucareux.tellus.worldgen.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Cross-process world-root lease for native batch publication and recovery. */
final class TellusNativeWorldLease implements AutoCloseable {
    private static final String LOCK_NAME = ".native-world.lock";
    private final FileChannel channel;
    private final FileLock lock;

    private TellusNativeWorldLease(FileChannel channel, FileLock lock) {
        this.channel = channel; this.lock = lock;
    }

    static TellusNativeWorldLease acquire(TellusNativeChunkPersistence.Binding binding) throws IOException {
        Path directory = binding.worldRoot().resolve("tellus/persistence-journal");
        Files.createDirectories(directory);
        FileChannel channel = FileChannel.open(directory.resolve(LOCK_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("native world lease is held by another writer");
            return new TellusNativeWorldLease(channel, lock);
        } catch (OverlappingFileLockException overlap) {
            channel.close();
            throw new IOException("native world lease is held by another writer", overlap);
        } catch (IOException failure) {
            channel.close();
            throw failure;
        }
    }

    @Override public void close() throws IOException {
        try { lock.release(); } finally { channel.close(); }
    }
}
