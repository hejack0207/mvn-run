package fr.lteconsulting.mvnrun;

import org.apache.commons.cli.Options;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.FSDirectory;
import org.apache.maven.index.*;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.index.context.UnsupportedExistingLuceneIndexException;
import org.apache.maven.index.packer.IndexPacker;
import org.apache.maven.index.packer.IndexPackingRequest;
import org.apache.maven.index.packer.IndexPackingRequest.IndexFormat;
import org.apache.maven.index.updater.DefaultIndexUpdater;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.logging.LoggerManager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static fr.lteconsulting.mvnrun.MavenRunApp.IndexOptions;

/**
 * A command line tool that can be used to index local Maven repository.
 * <p/>
 * The following command line options are supported:
 * <ul>
 * <li>-repository <path> : required path to repository to be indexed</li>
 * <li>-index <path> : required index folder used to store created index or where previously created index is
 * stored</li>
 * <li>-name <path> : required repository name/id</li>
 * <li>-target <path> : optional folder name where to save produced index files</li>
 * <li>-type <path> : optional indexer types</li>
 * <li>-format <path> : optional indexer formats</li>
 * </ul>
 * When index folder contains previously created index, the tool will use it as a base line and will generate chunks for
 * the incremental updates.
 * <p/>
 * The indexer types could be one of default, min or full. You can also specify list of comma-separated custom index
 * creators. An index creator should be a regular Plexus component, see
 * {@link org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator} and
 * {@link org.apache.maven.index.creator.JarFileContentsIndexCreator}.
 */
public class MavenIndWriter {
    // Command line options
    private static final long MB = 1024 * 1024;

    private Options options;

    private int status = 0;

    public String getPomPropertiesPath() {
        return "META-INF/maven/org.sonatype.nexus/nexus-indexer/pom.properties";
    }

    public void invokePlexusComponent(final IndexOptions cli, PlexusContainer plexus)
            throws Exception {
        final DefaultContainerConfiguration configuration = new DefaultContainerConfiguration();
        //configuration.setClassWorld(((DefaultPlexusContainer) plexus).getClassWorld());
        configuration.setClassPathScanning(PlexusConstants.SCANNING_INDEX);

        plexus = new DefaultPlexusContainer(configuration);

        if (cli.quiet) {
            setLogLevel(plexus, Logger.LEVEL_DISABLED);
        } else if (cli.debug) {
            setLogLevel(plexus, Logger.LEVEL_DEBUG);
        } else if (cli.errors) {
            setLogLevel(plexus, Logger.LEVEL_ERROR);
        }

        if (cli.unpack) {
            unpack(cli, plexus);
        } else if (cli.indexFolder != null && cli.repoFolder != null) {
            index(cli, plexus);
        } else {
            status = 1;
        }
    }

    private void setLogLevel(PlexusContainer plexus, int logLevel)
            throws ComponentLookupException {
        plexus.lookup(LoggerManager.class).setThresholds(logLevel);
    }

    private void index(final IndexOptions cli, PlexusContainer plexus)
            throws ComponentLookupException, IOException, UnsupportedExistingLuceneIndexException {
        File indexFolder = new File(cli.indexFolder);

        File outputFolder = new File(cli.targetFolder);

        File repositoryFolder = new File(cli.repoFolder);

        String repositoryName = cli.repoName != null ? cli.repoName : indexFolder.getName();

        List<IndexCreator> indexers = getIndexers(cli, plexus);

        if (!cli.quiet) {
            System.err.printf("Repository Folder: %s\n", repositoryFolder.getAbsolutePath());
            System.err.printf("Index Folder:      %s\n", indexFolder.getAbsolutePath());
            System.err.printf("Output Folder:     %s\n", outputFolder.getAbsolutePath());
            System.err.printf("Repository name:   %s\n", repositoryName);
            System.err.printf("Indexers: %s\n", indexers.toString());

            if (cli.createChecksum) {
                System.err.printf("Will create checksum files for all published files (sha1, md5).\n");
            } else {
                System.err.printf("Will not create checksum files.\n");
            }

            if (cli.createChunks) {
                System.err.printf("Will create incremental chunks for changes, along with baseline file.\n");
            } else {
                System.err.printf("Will create baseline file.\n");
            }
        }

        NexusIndexer indexer = plexus.lookup(NexusIndexer.class);

        long tstart = System.currentTimeMillis();

        IndexingContext context = indexer.addIndexingContext( //
                repositoryName, // context id
                repositoryName, // repository id
                repositoryFolder, // repository folder
                indexFolder, // index folder
                null, // repositoryUrl
                null, // index update url
                indexers);

        try {
            IndexPacker packer = plexus.lookup(IndexPacker.class);

            ArtifactScanningListener listener = new IndexerListener(context, cli.debug, cli.quiet);

            indexer.scan(context, listener, true);

            IndexSearcher indexSearcher = context.acquireIndexSearcher();

            try {
                IndexPackingRequest request =
                        new IndexPackingRequest(context, indexSearcher.getIndexReader(), outputFolder);

                request.setCreateChecksumFiles(cli.createChecksum);

                request.setCreateIncrementalChunks(cli.createChunks);

                request.setFormats(Arrays.asList(IndexFormat.FORMAT_V1));

                if (cli.chunksKeeped != null) {
                    request.setMaxIndexChunks(cli.chunksKeeped.intValue());
                }

                packIndex(packer, request, cli.debug, cli.quiet);
            } finally {
                context.releaseIndexSearcher(indexSearcher);
            }

            if (!cli.quiet) {
                printStats(tstart);
            }
        } finally {
            indexer.removeIndexingContext(context, false);
        }
    }

