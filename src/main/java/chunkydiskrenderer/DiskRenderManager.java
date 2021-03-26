package chunkydiskrenderer;

import se.llbit.chunky.PersistentSettings;
import se.llbit.chunky.main.Chunky;
import se.llbit.chunky.renderer.*;
import se.llbit.chunky.renderer.scene.Camera;
import se.llbit.chunky.renderer.scene.Scene;
import se.llbit.chunky.resources.BitmapImage;
import se.llbit.log.Log;
import se.llbit.math.Ray;
import se.llbit.util.TaskTracker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class DiskRenderManager extends Thread implements Renderer {
    private static final Repaintable EMPTY_CANVAS = () -> {};
    private Repaintable canvas = EMPTY_CANVAS;

    private boolean shouldFinalize = true;
    private final Scene bufferedScene;
    private final boolean headless;
    private int numThreads;

    private RenderMode mode = RenderMode.PREVIEW;

    private SnapshotControl snapshotControl = SnapshotControl.DEFAULT;

    private RenderContext context;

    private Random random;


    private int cpuLoad;
    private SceneProvider sceneProvider;

    private BiConsumer<Long, Integer> renderCompleteListener;
    private BiConsumer<Scene, Integer> frameCompleteListener;

    private Collection<RenderStatusListener> renderListeners = new ArrayList<>();
    private Collection<SceneStatusListener> sceneListeners = new ArrayList<>();

    private TaskTracker.Task renderTask;

    private int drawDepth = 256;
    private boolean drawEntities = true;

    public DiskRenderManager(RenderContext context, boolean headless) {
        super("Render Manager");

        this.setPriority(Thread.MAX_PRIORITY);

        numThreads = context.numRenderThreads();
        cpuLoad = PersistentSettings.getCPULoad();

        this.context = context;

        this.headless = headless;
        bufferedScene = context.getChunky().getSceneFactory().newScene();

        random = new Random(System.currentTimeMillis());
    }

    @Override
    public void setSceneProvider(SceneProvider sceneProvider) {
        this.sceneProvider = sceneProvider;
    }

    @Override
    public void setCanvas(Repaintable canvas) {
        this.canvas = canvas;
    }

    @Override
    public void setCPULoad(int loadPercent) {
        this.cpuLoad = loadPercent;
    }

    @Override
    public void setOnRenderCompleted(BiConsumer<Long, Integer> listener) {
        renderCompleteListener = listener;
    }

    @Override
    public void setOnFrameCompleted(BiConsumer<Scene, Integer> listener) {
        frameCompleteListener = listener;
    }

    @Override
    public void setSnapshotControl(SnapshotControl callback) {
        snapshotControl = callback;
    }

    @Override
    public void setRenderTask(TaskTracker.Task task) {
        renderTask = task;
    }

    @Override
    public synchronized void addRenderListener(RenderStatusListener listener) {
        renderListeners.add(listener);
    }

    @Override
    public void removeRenderListener(RenderStatusListener listener) {
        renderListeners.remove(listener);
    }

    @Override
    public synchronized void addSceneStatusListener(SceneStatusListener listener) {
        sceneListeners.add(listener);
    }

    @Override
    public void removeSceneStatusListener(SceneStatusListener listener) {
        sceneListeners.remove(listener);
    }

    @Override
    public void withBufferedImage(Consumer<BitmapImage> consumer) {
        bufferedScene.withBufferedImage(consumer);
    }

    @Override
    public void withSampleBufferProtected(SampleBufferConsumer consumer) {
        synchronized (bufferedScene) {
            consumer.accept(bufferedScene.getSampleBuffer(), bufferedScene.width, bufferedScene.height);
        }
    }

    @Override
    public RenderStatus getRenderStatus() {
        RenderStatus status;
        synchronized (bufferedScene) {
            status = new RenderStatus(bufferedScene.renderTime, bufferedScene.spp);
        }
        return status;
    }

    @Override
    public void shutdown() {
        interrupt();
    }

    private void updateRenderState(Scene scene) {
        shouldFinalize = scene.shouldFinalizeBuffer();
        if (mode != scene.getMode()) {
            mode = scene.getMode();
            renderListeners.forEach(listener -> listener.renderStateChanged(mode));
        }
    }

    private synchronized void sendSceneStatus(String status) {
        for (SceneStatusListener listener : sceneListeners) {
            listener.sceneStatus(status);
        }
    }

    @Override
    public void run() {
        try {
            while (!isInterrupted()) {
                ResetReason reason = sceneProvider.awaitSceneStateChange();

                synchronized (bufferedScene) {
                    sceneProvider.withSceneProtected(scene -> {
                        if (reason.overwriteState()) {
                            bufferedScene.copyState(scene);
                        }
                        if (reason == ResetReason.MATERIALS_CHANGED || reason == ResetReason.SCENE_LOADED) {
                            scene.importMaterials();
                        }

                        bufferedScene.copyTransients(scene);
                        updateRenderState(scene);

                        if (reason == ResetReason.SCENE_LOADED) {
                            bufferedScene.swapBuffers();

                            sendSceneStatus(bufferedScene.sceneStatus());
                        }
                    });
                }

                if (mode == RenderMode.PREVIEW) {
                    System.out.println("Previewing");
                    previewRender();
                } else {
                    System.out.println("Rendering");

                    int spp, targetSpp;
                    synchronized (bufferedScene) {
                        spp = bufferedScene.spp;
                        targetSpp = bufferedScene.getTargetSpp();
                        if (spp < targetSpp) {
                            updateRenderProgress();
                        }
                    }

                    if (spp < targetSpp) {
                    } else {
                        sceneProvider.withEditSceneProtected(scene -> {
                            scene.pauseRender();
                            updateRenderState(scene);
                        });
                    }
                }

                if (headless) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            // 3D view was closed.
        } catch (Throwable e) {
            Log.error("Unchecked exception in render manager", e);
        }
    }

    private void previewRender() throws InterruptedException {
        // Create new tracer
        LargeDiskRenderer tracer = new LargeDiskRenderer(bufferedScene);
        tracer.start();

        // Generate camera rays and start tracing
        Ray ray = new Ray();
        Camera cam = bufferedScene.camera();
        double halfWidth = bufferedScene.width / (2.0 * bufferedScene.height);
        double invHeight = 1.0 / bufferedScene.height;
        for (int i = 0; i < bufferedScene.width; i++) {
            for (int j = 0; j < bufferedScene.height; j++) {
                cam.calcViewRay(ray, -halfWidth + i * invHeight, -0.5 + j * invHeight);

                tracer.addRay(LargeDiskRenderer.CacheRay.create(
                        0, i, j,
                        LargeDiskRenderer.Float3.create((float) ray.o.x, (float) ray.o.y, (float) ray.o.z),
                        LargeDiskRenderer.Float3.create((float) ray.d.x, (float) ray.d.y, (float) ray.d.z)
                ));
            }
        }


        while (!tracer.doneTracing()) {
            double[] sampleBuffer = bufferedScene.getSampleBuffer();
            tracer.tracer.pool.submit(() -> tracer.getProcessRays().parallelStream().forEach(cacheRay -> {
                int offset = (cacheRay.x + cacheRay.y * bufferedScene.canvasWidth()) * 3;
                sampleBuffer[offset + 0] = cacheRay.color.x;
                sampleBuffer[offset + 1] = cacheRay.color.y;
                sampleBuffer[offset + 2] = cacheRay.color.z;
                bufferedScene.finalizePixel(cacheRay.x, cacheRay.y);
            })).join();
        }

        bufferedScene.swapBuffers();
        canvas.repaint();

        tracer.interrupt();
    }

    private void updateRenderProgress() {
        double renderTime = bufferedScene.renderTime / 1000.0;

        // Notify progress listener.
        int target = bufferedScene.getTargetSpp();
        long etaSeconds = (long) (((target - bufferedScene.spp) * renderTime) / bufferedScene.spp);
        if (etaSeconds > 0) {
            int seconds = (int) ((etaSeconds) % 60);
            int minutes = (int) ((etaSeconds / 60) % 60);
            int hours = (int) (etaSeconds / 3600);
            String eta = String.format("%d:%02d:%02d", hours, minutes, seconds);
            renderTask.update("Rendering", target, bufferedScene.spp, eta);
        } else {
            renderTask.update("Rendering", target, bufferedScene.spp, "");
        }

        synchronized (this) {
            renderListeners.forEach(listener -> {
                listener.setRenderTime(bufferedScene.renderTime);
                listener.setSamplesPerSecond(samplesPerSecond());
                listener.setSpp(bufferedScene.spp);
            });
        }
    }

    private int samplesPerSecond() {
        int canvasWidth = bufferedScene.canvasWidth();
        int canvasHeight = bufferedScene.canvasHeight();
        long pixelsPerFrame = (long) canvasWidth * canvasHeight;
        double renderTime = bufferedScene.renderTime / 1000.0;
        return (int) ((bufferedScene.spp * pixelsPerFrame) / renderTime);
    }
}
