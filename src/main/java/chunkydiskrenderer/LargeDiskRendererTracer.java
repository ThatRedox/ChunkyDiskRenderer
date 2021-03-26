package chunkydiskrenderer;

import it.unimi.dsi.fastutil.io.FastBufferedInputStream;
import se.llbit.chunky.PersistentSettings;
import se.llbit.chunky.chunk.BlockPalette;
import se.llbit.chunky.renderer.scene.Scene;
import se.llbit.log.Log;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;

/**
 * Intersect rays with a dual thread architecture.
 * One thread loads the next octree. One thread intersects with the octree.
 */
public class LargeDiskRendererTracer extends Thread {
    private ArrayList<File> octrees;
    private ArrayList<LargeDiskRenderer.CacheRay> rays = new ArrayList<>();
    private ExecutorService loader = Executors.newSingleThreadExecutor();
    private int threads;
    public ForkJoinPool pool;

    protected BlockPalette palette;
    protected Scene scene;

    private MiniPackedOctree currentOctree;
    private volatile MiniPackedOctree nextOctree;
    private final Object octreeDoneMonitor = new Object();

    private final Object traceMonitor = new Object();
    private final Object nextTraceMonitor = new Object();
    private volatile boolean traceDone = true;

    public LargeDiskRendererTracer(ArrayList<File> octrees) {
        super("Disk Render Tracer");
        this.octrees = octrees;
        this.pool = new ForkJoinPool(PersistentSettings.getNumThreads());
        this.threads = PersistentSettings.getNumThreads();
    }

    public boolean traceReady() {
        return !traceDone;
    }

    public void traceRays(Collection<LargeDiskRenderer.CacheRay> output, Collection<LargeDiskRenderer.CacheRay> newRays) throws InterruptedException {
        // Block until we are done with current batch
        synchronized (traceMonitor) {
            while (!traceDone) {
                traceMonitor.wait();
            }
        }

        // Copy finished rays
        if (output != null) {
            output.addAll(rays);
        }
        rays.clear();

        // Copy new rays
        if (newRays != null) {
            synchronized (newRays) {
                rays.addAll(newRays);
                newRays.clear();
            }
        }

        synchronized (nextTraceMonitor) {
            traceDone = false;
            nextTraceMonitor.notifyAll();
        }
    }

    private void loadNext(int i) {
        if (i >= octrees.size()) return;

        loader.execute(() -> {
            try {
                File octreeF = octrees.get(i);
                MiniPackedOctree octree = MiniPackedOctree.load(new DataInputStream(new FastBufferedInputStream(new GZIPInputStream(new FileInputStream(octreeF)))));
                synchronized (octreeDoneMonitor) {
                    nextOctree = octree;
                    octreeDoneMonitor.notifyAll();
                }
            } catch (IOException e) {
                Log.error(e);
            }
        });
    }

    @Override
    public void run() {
        try {
            while (!interrupted()) {
                // Load the next octree
                loadNext(1);

                // Wait for a trace job
                synchronized (nextTraceMonitor) {
                    while (traceDone) {
                        nextTraceMonitor.wait();
                    }
                }

                // Trace through each octree
                for (int i = 1; i < octrees.size(); i++) {
                    // Wait for octree
                    synchronized (octreeDoneMonitor) {
                        while (nextOctree == null) {
                            octreeDoneMonitor.wait();
                        }
                    }
                    currentOctree = nextOctree;
                    nextOctree = null;
                    loadNext(i+1);

                    pool.submit(() -> IntStream.range(0, threads).parallel().forEach(j -> {
                        for (int k = 0; k < rays.size(); k++) {
                            if (k % threads == j) {
                                currentOctree.intersect(rays.get(k), palette, scene);
                            }
                        }
                    })).join();
                }

                synchronized (traceMonitor) {
                    traceDone = true;
                    traceMonitor.notifyAll();
                }
            }
        } catch (InterruptedException e) {
            // Stopped
        }
    }
}