    private void unpack(IndexOptions cli, PlexusContainer plexus)
            throws ComponentLookupException, IOException {
        final File indexFolder = new File(cli.indexFolder).getCanonicalFile();
        final File indexArchive = new File(indexFolder, IndexingContext.INDEX_FILE_PREFIX + ".gz");

        final File outputFolder = new File(cli.targetFolder).getCanonicalFile();

        if (!cli.quiet) {
            System.err.printf("Index Folder:      %s\n", indexFolder.getAbsolutePath());
            System.err.printf("Output Folder:     %s\n", outputFolder.getAbsolutePath());
        }

        long tstart = System.currentTimeMillis();

        final List<IndexCreator> indexers = getIndexers(cli, plexus);

        try (BufferedInputStream is = new BufferedInputStream(new FileInputStream(indexArchive)); //
             FSDirectory directory = FSDirectory.open(outputFolder.toPath())) {
            DefaultIndexUpdater.unpackIndexData(is, directory, (IndexingContext) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class[]{IndexingContext.class}, null/*new PartialImplementation()
                    {
                        public List<IndexCreator> getIndexCreators()
                        {
                            return indexers;
                        }
                    }*/)

            );
        }

        if (!cli.quiet) {
            printStats(tstart);
        }
    }

    private List<IndexCreator> getIndexers(final IndexOptions cli, PlexusContainer plexus)
            throws ComponentLookupException {
        String type = "default";

        if (cli.type != null) {
            type = cli.type;
        }

        List<IndexCreator> indexers = new ArrayList<IndexCreator>(); // NexusIndexer.DEFAULT_INDEX;

        if ("default".equals(type)) {
            indexers.add(plexus.lookup(IndexCreator.class, "min"));
            indexers.add(plexus.lookup(IndexCreator.class, "jarContent"));
        } else if ("full".equals(type)) {
            for (Object component : plexus.lookupList(IndexCreator.class)) {
                indexers.add((IndexCreator) component);
            }
        } else {
            for (String hint : type.split(",")) {
                indexers.add(plexus.lookup(IndexCreator.class, hint));
            }
        }
        return indexers;
    }

    private void packIndex(IndexPacker packer, IndexPackingRequest request, boolean debug, boolean quiet) {
        try {
            packer.packIndex(request);
        } catch (IOException e) {
            if (!quiet) {
                System.err.printf("Cannot zip index; \n", e.getMessage());

                if (debug) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void printStats(final long startTimeInMillis) {
        long t = System.currentTimeMillis() - startTimeInMillis;

        long s = TimeUnit.MILLISECONDS.toSeconds(t);
        if (t > TimeUnit.MINUTES.toMillis(1)) {
            long m = TimeUnit.MILLISECONDS.toMinutes(t);

            System.err.printf("Total time:   %d min %d sec\n", m, s - (m * 60));
        } else {
            System.err.printf("Total time:   %d sec\n", s);
        }

        Runtime r = Runtime.getRuntime();

        System.err.printf("Final memory: %dM/%dM\n", //
                (r.totalMemory() - r.freeMemory()) / MB, r.totalMemory() / MB);
    }

    /**
     * Scanner listener
     */
    private static final class IndexerListener
            implements ArtifactScanningListener {
        private final IndexingContext context;

        private final boolean debug;

        private boolean quiet;

        private long ts = System.currentTimeMillis();

        private int count;

        IndexerListener(IndexingContext context, boolean debug, boolean quiet) {
            this.context = context;
            this.debug = debug;
            this.quiet = quiet;
        }

        public void scanningStarted(IndexingContext context) {
            if (!quiet) {
                System.err.println("Scanning started");
            }
        }

        public void artifactDiscovered(ArtifactContext ac) {
            count++;

            long t = System.currentTimeMillis();

            ArtifactInfo ai = ac.getArtifactInfo();

            if (!quiet && debug && "maven-plugin".equals(ai.getPackaging())) {
                System.err.printf("Plugin: %s:%s:%s - %s %s\n", //
                        ai.getGroupId(), ai.getArtifactId(), ai.getVersion(), ai.getPrefix(), "" + ai.getGoals());
            }

            if (!quiet && (debug || (t - ts) > 2000L)) {
                System.err.printf("  %6d %s\n", count, formatFile(ac.getPom()));
                ts = t;
            }
        }

        public void artifactError(ArtifactContext ac, Exception e) {
            if (!quiet) {
                System.err.printf("! %6d %s - %s\n", count, formatFile(ac.getPom()), e.getMessage());

                System.err.printf("         %s\n", formatFile(ac.getArtifact()));

                if (debug) {
                    e.printStackTrace();
                }
            }

            ts = System.currentTimeMillis();
        }

        private String formatFile(File file) {
            return file.getAbsolutePath().substring(context.getRepository().getAbsolutePath().length() + 1);
        }

        public void scanningFinished(IndexingContext context, ScanningResult result) {
            if (!quiet) {
                if (result.hasExceptions()) {
                    System.err.printf("Scanning errors:   %s\n", result.getExceptions().size());
                }

                System.err.printf("Artifacts added:   %s\n", result.getTotalFiles());
                System.err.printf("Artifacts deleted: %s\n", result.getDeletedFiles());
            }
        }
    }

}
