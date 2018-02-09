/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.caching.internal.version2;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.gradle.api.NonNullApi;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RelativePath;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.cache.StringInterner;
import org.gradle.api.internal.changedetection.TaskArtifactState;
import org.gradle.api.internal.changedetection.state.DirContentSnapshot;
import org.gradle.api.internal.changedetection.state.DirectoryFileSnapshot;
import org.gradle.api.internal.changedetection.state.FileContentSnapshot;
import org.gradle.api.internal.changedetection.state.FileHashSnapshot;
import org.gradle.api.internal.changedetection.state.FileSnapshot;
import org.gradle.api.internal.changedetection.state.FileSystemMirror;
import org.gradle.api.internal.changedetection.state.RegularFileSnapshot;
import org.gradle.api.internal.tasks.OriginTaskExecutionMetadata;
import org.gradle.api.internal.tasks.ResolvedTaskOutputFilePropertySpec;
import org.gradle.api.internal.tasks.execution.TaskOutputsGenerationListener;
import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.internal.OutputPropertySpec;
import org.gradle.caching.internal.OutputType;
import org.gradle.caching.internal.tasks.AbstractLoadCommand;
import org.gradle.caching.internal.tasks.TaskOutputCachingBuildCacheKey;
import org.gradle.caching.internal.tasks.origin.TaskOutputOriginFactory;
import org.gradle.caching.internal.tasks.origin.TaskOutputOriginReader;
import org.gradle.caching.internal.tasks.origin.TaskOutputOriginWriter;
import org.gradle.internal.file.FileType;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hasher;
import org.gradle.internal.hash.Hashing;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("Since15")
@NonNullApi
public class TaskOutputCacheCommandFactoryV2 {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final ThreadLocal<byte[]> COPY_BUFFERS = new ThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[BUFFER_SIZE];
        }
    };

    private final TaskOutputOriginFactory taskOutputOriginFactory;
    private final FileSystemMirror fileSystemMirror;
    private final StringInterner stringInterner;
    private final LocalBuildCacheServiceV2 local;

    public TaskOutputCacheCommandFactoryV2(TaskOutputOriginFactory taskOutputOriginFactory, FileSystemMirror fileSystemMirror, StringInterner stringInterner, LocalBuildCacheServiceV2 local) {
        this.taskOutputOriginFactory = taskOutputOriginFactory;
        this.fileSystemMirror = fileSystemMirror;
        this.stringInterner = stringInterner;
        this.local = local;
    }

    public BuildCacheLoadCommandV2<OriginTaskExecutionMetadata> createLoad(TaskOutputCachingBuildCacheKey cacheKey, SortedSet<ResolvedTaskOutputFilePropertySpec> outputProperties, TaskInternal task, FileCollection localStateFiles, TaskOutputsGenerationListener taskOutputsGenerationListener, TaskArtifactState taskArtifactState) {
        return new LoadCommand(cacheKey, outputProperties, task, localStateFiles, taskOutputsGenerationListener, taskArtifactState);
    }

    public BuildCacheStoreCommandV2 createStore(TaskOutputCachingBuildCacheKey cacheKey, SortedSet<ResolvedTaskOutputFilePropertySpec> outputProperties, Map<String, Map<String, FileContentSnapshot>> outputSnapshots, TaskInternal task, long taskExecutionTime) {
        return new StoreCommand(cacheKey, outputProperties, outputSnapshots, task, taskExecutionTime);
    }

    private static class LoadResult {
        private final long artifactEntryCount;
        private final OriginTaskExecutionMetadata metadata;

        public LoadResult(long artifactEntryCount, OriginTaskExecutionMetadata metadata) {
            this.artifactEntryCount = artifactEntryCount;
            this.metadata = metadata;
        }

        public long getArtifactEntryCount() {
            return artifactEntryCount;
        }

        public OriginTaskExecutionMetadata getMetadata() {
            return metadata;
        }
    }

    private class LoadCommand extends AbstractLoadCommand<Void, LoadResult> implements BuildCacheLoadCommandV2<OriginTaskExecutionMetadata> {

        private final Map<String, Map<String, FileContentSnapshot>> incomingSnapshotsPerProperty;

        public LoadCommand(BuildCacheKey key, SortedSet<? extends OutputPropertySpec> outputProperties, TaskInternal task, FileCollection localStateFiles, TaskOutputsGenerationListener taskOutputsGenerationListener, TaskArtifactState taskArtifactState) {
            super(key, outputProperties, task, localStateFiles, taskOutputsGenerationListener, taskArtifactState, fileSystemMirror, stringInterner, taskOutputOriginFactory);
            this.incomingSnapshotsPerProperty = taskArtifactState.getOutputContentSnapshots();
        }

        @Override
        public Result<OriginTaskExecutionMetadata> load() {
            LoadResult result = performLoad(null);
            final OriginTaskExecutionMetadata metadata;
            final long count;
            if (result == null) {
                metadata = null;
                count = -1;
            } else {
                metadata = result.getMetadata();
                count = result.getArtifactEntryCount();
            }
            return new Result<OriginTaskExecutionMetadata>() {
                @Override
                public long getArtifactEntryCount() {
                    return count;
                }

                @Override
                public OriginTaskExecutionMetadata getMetadata() {
                    return metadata;
                }
            };
        }

        @Override
        protected LoadResult performLoad(@Nullable Void input, SortedSet<? extends OutputPropertySpec> outputProperties, TaskOutputOriginReader reader) throws IOException {
            HashCode resultKey = HashCode.fromString(getKey().getHashCode());
            CacheEntry entry = local.get(resultKey);
            if (entry == null) {
                return null;
            }
            if (!(entry instanceof ResultEntry)) {
                throw new IllegalStateException("Found an entry of unknown type for " + resultKey);
            }

            ResultEntry result = (ResultEntry) entry;
            Map<String, HashCode> outputs = result.getOutputs();
            ImmutableListMultimap.Builder<String, FileSnapshot> outgoingSnapshotsPerProperty = ImmutableListMultimap.builder();
            for (OutputPropertySpec propertySpec : outputProperties) {
                String propertyName = propertySpec.getPropertyName();

                File outputRoot = propertySpec.getOutputRoot();
                if (outputRoot == null) {
                    // Optional output was null
                    continue;
                }

                HashCode outputKey = outputs.get(propertyName);
                if (outputKey == null) {
                    // Outputs were deleted during task execution
                    FileUtils.deleteQuietly(outputRoot);
                    continue;
                }

                Map<String, FileContentSnapshot> incomingSnapshots = getIncomingSnapshots(propertyName);

                if (propertySpec.getOutputType() == OutputType.FILE) {
                    FileUtils.forceMkdir(outputRoot.getParentFile());
                }
                List<FileSnapshot> outgoingSnapshots = Lists.newArrayListWithExpectedSize(incomingSnapshots.size());
                load(outputKey, outputRoot, null, incomingSnapshots, outgoingSnapshots);
                outgoingSnapshotsPerProperty.putAll(propertyName, outgoingSnapshots);

                for (String remainingAbsolutePath : incomingSnapshots.keySet()) {
                    // System.out.println("> Deleting redundant output " + remainingAbsolutePath);
                    File remainingFile = new File(remainingAbsolutePath);
                    if (remainingFile.exists()) {
                        FileUtils.forceDelete(remainingFile);
                    }
                }
            }
            OriginTaskExecutionMetadata originMetadata = reader.execute(result.getOriginMetadata());
            ImmutableListMultimap<String, FileSnapshot> outgoingSnapshots = outgoingSnapshotsPerProperty.build();
            updateSnapshots(outgoingSnapshots, originMetadata);
            return new LoadResult(outgoingSnapshots.size(), originMetadata);
        }

        private Map<String, FileContentSnapshot> getIncomingSnapshots(String propertyName) {
            Map<String, FileContentSnapshot> incomingSnapshots = incomingSnapshotsPerProperty.get(propertyName);
            if (incomingSnapshots == null) {
                throw new IllegalStateException("Cannot find incoming output snapshots for " + propertyName);
            }
            return new HashMap<String, FileContentSnapshot>(incomingSnapshots);
        }

        private void load(HashCode key, File target, @Nullable RelativePath parent, Map<String, FileContentSnapshot> incomingSnapshots, Collection<FileSnapshot> outgoingSnapshots) throws IOException {
            CacheEntry entry = local.get(key);
            String absolutePath = target.getAbsolutePath();
            FileSnapshot outgoingSnapshot;
            FileContentSnapshot incomingSnapshot = incomingSnapshots.remove(absolutePath);
            if (entry instanceof FileEntry) {
                FileContentSnapshot outgoingContentSnapshot;
                if (incomingSnapshot != null
                    && incomingSnapshot.getType() == FileType.RegularFile
                    && incomingSnapshot.getContentMd5().equals(key)
                ) {
                    outgoingContentSnapshot = incomingSnapshot;
                } else {
                    InputStream inputStream = ((FileEntry) entry).read();
                    try {
                        if (incomingSnapshot != null && incomingSnapshot.getType() != FileType.RegularFile) {
                            FileUtils.forceDelete(target);
                        }
                        OutputStream outputStream = new FileOutputStream(target);
                        try {
                            IOUtils.copyLarge(inputStream, outputStream, COPY_BUFFERS.get());
                        } finally {
                            IOUtils.closeQuietly(outputStream);
                        }
                    } finally {
                        IOUtils.closeQuietly(inputStream);
                    }
                    outgoingContentSnapshot = new FileHashSnapshot(key);
                }
                outgoingSnapshot = new RegularFileSnapshot(absolutePath, getChildPath(parent, target, true), parent == null, outgoingContentSnapshot);
            } else if (entry instanceof ManifestEntry) {
                // TODO Avoid loading directory if its hash is already what we expect here
                ManifestEntry manifest = (ManifestEntry) entry;
                ImmutableSortedMap<String, HashCode> childEntries = manifest.getChildren();
                if (incomingSnapshot == null || incomingSnapshot.getType() != FileType.Directory) {
                    if (target.exists()) {
                        FileUtils.forceDelete(target);
                    }
                    FileUtils.forceMkdir(target);
                }
                RelativePath relativePath = getChildPath(parent, target, false);
                for (Map.Entry<String, HashCode> childEntry : childEntries.entrySet()) {
                    load(childEntry.getValue(), new File(target, childEntry.getKey()), relativePath, incomingSnapshots, outgoingSnapshots);
                }
                outgoingSnapshot = new DirectoryFileSnapshot(absolutePath, relativePath, parent == null);
            } else if (entry == null) {
                throw new IllegalStateException("Entry not found: " + key);
            } else {
                throw new IllegalStateException("Invalid entry type: " + entry.getClass().getName());
            }
            outgoingSnapshots.add(outgoingSnapshot);
        }
    }

    private static RelativePath getChildPath(@Nullable RelativePath parent, File target, boolean isFile) {
        RelativePath relativePath;
        if (parent == null) {
            if (isFile) {
                relativePath = RelativePath.parse(true, target.getName());
            } else {
                relativePath = RelativePath.EMPTY_ROOT;
            }
        } else {
            relativePath = parent.append(isFile, target.getName());
        }
        return relativePath;
    }

    private class StoreCommand implements BuildCacheStoreCommandV2 {
        private final TaskOutputCachingBuildCacheKey key;
        private final SortedSet<ResolvedTaskOutputFilePropertySpec> outputProperties;
        private final Map<String, Map<String, FileContentSnapshot>> outputSnapshots;
        private final TaskInternal task;
        private final long taskExecutionTime;

        private StoreCommand(TaskOutputCachingBuildCacheKey key, SortedSet<ResolvedTaskOutputFilePropertySpec> outputProperties, Map<String, Map<String, FileContentSnapshot>> outputSnapshots, TaskInternal task, long taskExecutionTime) {
            this.key = key;
            this.outputProperties = outputProperties;
            this.outputSnapshots = outputSnapshots;
            this.task = task;
            this.taskExecutionTime = taskExecutionTime;
        }

        @Override
        public BuildCacheKey getKey() {
            return key;
        }

        @Override
        public Result store() {
            final AtomicInteger count = new AtomicInteger();
            ImmutableSortedMap.Builder<String, HashCode> outputHashes = ImmutableSortedMap.naturalOrder();
            for (final OutputPropertySpec outputProperty : outputProperties) {
                final File outputRoot = outputProperty.getOutputRoot();
                if (outputRoot == null) {
                    continue;
                }
                String absoluteRootPath = outputRoot.getAbsolutePath();
                String outputPropertyName = outputProperty.getPropertyName();
                final Map<String, FileContentSnapshot> outputs = outputSnapshots.get(outputPropertyName);
                if (outputs == null) {
                    throw new IllegalStateException("Cannot find outputs for " + outputProperty);
                }
                if (outputs.isEmpty()) {
                    // TODO Support missing output
                    continue;
                }

                HashCode outputHash;
                switch (outputProperty.getOutputType()) {
                    case DIRECTORY:
                        Path rootPath = outputRoot.toPath();
                        DirectorySnapshotX root = new DirectorySnapshotX();
                        for (Map.Entry<String, FileContentSnapshot> entry : outputs.entrySet()) {
                            String absolutePath = entry.getKey();
                            if (absolutePath.equals(absoluteRootPath)) {
                                // This is the root directory itself
                                continue;
                            }
                            File absoluteFile = new File(absolutePath);
                            Path relativePath = rootPath.relativize(absoluteFile.toPath());
                            Iterator<Path> iPathElements = relativePath.iterator();
                            DirectorySnapshotX parent = root;
                            FileContentSnapshot snapshot = entry.getValue();
                            while (iPathElements.hasNext()) {
                                Path element = iPathElements.next();
                                if (iPathElements.hasNext() || snapshot instanceof DirContentSnapshot) {
                                    // This is a directory
                                    parent = parent.getOrAddDirectory(element.toString());
                                } else if (snapshot instanceof FileHashSnapshot) {
                                    // This is the final file
                                    parent.addFile(element.toString(), snapshot.getContentMd5(), absoluteFile);
                                } else {
                                    throw new IllegalStateException("Invalid content snapshot type: " + snapshot);
                                }
                            }
                        }
                        outputHash = root.put(local);
                        break;
                    case FILE:
                        FileContentSnapshot fileSnapshot = Iterables.getOnlyElement(outputs.values());
                        outputHash = fileSnapshot.getContentMd5();
                        local.put(outputHash, outputRoot);
                        count.incrementAndGet();
                        break;
                    default:
                        throw new AssertionError();
                }
                outputHashes.put(outputPropertyName, outputHash);
            }
            TaskOutputOriginWriter writer = taskOutputOriginFactory.createWriter(task, taskExecutionTime);
            ByteArrayOutputStream originMetadata = new ByteArrayOutputStream();
            writer.execute(originMetadata);
            local.put(HashCode.fromString(key.getHashCode()), outputHashes.build(), originMetadata.toByteArray());
            return new Result() {
                @Override
                public long getArtifactEntryCount() {
                    return count.get();
                }
            };
        }

        private abstract class SnapshotX {
            public abstract HashCode put(LocalBuildCacheServiceV2 local);
        }

        private class DirectorySnapshotX extends SnapshotX {
            private final Map<String, SnapshotX> children = Maps.newHashMap();

            public DirectorySnapshotX getOrAddDirectory(String name) {
                SnapshotX entry = children.get(name);
                if (entry == null) {
                    DirectorySnapshotX directory = new DirectorySnapshotX();
                    children.put(name, directory);
                    return directory;
                } else if (entry instanceof DirectorySnapshotX) {
                    return (DirectorySnapshotX) entry;
                } else {
                    throw new IllegalStateException("Incorrect child type");
                }
            }

            public void addFile(String name, HashCode hashCode, File file) {
                children.put(name, new FileSnapshotX(hashCode, file));
            }

            @Override
            public HashCode put(LocalBuildCacheServiceV2 local) {
                ImmutableSortedMap.Builder<String, HashCode> childHashes = ImmutableSortedMap.naturalOrder();
                Hasher hasher = Hashing.md5().newHasher();
                for (Map.Entry<String, SnapshotX> entry : children.entrySet()) {
                    String childName = entry.getKey();
                    SnapshotX child = entry.getValue();
                    HashCode childHash = child.put(local);
                    hasher.putString(entry.getKey());
                    hasher.putHash(childHash);
                    childHashes.put(childName, childHash);
                }
                HashCode hashCode = hasher.hash();
                local.put(hashCode, childHashes.build());
                return hashCode;
            }
        }

        private class FileSnapshotX extends SnapshotX {
            private final HashCode hashCode;
            private File file;

            public FileSnapshotX(HashCode hashCode, File file) {
                this.hashCode = hashCode;
                this.file = file;
            }

            @Override
            public HashCode put(LocalBuildCacheServiceV2 local) {
                local.put(hashCode, file);
                return hashCode;
            }
        }
    }
}
